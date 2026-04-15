(ns agent.persistence.sqlite
  "SQLite-backed persistence for sessions, messages, and completion logs."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.sql DriverManager)
   (java.time Instant)
   (java.util UUID)))

(def latest-schema-version 2)

(defn- ensure-parent-dir! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(defn jdbc-url [path]
  (str "jdbc:sqlite:" path))

(defn- execute-ddl! [conn sql]
  (with-open [stmt (.createStatement conn)]
    (.execute stmt sql)))

(defn- select-bool [conn sql params]
  (with-open [stmt (.prepareStatement conn sql)]
    (doseq [[idx value] (map-indexed vector params)]
      (.setObject stmt (inc idx) value))
    (with-open [rs (.executeQuery stmt)]
      (and (.next rs)
           (pos? (.getInt rs 1))))))

(defn- table-exists? [conn table-name]
  (select-bool conn
               "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?"
               [table-name]))

(defn- get-user-version [conn]
  (with-open [stmt (.prepareStatement conn "PRAGMA user_version")
              rs (.executeQuery stmt)]
    (.next rs)
    (.getInt rs 1)))

(defn- set-user-version! [conn version]
  (execute-ddl! conn (str "PRAGMA user_version = " (int version))))

(defn- ensure-schema-migrations-table! [conn]
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                      );"))

(defn- migration-recorded? [conn version]
  (select-bool conn
               "SELECT COUNT(*) FROM schema_migrations WHERE version = ?"
               [version]))

(defn- record-migration! [conn version name]
  (when-not (migration-recorded? conn version)
    (with-open [stmt (.prepareStatement conn
                                       "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)")]
      (.setInt stmt 1 (int version))
      (.setString stmt 2 name)
      (.setString stmt 3 (str (Instant/now)))
      (.executeUpdate stmt))))

(defn- legacy-schema-present? [conn]
  (or (table-exists? conn "sessions")
      (table-exists? conn "messages")
      (table-exists? conn "completions")))

(defn- migration-1! [conn]
  (ensure-schema-migrations-table! conn)
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        created_at TEXT NOT NULL
                      );")
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(session_id) REFERENCES sessions(id)
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_messages_session_created
                      ON messages(session_id, created_at);")
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS completions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_id TEXT,
                        provider TEXT NOT NULL,
                        model TEXT,
                        prompt TEXT,
                        response TEXT,
                        created_at TEXT NOT NULL
                      );"))

(defn- migration-2! [conn]
  (ensure-schema-migrations-table! conn)
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_completions_created
                      ON completions(created_at);"))

(def ^:private migrations
  [{:version 1
    :name "initial-schema"
    :up migration-1!}
   {:version 2
    :name "completion-created-index"
    :up migration-2!}])

(defn- bootstrap-legacy-version! [conn]
  (when (and (zero? (get-user-version conn))
             (legacy-schema-present? conn))
    (ensure-schema-migrations-table! conn)
    (record-migration! conn 1 "initial-schema")
    (set-user-version! conn 1)))

(defn- apply-migration! [conn {:keys [version name up]}]
  (up conn)
  (ensure-schema-migrations-table! conn)
  (record-migration! conn version name)
  (set-user-version! conn version))

(defn- migrate! [conn]
  (execute-ddl! conn "PRAGMA journal_mode=WAL;")
  (bootstrap-legacy-version! conn)
  (let [current-version (get-user-version conn)]
    (doseq [migration migrations
            :when (> (:version migration) current-version)]
      (apply-migration! conn migration))))

(defn init-store!
  [{:keys [path]}]
  (Class/forName "org.sqlite.JDBC")
  (ensure-parent-dir! path)
  (with-open [conn (DriverManager/getConnection (jdbc-url path))]
    (migrate! conn))
  {:path path})

(defn create-store
  [config]
  (init-store! config))

(defn- with-connection [store f]
  (with-open [conn (DriverManager/getConnection (jdbc-url (:path store)))]
    (f conn)))

(defn schema-version
  [store]
  (with-connection store get-user-version))

(defn migration-history
  [store]
  (with-connection
    store
    (fn [conn]
      (if-not (table-exists? conn "schema_migrations")
        []
        (with-open [stmt (.prepareStatement conn
                                           "SELECT version, name, applied_at FROM schema_migrations ORDER BY version ASC")
                    rs (.executeQuery stmt)]
          (loop [acc []]
            (if (.next rs)
              (recur (conj acc {:version (.getInt rs "version")
                                :name (.getString rs "name")
                                :applied-at (.getString rs "applied_at")}))
              acc)))))))

(defn create-session!
  ([store] (create-session! store nil))
  ([store title]
   (let [id (str (UUID/randomUUID))
         created-at (str (Instant/now))]
     (with-connection
       store
       (fn [conn]
         (with-open [stmt (.prepareStatement conn
                                            "INSERT INTO sessions (id, title, created_at) VALUES (?, ?, ?)")]
           (.setString stmt 1 id)
           (.setString stmt 2 title)
           (.setString stmt 3 created-at)
           (.executeUpdate stmt))))
     {:id id
      :title title
      :created-at created-at})))

(defn list-sessions
  [store]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT id, title, created_at FROM sessions ORDER BY created_at DESC")
                  rs (.executeQuery stmt)]
        (loop [acc []]
          (if (.next rs)
            (recur (conj acc {:id (.getString rs "id")
                              :title (.getString rs "title")
                              :created-at (.getString rs "created_at")}))
            acc))))))

(defn session-exists?
  [store session-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT 1 FROM sessions WHERE id = ? LIMIT 1")]
        (.setString stmt 1 session-id)
        (with-open [rs (.executeQuery stmt)]
          (.next rs))))))

(defn append-message!
  [store session-id role content]
  (let [created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)")]
          (.setString stmt 1 session-id)
          (.setString stmt 2 role)
          (.setString stmt 3 content)
          (.setString stmt 4 created-at)
          (.executeUpdate stmt))))
    {:session-id session-id
     :role role
     :content content
     :created-at created-at}))

(defn list-messages
  [store session-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT role, content, created_at FROM messages WHERE session_id = ? ORDER BY id ASC")]
        (.setString stmt 1 session-id)
        (with-open [rs (.executeQuery stmt)]
          (loop [acc []]
            (if (.next rs)
              (recur (conj acc {:role (.getString rs "role")
                                :content (.getString rs "content")
                                :created-at (.getString rs "created_at")}))
              acc)))))))

(defn log-completion!
  [store {:keys [session-id provider model prompt response]}]
  (let [created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO completions (session_id, provider, model, prompt, response, created_at) VALUES (?, ?, ?, ?, ?, ?)")]
          (.setString stmt 1 session-id)
          (.setString stmt 2 (name provider))
          (.setString stmt 3 model)
          (.setString stmt 4 prompt)
          (.setString stmt 5 response)
          (.setString stmt 6 created-at)
          (.executeUpdate stmt))))
    {:session-id session-id
     :provider provider
     :model model
     :prompt prompt
     :response response
     :created-at created-at}))

(defn health-check
  [store]
  (try
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn "SELECT COUNT(*) AS n FROM sessions")
                    rs (.executeQuery stmt)]
          (.next rs)
          (let [schema-version (get-user-version conn)]
          {:healthy true
           :details {:path (:path store)
                     :session-count (.getInt rs "n")
                     :schema-version schema-version
                     :latest-schema-version latest-schema-version
                     :up-to-date? (= schema-version latest-schema-version)}}))))
    (catch Exception e
      {:healthy false
       :details {:path (:path store)
                 :error (.getMessage e)}})))
