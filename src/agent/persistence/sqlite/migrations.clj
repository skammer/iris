(ns agent.persistence.sqlite.migrations
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ragtime.core :as ragtime]
   [ragtime.protocols :as ragtime-protocols]
   [ragtime.strategy :as ragtime-strategy])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(def latest-schema-version 11)

(def ^:private metadata-table "schema_migration_meta")

(def migration-descriptors
  [{:version 1
    :id "001-baseline"
    :name "baseline-schema"
    :up-resource "agent/persistence/sqlite/migrations/001-baseline.up.sql"
    :irreversible? true}
   {:version 2
    :id "002-drop-runs"
    :name "drop-runs"
    :up-resource "agent/persistence/sqlite/migrations/002-drop-runs.up.sql"
    :irreversible? true}
   {:version 3
    :id "003-drop-federation"
    :name "drop-federation"
    :up-resource "agent/persistence/sqlite/migrations/003-drop-federation.up.sql"
    :irreversible? true}
   {:version 4
    :id "004-vault-memory-index"
    :name "vault-memory-index"
    :up-resource "agent/persistence/sqlite/migrations/004-vault-memory-index.up.sql"
    :irreversible? true}
   {:version 5
    :id "005-drop-memory-facts"
    :name "drop-memory-facts"
    :up-resource "agent/persistence/sqlite/migrations/005-drop-memory-facts.up.sql"
    :irreversible? true}
   {:version 6
    :id "006-memory-embeddings"
    :name "memory-embeddings"
    :up-resource "agent/persistence/sqlite/migrations/006-memory-embeddings.up.sql"
    :irreversible? true}
   {:version 7
    :id "007-chat-tasks"
    :name "chat-tasks"
    :up-resource "agent/persistence/sqlite/migrations/007-chat-tasks.up.sql"
    :irreversible? true}
   {:version 8
    :id "008-memory-idle-extraction"
    :name "memory-idle-extraction"
    :up-resource "agent/persistence/sqlite/migrations/008-memory-idle-extraction.up.sql"
    :irreversible? true}
   {:version 9
    :id "009-memory-note-updates"
    :name "memory-note-updates"
    :up-resource "agent/persistence/sqlite/migrations/009-memory-note-updates.up.sql"
    :irreversible? true}
   {:version 10
    :id "010-cron-jobs"
    :name "cron-jobs"
    :up-resource "agent/persistence/sqlite/migrations/010-cron-jobs.up.sql"
    :irreversible? true}
   {:version 11
    :id "011-restart-handoffs"
    :name "restart-handoffs"
    :up-resource "agent/persistence/sqlite/migrations/011-restart-handoffs.up.sql"
    :irreversible? true}])

(defn descriptor-by-version [version]
  (some #(when (= version (:version %)) %) migration-descriptors))

(defn- descriptor-by-id [id]
  (some #(when (= id (:id %)) %) migration-descriptors))

(defn- resource-text! [resource-path]
  (if-let [resource (io/resource resource-path)]
    (slurp resource)
    (throw (ex-info "Migration resource not found"
                    {:type :migration-resource-not-found
                     :resource resource-path}))))

(defn- append-line [statement line]
  (str statement (when-not (str/blank? statement) "\n") line))

(defn- sql-statements [sql]
  (letfn [(finish [statements statement]
            (cond-> statements
              (not (str/blank? statement)) (conj statement)))]
    (loop [lines (str/split-lines sql)
           statements []
           statement ""
           trigger? false]
      (if-let [line (first lines)]
        (let [trimmed (str/trim line)]
          (cond
            (or (str/blank? trimmed) (str/starts-with? trimmed "--"))
            (recur (rest lines) statements statement trigger?)

            (str/blank? statement)
            (let [trigger-start? (boolean (re-find #"(?i)^CREATE\s+TRIGGER\b" trimmed))
                  statement* line]
              (if (and (not trigger-start?) (str/ends-with? trimmed ";"))
                (recur (rest lines) (conj statements statement*) "" false)
                (recur (rest lines) statements statement* trigger-start?)))

            :else
            (let [statement* (append-line statement line)
                  end? (if trigger?
                         (boolean (re-find #"(?i)^END;\s*$" trimmed))
                         (str/ends-with? trimmed ";"))]
              (if end?
                (recur (rest lines) (conj statements statement*) "" false)
                (recur (rest lines) statements statement* trigger?)))))
        (finish statements statement)))))

(defn- descriptor-up [descriptor]
  (sql-statements (resource-text! (:up-resource descriptor))))

(defn- sha256-hex [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- migration-checksum [descriptor]
  (subs (sha256-hex (resource-text! (:up-resource descriptor))) 0 16))

(defn- migration-metadata-ddl! [conn]
  (common/execute-ddl! conn "CREATE TABLE IF NOT EXISTS schema_migration_meta (
                               version INTEGER PRIMARY KEY,
                               name TEXT NOT NULL,
                               checksum TEXT NOT NULL,
                               irreversible INTEGER NOT NULL,
                               applied_at TEXT NOT NULL
                             );"))

(defn- migration-history* [conn]
  (common/select-many
   conn
   ["SELECT version, name, checksum, irreversible, applied_at
     FROM schema_migration_meta
     ORDER BY version ASC"]
   (fn [{:keys [version name checksum irreversible applied_at]}]
     {:version (int version)
      :name name
      :checksum checksum
      :irreversible? (pos? (int irreversible))
      :applied-at applied_at})))

(defn migration-history [store]
  (common/with-connection
    store
    (fn [conn]
      (if-not (common/table-exists? conn metadata-table)
        []
        (migration-history* conn)))))

(defn schema-version [store]
  (or (some->> (migration-history store) last :version)
      0))

(defn- record-migration-meta! [conn {:keys [version name irreversible?] :as descriptor}]
  (with-open [stmt (.prepareStatement conn
                                      "INSERT OR REPLACE INTO schema_migration_meta
                                       (version, name, checksum, irreversible, applied_at)
                                       VALUES (?, ?, ?, ?, ?)")]
    (.setInt stmt 1 (int version))
    (.setString stmt 2 name)
    (.setString stmt 3 (migration-checksum descriptor))
    (.setInt stmt 4 (if irreversible? 1 0))
    (.setString stmt 5 (common/now-str))
    (.executeUpdate stmt))
  (common/set-user-version! conn version))

(defn- migration-drift! [details]
  (throw (ex-info "SQLite schema drift detected"
                  (assoc details :type :migration-drift))))

(defn- verify-migration-checksums! [store]
  (doseq [{:keys [version checksum]} (migration-history store)]
    (let [descriptor (descriptor-by-version version)]
      (when-not descriptor
        (migration-drift! {:reason :unknown-migration
                           :version version}))
      (let [expected (migration-checksum descriptor)]
        (when (not= checksum expected)
          (migration-drift! {:reason :checksum-mismatch
                             :version version
                             :expected expected
                             :actual checksum}))))))

(defn- unversioned-schema? [conn]
  (or (common/table-exists? conn "sessions")
      (common/table-exists? conn "messages")))

(defn- ensure-ragtime-table! [conn]
  (common/execute-ddl! conn "CREATE TABLE IF NOT EXISTS ragtime_migrations (
                               id TEXT PRIMARY KEY,
                               created_at TEXT NOT NULL
                             );"))

(defrecord SqliteDataStore [store]
  ragtime-protocols/DataStore
  (add-migration-id [_ migration-id]
    (common/with-transaction
      store
      (fn [conn]
        (ensure-ragtime-table! conn)
        (with-open [stmt (.prepareStatement conn
                                            "INSERT OR REPLACE INTO ragtime_migrations (id, created_at)
                                             VALUES (?, ?)")]
          (.setString stmt 1 (str migration-id))
          (.setString stmt 2 (common/now-str))
          (.executeUpdate stmt)))))
  (remove-migration-id [_ migration-id]
    (common/with-transaction
      store
      (fn [conn]
        (ensure-ragtime-table! conn)
        (with-open [stmt (.prepareStatement conn
                                            "DELETE FROM ragtime_migrations WHERE id = ?")]
          (.setString stmt 1 (str migration-id))
          (.executeUpdate stmt)))))
  (applied-migration-ids [_]
    (common/with-connection
      store
      (fn [conn]
        (ensure-ragtime-table! conn)
        (mapv :id
              (common/select-many conn
                                  ["SELECT id FROM ragtime_migrations ORDER BY id ASC"]
                                  identity))))))

(defrecord SqliteMigration [descriptor]
  ragtime-protocols/Migration
  (id [_] (:id descriptor))
  (run-up! [_ data-store]
    (common/with-transaction
      (:store data-store)
      (fn [conn]
        (doseq [sql (descriptor-up descriptor)]
          (common/execute-ddl! conn sql)))))
  (run-down! [_ _data-store]
    (throw (ex-info "Irreversible migration"
                    {:type :irreversible-migration
                     :id (:id descriptor)
                     :version (:version descriptor)}))))

(defn- ragtime-migrations []
  (mapv ->SqliteMigration migration-descriptors))

(defn- reporter [store]
  (fn [_store op migration-id]
    (when-let [descriptor (descriptor-by-id (str migration-id))]
      (case op
        :up (common/with-transaction store
              (fn [conn]
                (migration-metadata-ddl! conn)
                (record-migration-meta! conn descriptor)))
        nil))))

(defn migrate! [store]
  (common/with-transaction
    store
    (fn [conn]
      (let [has-meta? (common/table-exists? conn metadata-table)]
        (when (and (not has-meta?) (unversioned-schema? conn))
          (migration-drift! {:reason :unversioned-schema}))
        (migration-metadata-ddl! conn))))
  (let [migrations (ragtime-migrations)
        index (ragtime/into-index migrations)]
    (ragtime/migrate-all
     (->SqliteDataStore store)
     index
     migrations
     {:strategy ragtime-strategy/apply-new
      :reporter (reporter store)}))
  (verify-migration-checksums! store)
  store)
