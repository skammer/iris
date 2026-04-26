(ns agent.persistence.sqlite.migrations
  (:require
   [agent.persistence.sqlite.common :as common]
   [ragtime.core :as ragtime]
   [ragtime.protocols :as ragtime-protocols]
   [ragtime.strategy :as ragtime-strategy]))

(def latest-schema-version 14)

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
          ON federation_outbox(peer_id, created_at DESC);"]}
   {:version 9
    :id "9"
    :name "workflow-idempotency"
    :checksum "7bf4270de8c46bd8"
    :irreversible? true
    :up ["ALTER TABLE agent_runs ADD COLUMN idempotency_key TEXT;"
         "CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_runs_idempotency_key
          ON agent_runs(idempotency_key)
          WHERE idempotency_key IS NOT NULL;"
         "CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_run_commands_run_request
          ON agent_run_commands(run_id, request_id)
          WHERE request_id IS NOT NULL;"
         "CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_run_heartbeats_run_sequence
          ON agent_run_heartbeats(run_id, sequence_no)
          WHERE sequence_no IS NOT NULL;"
         "CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_run_checkpoints_run_sequence_type
          ON agent_run_checkpoints(run_id, sequence_no, checkpoint_type)
          WHERE sequence_no IS NOT NULL;"]}
   {:version 10
    :id "10"
    :name "workflow-events-activities"
    :checksum "366f6a2322665cc9"
    :irreversible? true
    :up ["CREATE TRIGGER IF NOT EXISTS trg_agent_runs_requested_event
          AFTER INSERT ON agent_runs
          BEGIN
            INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
            VALUES ('agent.run.requested', 'agent_run', NEW.id, NEW.idempotency_key,
                    json_object('agent-id', NEW.agent_id,
                                'name', NEW.name,
                                'parent-run-id', NEW.parent_run_id,
                                'substrate', NEW.substrate,
                                'lease-id', NEW.lease_id,
                                'requested-by', NEW.requested_by,
                                'capabilities', json(NEW.capabilities_json),
                                'runner-options', json(NEW.runner_options_json)),
                    NEW.created_at);
          END;"
         "CREATE TRIGGER IF NOT EXISTS trg_agent_runs_status_event
          AFTER UPDATE OF status ON agent_runs
          WHEN OLD.status <> NEW.status
          BEGIN
            INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
            VALUES (CASE
                      WHEN NEW.status = 'running' THEN 'agent.run.registered'
                      ELSE 'agent.run.' || NEW.status
                    END,
                    'agent_run', NEW.id, NEW.idempotency_key,
                    json_object('status', NEW.status,
                                'last-error', NEW.last_error,
                                'agent-id', NEW.agent_id,
                                'network-identity', json(NEW.network_identity_json),
                                'runner-metadata', json(NEW.runner_metadata_json),
                                'finished-at', NEW.finished_at),
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
          END;"
         "CREATE TRIGGER IF NOT EXISTS trg_agent_run_heartbeats_event
          AFTER INSERT ON agent_run_heartbeats
          BEGIN
            INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
            VALUES ('agent.run.heartbeat', 'agent_run', NEW.run_id, NULL,
                    json_object('sequence-no', NEW.sequence_no,
                                'status', NEW.status),
                    NEW.observed_at);
          END;"
         "CREATE TRIGGER IF NOT EXISTS trg_agent_run_checkpoints_event
          AFTER INSERT ON agent_run_checkpoints
          BEGIN
            INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
            VALUES ('agent.run.checkpointed', 'agent_run', NEW.run_id, NULL,
                    json_object('sequence-no', NEW.sequence_no,
                                'checkpoint-type', NEW.checkpoint_type),
                    NEW.created_at);
          END;"
         "CREATE TRIGGER IF NOT EXISTS trg_agent_run_commands_enqueued_event
          AFTER INSERT ON agent_run_commands
          BEGIN
            INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
            VALUES ('agent.run.command.enqueued', 'agent_run', NEW.run_id, NEW.request_id,
                    json_object('command-id', NEW.id,
                                'command-type', NEW.command_type,
                                'request-id', NEW.request_id),
                    NEW.created_at);
          END;"
         "CREATE TRIGGER IF NOT EXISTS trg_agent_run_commands_status_event
          AFTER UPDATE OF status ON agent_run_commands
          WHEN OLD.status <> NEW.status
          BEGIN
            INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
            VALUES (CASE
                      WHEN NEW.status = 'acknowledged' THEN 'agent.run.command.acknowledged'
                      ELSE 'agent.run.command.completed'
                    END,
                    'agent_run', NEW.run_id, NEW.request_id,
                    json_object('command-id', NEW.id,
                                'request-id', NEW.request_id,
                                'status', NEW.status,
                                'error', NEW.error,
                                'response', json(NEW.response_json)),
                    coalesce(NEW.completed_at, NEW.acknowledged_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')));
          END;"
         "CREATE TABLE IF NOT EXISTS agent_run_activities (
            activity_key TEXT PRIMARY KEY,
            run_id TEXT NOT NULL,
            command_id TEXT,
            activity_name TEXT NOT NULL,
            status TEXT NOT NULL,
            input_json TEXT,
            result_json TEXT,
            error TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
          );"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_activities_run_created
          ON agent_run_activities(run_id, created_at DESC);"
         "CREATE INDEX IF NOT EXISTS idx_agent_run_activities_command_created
          ON agent_run_activities(command_id, created_at DESC);"]}
   {:version 11
    :id "11"
    :name "harden-tool-approvals"
    :checksum "f5a70d2e197b3f1"
    :irreversible? true
    :up ["ALTER TABLE tool_approvals ADD COLUMN input_hash TEXT;"
         "ALTER TABLE tool_approvals ADD COLUMN requested_permissions_json TEXT;"
         "ALTER TABLE tool_approvals ADD COLUMN expires_at TEXT;"
         "CREATE INDEX IF NOT EXISTS idx_tool_approvals_expires
          ON tool_approvals(expires_at);"]}
   {:version 12
    :id "12"
    :name "mandatory-memory-facts"
    :checksum "8c3e2b2d4a5f1201"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS memory_facts (
            id TEXT PRIMARY KEY,
            scope_type TEXT NOT NULL,
            scope_id TEXT,
            subject TEXT NOT NULL,
            predicate TEXT NOT NULL,
            object TEXT NOT NULL,
            normalized_subject TEXT NOT NULL,
            normalized_predicate TEXT NOT NULL,
            normalized_object TEXT NOT NULL,
            source_session_id TEXT,
            source_message_ids_json TEXT,
            source_request_id TEXT,
            confidence REAL,
            status TEXT NOT NULL,
            metadata_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
          );"
         "CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_facts_dedup
          ON memory_facts(scope_type,
                          coalesce(scope_id, ''),
                          normalized_subject,
                          normalized_predicate,
                          normalized_object);"
         "CREATE INDEX IF NOT EXISTS idx_memory_facts_scope_updated
          ON memory_facts(scope_type, scope_id, updated_at DESC);"]}
   {:version 13
    :id "13"
    :name "channel-session-mappings"
    :checksum "b35b0839c3f4b987"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS channel_session_mappings (
            source TEXT NOT NULL,
            external_chat_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            metadata_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY(source, external_chat_id),
            FOREIGN KEY(session_id) REFERENCES sessions(id)
          );"
         "CREATE INDEX IF NOT EXISTS idx_channel_session_mappings_session
          ON channel_session_mappings(session_id);"]}
   {:version 14
    :id "14"
    :name "channel-inbox-offsets"
    :checksum "d8b29a4f61c83e21"
    :irreversible? true
    :up ["CREATE TABLE IF NOT EXISTS channel_offsets (
            source TEXT PRIMARY KEY,
            next_offset INTEGER NOT NULL,
            updated_at TEXT NOT NULL
          );"
         "CREATE TABLE IF NOT EXISTS channel_inbox (
            source TEXT NOT NULL,
            update_id INTEGER NOT NULL,
            status TEXT NOT NULL,
            raw_json TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY(source, update_id)
          );"
         "CREATE INDEX IF NOT EXISTS idx_channel_inbox_status_updated
          ON channel_inbox(source, status, updated_at DESC);"]}])

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
    9 (cond-> []
         (not (common/column-exists? conn "agent_runs" "idempotency_key"))
         (conj (first up))
         true
         (into (rest up)))
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
