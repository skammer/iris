(ns agent.persistence.sqlite.migrations
  (:require
   [agent.persistence.sqlite.common :as common]
   [ragtime.core :as ragtime]
   [ragtime.protocols :as ragtime-protocols]
   [ragtime.strategy :as ragtime-strategy]))

(def latest-schema-version 8)

(def ^:private metadata-table "schema_migration_meta")

(def ^:private migration-descriptors
  [{:version 1
    :id "1"
    :name "initial-schema"
    :checksum "d846e9929ae182da"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS sessions (
            id TEXT PRIMARY KEY,
            title TEXT,
            created_at TEXT NOT NULL
          );"
         "CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY(session_id) REFERENCES sessions(id)
          );"
         "CREATE INDEX IF NOT EXISTS idx_messages_session_created
          ON messages(session_id, created_at);"
         "CREATE TABLE IF NOT EXISTS completions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id TEXT,
            provider TEXT NOT NULL,
            model TEXT,
            prompt TEXT,
            response TEXT,
            created_at TEXT NOT NULL
          );"]}
   {:version 2
    :id "2"
    :name "completion-created-index"
    :checksum "9d6f286ca4525909"
    :irreversible? true
    :up ["CREATE INDEX IF NOT EXISTS idx_completions_created
          ON completions(created_at);"]}
   {:version 3
    :id "3"
    :name "event-log"
    :checksum "bad410b46446a4ff"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS agent_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_type TEXT NOT NULL,
            entity_type TEXT,
            entity_id TEXT,
            request_id TEXT,
            payload TEXT,
            created_at TEXT NOT NULL
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_events_created
          ON agent_events(created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_agent_events_entity
          ON agent_events(entity_type, entity_id, created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_agent_events_request
          ON agent_events(request_id, created_at DESC);"]}
   {:version 4
    :id "4"
    :name "tool-approvals"
    :checksum "f6076f1a97ddf2e0"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS tool_approvals (
            id TEXT PRIMARY KEY,
            tool_name TEXT NOT NULL,
            status TEXT NOT NULL,
            input_json TEXT NOT NULL,
            requested_by TEXT,
            reason TEXT,
            actor TEXT,
            decision_reason TEXT,
            created_at TEXT NOT NULL,
            decided_at TEXT
          );"
         "CREATE INDEX IF NOT EXISTS idx_tool_approvals_status_created
          ON tool_approvals(status, created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_tool_approvals_tool_created
          ON tool_approvals(tool_name, created_at DESC);"]}
   {:version 5
    :id "5"
    :name "distributed-run-registry"
    :checksum "28efe801a91772fd"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS agent_runs (
            id TEXT PRIMARY KEY,
            agent_id TEXT NOT NULL,
            parent_run_id TEXT,
            lease_id TEXT,
            name TEXT,
            substrate TEXT NOT NULL,
            status TEXT NOT NULL,
            capabilities_json TEXT,
            network_identity_json TEXT,
            bootstrap_token TEXT,
            bootstrap_spec_json TEXT,
            runner_metadata_json TEXT,
            requested_by TEXT,
            last_error TEXT,
            created_at TEXT NOT NULL,
            started_at TEXT,
            finished_at TEXT
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_runs_status_created
          ON agent_runs(status, created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_created
          ON agent_runs(agent_id, created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_created
          ON agent_runs(parent_run_id, created_at DESC);"
         "CREATE TABLE IF NOT EXISTS agent_run_leases (
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            holder_id TEXT,
            status TEXT NOT NULL,
            acquired_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            released_at TEXT
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_leases_run_acquired
          ON agent_run_leases(run_id, acquired_at DESC);"
         "CREATE TABLE IF NOT EXISTS agent_run_heartbeats (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            run_id TEXT NOT NULL,
            sequence_no INTEGER NOT NULL,
            status TEXT,
            metrics_json TEXT,
            observed_at TEXT NOT NULL
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_heartbeats_run_observed
          ON agent_run_heartbeats(run_id, observed_at DESC);"
         "CREATE TABLE IF NOT EXISTS agent_run_commands (
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            command_type TEXT NOT NULL,
            payload_json TEXT,
            status TEXT NOT NULL,
            created_at TEXT NOT NULL,
            acknowledged_at TEXT,
            completed_at TEXT,
            error TEXT
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_commands_run_created
          ON agent_run_commands(run_id, created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_commands_run_status_created
          ON agent_run_commands(run_id, status, created_at DESC);"
         "CREATE TABLE IF NOT EXISTS agent_run_checkpoints (
            id TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            sequence_no INTEGER NOT NULL,
            checkpoint_type TEXT NOT NULL,
            state_json TEXT,
            created_at TEXT NOT NULL
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoints_run_created
          ON agent_run_checkpoints(run_id, created_at DESC);"]}
   {:version 6
    :id "6"
    :name "agent-runner-options"
    :checksum "4f9294070efb6b52"
    :irreversible? true
    :up ["ALTER TABLE agent_runs ADD COLUMN runner_options_json TEXT;"]}
   {:version 7
    :id "7"
    :name "agent-run-command-request-response"
    :checksum "d715b4ea611c879e"
    :irreversible? true
    :up ["ALTER TABLE agent_run_commands ADD COLUMN request_id TEXT;"
         "ALTER TABLE agent_run_commands ADD COLUMN response_json TEXT;"]}
   {:version 8
    :id "8"
    :name "federation-auth-outbox"
    :checksum "11c3e7e33dfb0c2"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS federation_peer_keys (
            peer_id TEXT NOT NULL,
            key_id TEXT NOT NULL,
            public_key TEXT NOT NULL,
            status TEXT NOT NULL,
            valid_from TEXT,
            valid_until TEXT,
            created_at TEXT NOT NULL,
            PRIMARY KEY(peer_id, key_id)
          );"
         "CREATE INDEX IF NOT EXISTS idx_federation_peer_keys_status
          ON federation_peer_keys(peer_id, status);"
         "CREATE TABLE IF NOT EXISTS federation_nonces (
            peer_id TEXT NOT NULL,
            nonce TEXT NOT NULL,
            seen_at TEXT NOT NULL,
            expires_at TEXT NOT NULL,
            PRIMARY KEY(peer_id, nonce)
          );"
         "CREATE INDEX IF NOT EXISTS idx_federation_nonces_expires
          ON federation_nonces(expires_at);"
         "CREATE TABLE IF NOT EXISTS federation_outbox (
            id TEXT PRIMARY KEY,
            peer_id TEXT NOT NULL,
            key_id TEXT,
            url TEXT,
            envelope_json TEXT NOT NULL,
            state TEXT NOT NULL,
            attempt_count INTEGER NOT NULL DEFAULT 0,
            next_attempt_at TEXT,
            last_error TEXT,
            last_status INTEGER,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
          );"
         "CREATE INDEX IF NOT EXISTS idx_federation_outbox_state_next
          ON federation_outbox(state, next_attempt_at);"
         "CREATE INDEX IF NOT EXISTS idx_federation_outbox_peer_created
          ON federation_outbox(peer_id, created_at DESC);"]}])

(defn descriptor-by-version [version]
  (some #(when (= version (:version %)) %) migration-descriptors))

(defn migration-history [store]
  (common/with-connection
    store
    (fn [conn]
      (if-not (common/table-exists? conn metadata-table)
        []
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
             :applied-at applied_at}))))))

(defn schema-version [store]
  (or (some->> (migration-history store) last :version)
      0))

(defn- migration-metadata-ddl! [conn]
  (common/execute-ddl! conn "CREATE TABLE IF NOT EXISTS schema_migration_meta (
                               version INTEGER PRIMARY KEY,
                               name TEXT NOT NULL,
                               checksum TEXT NOT NULL,
                               irreversible INTEGER NOT NULL,
                               applied_at TEXT NOT NULL
                             );"))

(defn- record-migration-meta! [conn {:keys [version name checksum irreversible?]}]
  (with-open [stmt (.prepareStatement conn
                                      "INSERT OR REPLACE INTO schema_migration_meta (version, name, checksum, irreversible, applied_at)
                                       VALUES (?, ?, ?, ?, ?)")]
    (.setInt stmt 1 (int version))
    (.setString stmt 2 name)
    (.setString stmt 3 checksum)
    (.setInt stmt 4 (if irreversible? 1 0))
    (.setString stmt 5 (common/now-str))
    (.executeUpdate stmt))
  (common/set-user-version! conn version))

(defn- remove-migration-meta! [conn version]
  (with-open [stmt (.prepareStatement conn
                                      "DELETE FROM schema_migration_meta WHERE version = ?")]
    (.setInt stmt 1 (int version))
    (.executeUpdate stmt))
  (common/set-user-version! conn (dec version)))

(defn- effective-up-statements [{:keys [version up]} conn]
  (case version
    6 (if (common/column-exists? conn "agent_runs" "runner_options_json") [] up)
    7 (cond-> []
         (not (common/column-exists? conn "agent_run_commands" "request_id"))
         (conj (first up))
         (not (common/column-exists? conn "agent_run_commands" "response_json"))
         (conj (second up)))
    up))

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
                                            "INSERT OR REPLACE INTO ragtime_migrations (id, created_at) VALUES (?, ?)")]
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

(defrecord SqliteMigration [id version up down]
  ragtime-protocols/Migration
  (id [_] id)
  (run-up! [_ data-store]
    (common/with-transaction
      (:store data-store)
      (fn [conn]
        (doseq [sql (effective-up-statements {:version version :up up} conn)]
          (common/execute-ddl! conn sql)))))
  (run-down! [_ data-store]
    (if (seq down)
      (common/with-transaction
        (:store data-store)
        (fn [conn]
          (doseq [sql down]
            (common/execute-ddl! conn sql))))
      (throw (ex-info "Irreversible migration"
                      {:type :irreversible-migration
                       :id id
                       :version version})))))

(defn- ragtime-migrations [_store]
  (mapv (fn [{:keys [id version up down]}]
          (->SqliteMigration id version up down))
        migration-descriptors))

(defn- datastore [store]
  (->SqliteDataStore store))

(defn- reporter [store]
  (fn [_store op version]
    (when-let [descriptor (descriptor-by-version (Integer/parseInt (str version)))]
      (case op
        :up (common/with-transaction store
              (fn [conn]
                (migration-metadata-ddl! conn)
                (record-migration-meta! conn descriptor)))
        :down (common/with-transaction store
                (fn [conn]
                  (migration-metadata-ddl! conn)
                  (remove-migration-meta! conn (:version descriptor))))
        nil))))

(defn- verify-migration-checksums! [store]
  (doseq [{:keys [version checksum]} (migration-history store)]
    (let [expected (descriptor-by-version version)]
      (when-not expected
        (throw (ex-info "Unknown applied migration"
                        {:type :migration-drift
                         :version version})))
      (when (not= checksum (:checksum expected))
        (throw (ex-info "Migration checksum drift detected"
                        {:type :migration-drift
                         :version version
                         :expected (:checksum expected)
                         :actual checksum}))))))

(defn migrate! [store]
  (common/with-transaction
    store
    (fn [conn]
      (migration-metadata-ddl! conn)))
  (let [migrations (ragtime-migrations store)
        index (ragtime/into-index migrations)]
    (ragtime/migrate-all
      (datastore store)
      index
      migrations
      {:strategy ragtime-strategy/apply-new
       :reporter (reporter store)}))
  (verify-migration-checksums! store)
  store)
