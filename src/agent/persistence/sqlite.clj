(ns agent.persistence.sqlite
  "SQLite-backed persistence for sessions, messages, and completion logs."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io])
  (:import
   (java.sql DriverManager)
   (java.time Instant)
   (java.util UUID)))

(def latest-schema-version 6)

(defn- ensure-parent-dir! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(defn jdbc-url [path]
  (str "jdbc:sqlite:" path))

(defn- normalize-name [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

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
      (table-exists? conn "completions")
      (table-exists? conn "agent_events")))

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

(defn- migration-3! [conn]
  (ensure-schema-migrations-table! conn)
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS agent_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_type TEXT NOT NULL,
                        entity_type TEXT,
                        entity_id TEXT,
                        request_id TEXT,
                        payload TEXT,
                        created_at TEXT NOT NULL
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_events_created
                      ON agent_events(created_at DESC);")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_events_entity
                      ON agent_events(entity_type, entity_id, created_at DESC);")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_events_request
                      ON agent_events(request_id, created_at DESC);"))

(defn- migration-4! [conn]
  (ensure-schema-migrations-table! conn)
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS tool_approvals (
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
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_tool_approvals_status_created
                      ON tool_approvals(status, created_at DESC);")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_tool_approvals_tool_created
                      ON tool_approvals(tool_name, created_at DESC);"))

(defn- migration-5! [conn]
  (ensure-schema-migrations-table! conn)
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS agent_runs (
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
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_runs_status_created
                      ON agent_runs(status, created_at DESC);")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_created
                      ON agent_runs(agent_id, created_at DESC);")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_created
                      ON agent_runs(parent_run_id, created_at DESC);")
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS agent_run_leases (
                        id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        holder_id TEXT,
                        status TEXT NOT NULL,
                        acquired_at TEXT NOT NULL,
                        expires_at TEXT NOT NULL,
                        released_at TEXT
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_run_leases_run_acquired
                      ON agent_run_leases(run_id, acquired_at DESC);")
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS agent_run_heartbeats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_id TEXT NOT NULL,
                        sequence_no INTEGER NOT NULL,
                        status TEXT,
                        metrics_json TEXT,
                        observed_at TEXT NOT NULL
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_run_heartbeats_run_observed
                      ON agent_run_heartbeats(run_id, observed_at DESC);")
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS agent_run_commands (
                        id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        command_type TEXT NOT NULL,
                        payload_json TEXT,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        acknowledged_at TEXT,
                        completed_at TEXT,
                        error TEXT
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_run_commands_run_created
                      ON agent_run_commands(run_id, created_at DESC);")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_run_commands_run_status_created
                      ON agent_run_commands(run_id, status, created_at DESC);")
  (execute-ddl! conn "CREATE TABLE IF NOT EXISTS agent_run_checkpoints (
                        id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        sequence_no INTEGER NOT NULL,
                        checkpoint_type TEXT NOT NULL,
                        state_json TEXT,
                        created_at TEXT NOT NULL
                      );")
  (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoints_run_created
                      ON agent_run_checkpoints(run_id, created_at DESC);"))

(defn- column-exists? [conn table-name column-name]
  (with-open [stmt (.prepareStatement conn (str "PRAGMA table_info(" table-name ")"))
              rs (.executeQuery stmt)]
    (loop []
      (if (.next rs)
        (if (= column-name (.getString rs "name"))
          true
          (recur))
        false))))

(defn- migration-6! [conn]
  (ensure-schema-migrations-table! conn)
  (when-not (column-exists? conn "agent_runs" "runner_options_json")
    (execute-ddl! conn "ALTER TABLE agent_runs ADD COLUMN runner_options_json TEXT;")))

(def ^:private migrations
  [{:version 1
    :name "initial-schema"
    :up migration-1!}
   {:version 2
    :name "completion-created-index"
    :up migration-2!}
   {:version 3
    :name "event-log"
    :up migration-3!}
   {:version 4
    :name "tool-approvals"
    :up migration-4!}
   {:version 5
    :name "distributed-run-registry"
    :up migration-5!}
   {:version 6
    :name "agent-runner-options"
    :up migration-6!}])

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

(defn search-messages
  ([store query] (search-messages store query {}))
  ([store query {:keys [limit] :or {limit 20}}]
   (let [needle (str "%" (or query "") "%")]
     (with-connection
       store
       (fn [conn]
         (with-open [stmt (.prepareStatement conn
                                            "SELECT session_id, role, content, created_at
                                             FROM messages
                                             WHERE content LIKE ?
                                             ORDER BY id DESC
                                             LIMIT ?")]
           (.setString stmt 1 needle)
           (.setInt stmt 2 (int limit))
           (with-open [rs (.executeQuery stmt)]
             (loop [acc []]
               (if (.next rs)
                 (recur (conj acc {:session-id (.getString rs "session_id")
                                   :role (.getString rs "role")
                                   :content (.getString rs "content")
                                   :created-at (.getString rs "created_at")}))
                 acc)))))))))

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

(defn log-event!
  [store {:keys [event-type entity-type entity-id request-id payload created-at]}]
  (let [created-at* (or created-at (str (Instant/now)))
        event-type* (normalize-name event-type)
        entity-type* (normalize-name entity-type)
        payload-json (when (some? payload) (json/generate-string payload))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
                                            VALUES (?, ?, ?, ?, ?, ?)")]
          (.setString stmt 1 event-type*)
          (.setString stmt 2 entity-type*)
          (.setString stmt 3 entity-id)
          (.setString stmt 4 request-id)
          (.setString stmt 5 payload-json)
          (.setString stmt 6 created-at*)
          (.executeUpdate stmt))))
    {:event-type event-type*
     :entity-type entity-type*
     :entity-id entity-id
     :request-id request-id
     :payload payload
     :created-at created-at*}))

(defn list-events
  ([store] (list-events store {}))
  ([store {:keys [entity-type entity-id request-id limit]
           :or {limit 100}}]
   (let [entity-type* (normalize-name entity-type)]
     (with-connection
       store
       (fn [conn]
         (with-open [stmt (.prepareStatement conn
                                            "SELECT id, event_type, entity_type, entity_id, request_id, payload, created_at
                                             FROM agent_events
                                             WHERE (? IS NULL OR entity_type = ?)
                                               AND (? IS NULL OR entity_id = ?)
                                               AND (? IS NULL OR request_id = ?)
                                             ORDER BY id DESC
                                             LIMIT ?")]
           (.setString stmt 1 entity-type*)
           (.setString stmt 2 entity-type*)
           (.setString stmt 3 entity-id)
           (.setString stmt 4 entity-id)
           (.setString stmt 5 request-id)
           (.setString stmt 6 request-id)
           (.setInt stmt 7 (int limit))
           (with-open [rs (.executeQuery stmt)]
             (loop [acc []]
               (if (.next rs)
                 (let [payload-json (.getString rs "payload")]
                   (recur (conj acc {:id (.getLong rs "id")
                                     :event-type (.getString rs "event_type")
                                     :entity-type (.getString rs "entity_type")
                                     :entity-id (.getString rs "entity_id")
                                     :request-id (.getString rs "request_id")
                                     :payload (when payload-json (json/parse-string payload-json true))
                                     :created-at (.getString rs "created_at")})))
                 acc)))))))))

(defn search-events
  ([store query] (search-events store query {}))
  ([store query {:keys [limit] :or {limit 20}}]
   (let [needle (str "%" (or query "") "%")]
     (with-connection
       store
       (fn [conn]
         (with-open [stmt (.prepareStatement conn
                                            "SELECT id, event_type, entity_type, entity_id, request_id, payload, created_at
                                             FROM agent_events
                                             WHERE event_type LIKE ?
                                                OR entity_id LIKE ?
                                                OR payload LIKE ?
                                             ORDER BY id DESC
                                             LIMIT ?")]
           (.setString stmt 1 needle)
           (.setString stmt 2 needle)
           (.setString stmt 3 needle)
           (.setInt stmt 4 (int limit))
           (with-open [rs (.executeQuery stmt)]
             (loop [acc []]
               (if (.next rs)
                 (let [payload-json (.getString rs "payload")]
                   (recur (conj acc {:id (.getLong rs "id")
                                     :event-type (.getString rs "event_type")
                                     :entity-type (.getString rs "entity_type")
                                     :entity-id (.getString rs "entity_id")
                                     :request-id (.getString rs "request_id")
                                     :payload (when payload-json (json/parse-string payload-json true))
                                     :created-at (.getString rs "created_at")})))
                 acc)))))))))

(defn create-tool-approval!
  [store {:keys [tool-name input requested-by reason]}]
  (let [id (str (UUID/randomUUID))
        created-at (str (Instant/now))
        tool-name* (normalize-name tool-name)
        input-json (json/generate-string input)]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO tool_approvals (id, tool_name, status, input_json, requested_by, reason, actor, decision_reason, created_at, decided_at)
                                            VALUES (?, ?, 'pending', ?, ?, ?, NULL, NULL, ?, NULL)")]
          (.setString stmt 1 id)
          (.setString stmt 2 tool-name*)
          (.setString stmt 3 input-json)
          (.setString stmt 4 requested-by)
          (.setString stmt 5 reason)
          (.setString stmt 6 created-at)
          (.executeUpdate stmt))))
    {:id id
     :tool-name tool-name*
     :status "pending"
     :input input
     :requested-by requested-by
     :reason reason
     :actor nil
     :decision-reason nil
     :created-at created-at
     :decided-at nil}))

(defn get-tool-approval
  [store approval-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT id, tool_name, status, input_json, requested_by, reason, actor, decision_reason, created_at, decided_at
                                          FROM tool_approvals
                                          WHERE id = ?")]
        (.setString stmt 1 approval-id)
        (with-open [rs (.executeQuery stmt)]
          (when (.next rs)
            {:id (.getString rs "id")
             :tool-name (.getString rs "tool_name")
             :status (.getString rs "status")
             :input (json/parse-string (.getString rs "input_json") true)
             :requested-by (.getString rs "requested_by")
             :reason (.getString rs "reason")
             :actor (.getString rs "actor")
             :decision-reason (.getString rs "decision_reason")
             :created-at (.getString rs "created_at")
             :decided-at (.getString rs "decided_at")}))))))

(defn list-tool-approvals
  ([store] (list-tool-approvals store {}))
  ([store {:keys [status limit] :or {limit 100}}]
   (with-connection
     store
     (fn [conn]
       (with-open [stmt (.prepareStatement conn
                                          "SELECT id, tool_name, status, input_json, requested_by, reason, actor, decision_reason, created_at, decided_at
                                           FROM tool_approvals
                                           WHERE (? IS NULL OR status = ?)
                                           ORDER BY created_at DESC
                                           LIMIT ?")]
         (.setString stmt 1 status)
         (.setString stmt 2 status)
         (.setInt stmt 3 (int limit))
         (with-open [rs (.executeQuery stmt)]
           (loop [acc []]
             (if (.next rs)
               (recur (conj acc {:id (.getString rs "id")
                                 :tool-name (.getString rs "tool_name")
                                 :status (.getString rs "status")
                                 :input (json/parse-string (.getString rs "input_json") true)
                                 :requested-by (.getString rs "requested_by")
                                 :reason (.getString rs "reason")
                                 :actor (.getString rs "actor")
                                 :decision-reason (.getString rs "decision_reason")
                                 :created-at (.getString rs "created_at")
                                 :decided-at (.getString rs "decided_at")}))
               acc))))))))

(defn decide-tool-approval!
  [store approval-id status actor decision-reason]
  (let [status* (normalize-name status)
        decided-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "UPDATE tool_approvals
                                            SET status = ?, actor = ?, decision_reason = ?, decided_at = ?
                                            WHERE id = ?")]
          (.setString stmt 1 status*)
          (.setString stmt 2 actor)
          (.setString stmt 3 decision-reason)
          (.setString stmt 4 decided-at)
          (.setString stmt 5 approval-id)
          (let [updated (.executeUpdate stmt)]
            (when (zero? updated)
              (throw (ex-info "Approval request not found"
                              {:type :approval-not-found
                               :approval-id approval-id})))))))
    (get-tool-approval store approval-id)))

(defn- json-string [value]
  (when (some? value)
    (json/generate-string value)))

(defn- parse-json-string [value]
  (when value
    (json/parse-string value true)))

(defn create-agent-run!
  [store {:keys [id agent-id parent-run-id lease-id name substrate status capabilities
                 network-identity bootstrap-token bootstrap-spec runner-metadata
                 runner-options requested-by last-error]
          :or {status "requested"}}]
  (let [id (or id (str (UUID/randomUUID)))
        created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO agent_runs (id, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, bootstrap_token, bootstrap_spec_json, runner_metadata_json, runner_options_json, requested_by, last_error, created_at, started_at, finished_at)
                                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)")]
          (.setString stmt 1 id)
          (.setString stmt 2 agent-id)
          (.setString stmt 3 parent-run-id)
          (.setString stmt 4 lease-id)
          (.setString stmt 5 name)
          (.setString stmt 6 (normalize-name substrate))
          (.setString stmt 7 (normalize-name status))
          (.setString stmt 8 (json-string capabilities))
          (.setString stmt 9 (json-string network-identity))
          (.setString stmt 10 bootstrap-token)
          (.setString stmt 11 (json-string bootstrap-spec))
          (.setString stmt 12 (json-string runner-metadata))
          (.setString stmt 13 (json-string runner-options))
          (.setString stmt 14 requested-by)
          (.setString stmt 15 last-error)
          (.setString stmt 16 created-at)
          (.executeUpdate stmt))))
    {:id id
     :agent-id agent-id
     :parent-run-id parent-run-id
     :lease-id lease-id
     :name name
     :substrate (normalize-name substrate)
     :status (normalize-name status)
     :capabilities capabilities
     :network-identity network-identity
     :bootstrap-token bootstrap-token
     :bootstrap-spec bootstrap-spec
     :runner-metadata runner-metadata
     :runner-options runner-options
     :requested-by requested-by
     :last-error last-error
     :created-at created-at
     :started-at nil
     :finished-at nil}))

(defn get-agent-run
  [store run-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT id, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, bootstrap_token, bootstrap_spec_json, runner_metadata_json, runner_options_json, requested_by, last_error, created_at, started_at, finished_at
                                          FROM agent_runs
                                          WHERE id = ?")]
        (.setString stmt 1 run-id)
        (with-open [rs (.executeQuery stmt)]
          (when (.next rs)
            {:id (.getString rs "id")
             :agent-id (.getString rs "agent_id")
             :parent-run-id (.getString rs "parent_run_id")
             :lease-id (.getString rs "lease_id")
             :name (.getString rs "name")
             :substrate (.getString rs "substrate")
             :status (.getString rs "status")
             :capabilities (parse-json-string (.getString rs "capabilities_json"))
             :network-identity (parse-json-string (.getString rs "network_identity_json"))
             :bootstrap-token (.getString rs "bootstrap_token")
             :bootstrap-spec (parse-json-string (.getString rs "bootstrap_spec_json"))
             :runner-metadata (parse-json-string (.getString rs "runner_metadata_json"))
             :runner-options (parse-json-string (.getString rs "runner_options_json"))
             :requested-by (.getString rs "requested_by")
             :last-error (.getString rs "last_error")
             :created-at (.getString rs "created_at")
             :started-at (.getString rs "started_at")
             :finished-at (.getString rs "finished_at")}))))))

(defn list-agent-runs
  ([store] (list-agent-runs store {}))
  ([store {:keys [status parent-run-id limit] :or {limit 100}}]
   (with-connection
     store
     (fn [conn]
       (with-open [stmt (.prepareStatement conn
                                          "SELECT id, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, bootstrap_token, bootstrap_spec_json, runner_metadata_json, runner_options_json, requested_by, last_error, created_at, started_at, finished_at
                                           FROM agent_runs
                                           WHERE (? IS NULL OR status = ?)
                                             AND (? IS NULL OR parent_run_id = ?)
                                           ORDER BY created_at DESC
                                           LIMIT ?")]
         (.setString stmt 1 status)
         (.setString stmt 2 status)
         (.setString stmt 3 parent-run-id)
         (.setString stmt 4 parent-run-id)
         (.setInt stmt 5 (int limit))
         (with-open [rs (.executeQuery stmt)]
           (loop [acc []]
             (if (.next rs)
               (recur (conj acc {:id (.getString rs "id")
                                 :agent-id (.getString rs "agent_id")
                                 :parent-run-id (.getString rs "parent_run_id")
                                 :lease-id (.getString rs "lease_id")
                                 :name (.getString rs "name")
                                 :substrate (.getString rs "substrate")
                                 :status (.getString rs "status")
                                 :capabilities (parse-json-string (.getString rs "capabilities_json"))
                                 :network-identity (parse-json-string (.getString rs "network_identity_json"))
                                 :bootstrap-token (.getString rs "bootstrap_token")
                                 :bootstrap-spec (parse-json-string (.getString rs "bootstrap_spec_json"))
                                 :runner-metadata (parse-json-string (.getString rs "runner_metadata_json"))
                                 :runner-options (parse-json-string (.getString rs "runner_options_json"))
                                 :requested-by (.getString rs "requested_by")
                                 :last-error (.getString rs "last_error")
                                 :created-at (.getString rs "created_at")
                                 :started-at (.getString rs "started_at")
                                 :finished-at (.getString rs "finished_at")}))
               acc))))))))

(defn update-agent-run!
  [store run-id updates]
  (let [status (some-> (:status updates) normalize-name)
        lease-id (:lease-id updates)
        network-json (when (contains? updates :network-identity)
                       (json-string (:network-identity updates)))
        capabilities-json (when (contains? updates :capabilities)
                            (json-string (:capabilities updates)))
        bootstrap-spec-json (when (contains? updates :bootstrap-spec)
                              (json-string (:bootstrap-spec updates)))
        runner-metadata-json (when (contains? updates :runner-metadata)
                               (json-string (:runner-metadata updates)))
        runner-options-json (when (contains? updates :runner-options)
                              (json-string (:runner-options updates)))
        started-at (or (:started-at updates)
                       (when (= status "running") (str (Instant/now))))
        finished-at (or (:finished-at updates)
                        (when (contains? #{"completed" "failed" "cancelled" "expired"} status)
                          (str (Instant/now))))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "UPDATE agent_runs
                                            SET status = COALESCE(?, status),
                                                lease_id = COALESCE(?, lease_id),
                                                network_identity_json = COALESCE(?, network_identity_json),
                                                capabilities_json = COALESCE(?, capabilities_json),
                                                bootstrap_spec_json = COALESCE(?, bootstrap_spec_json),
                                                runner_metadata_json = COALESCE(?, runner_metadata_json),
                                                runner_options_json = COALESCE(?, runner_options_json),
                                                last_error = COALESCE(?, last_error),
                                                started_at = COALESCE(?, started_at),
                                                finished_at = COALESCE(?, finished_at)
                                            WHERE id = ?")]
          (.setString stmt 1 status)
          (.setString stmt 2 lease-id)
          (.setString stmt 3 network-json)
          (.setString stmt 4 capabilities-json)
          (.setString stmt 5 bootstrap-spec-json)
          (.setString stmt 6 runner-metadata-json)
          (.setString stmt 7 runner-options-json)
          (.setString stmt 8 (:last-error updates))
          (.setString stmt 9 started-at)
          (.setString stmt 10 finished-at)
          (.setString stmt 11 run-id)
          (let [updated (.executeUpdate stmt)]
            (when (zero? updated)
              (throw (ex-info "Agent run not found" {:type :run-not-found
                                                     :run-id run-id})))))))
    (get-agent-run store run-id)))

(defn create-agent-run-lease!
  [store {:keys [run-id holder-id expires-at]
          :or {holder-id "runtime"}}]
  (let [id (str (UUID/randomUUID))
        acquired-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO agent_run_leases (id, run_id, holder_id, status, acquired_at, expires_at, released_at)
                                            VALUES (?, ?, ?, 'active', ?, ?, NULL)")]
          (.setString stmt 1 id)
          (.setString stmt 2 run-id)
          (.setString stmt 3 holder-id)
          (.setString stmt 4 acquired-at)
          (.setString stmt 5 expires-at)
          (.executeUpdate stmt))))
    (update-agent-run! store run-id {:lease-id id})
    {:id id
     :run-id run-id
     :holder-id holder-id
     :status "active"
     :acquired-at acquired-at
     :expires-at expires-at
     :released-at nil}))

(defn latest-agent-run-lease
  [store run-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT id, run_id, holder_id, status, acquired_at, expires_at, released_at
                                          FROM agent_run_leases
                                          WHERE run_id = ?
                                          ORDER BY acquired_at DESC
                                          LIMIT 1")]
        (.setString stmt 1 run-id)
        (with-open [rs (.executeQuery stmt)]
          (when (.next rs)
            {:id (.getString rs "id")
             :run-id (.getString rs "run_id")
             :holder-id (.getString rs "holder_id")
             :status (.getString rs "status")
             :acquired-at (.getString rs "acquired_at")
             :expires-at (.getString rs "expires_at")
             :released-at (.getString rs "released_at")}))))))

(defn renew-agent-run-lease!
  [store lease-id expires-at]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "UPDATE agent_run_leases
                                          SET expires_at = ?, status = 'active'
                                          WHERE id = ?")]
        (.setString stmt 1 expires-at)
        (.setString stmt 2 lease-id)
        (let [updated (.executeUpdate stmt)]
          (when (zero? updated)
            (throw (ex-info "Lease not found" {:type :lease-not-found
                                               :lease-id lease-id})))))))
  lease-id)

(defn release-agent-run-lease!
  [store lease-id]
  (let [released-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "UPDATE agent_run_leases
                                            SET status = 'released', released_at = ?
                                            WHERE id = ?")]
          (.setString stmt 1 released-at)
          (.setString stmt 2 lease-id)
          (let [updated (.executeUpdate stmt)]
            (when (zero? updated)
              (throw (ex-info "Lease not found" {:type :lease-not-found
                                                 :lease-id lease-id})))))))
    released-at))

(defn record-agent-run-heartbeat!
  [store {:keys [run-id sequence-no status metrics]}]
  (let [observed-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO agent_run_heartbeats (run_id, sequence_no, status, metrics_json, observed_at)
                                            VALUES (?, ?, ?, ?, ?)")]
          (.setString stmt 1 run-id)
          (.setInt stmt 2 (int sequence-no))
          (.setString stmt 3 (normalize-name status))
          (.setString stmt 4 (json-string metrics))
          (.setString stmt 5 observed-at)
          (.executeUpdate stmt))))
    {:run-id run-id
     :sequence-no sequence-no
     :status (normalize-name status)
     :metrics metrics
     :observed-at observed-at}))

(defn latest-agent-run-heartbeat
  [store run-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT run_id, sequence_no, status, metrics_json, observed_at
                                          FROM agent_run_heartbeats
                                          WHERE run_id = ?
                                          ORDER BY observed_at DESC
                                          LIMIT 1")]
        (.setString stmt 1 run-id)
        (with-open [rs (.executeQuery stmt)]
          (when (.next rs)
            {:run-id (.getString rs "run_id")
             :sequence-no (.getInt rs "sequence_no")
             :status (.getString rs "status")
             :metrics (parse-json-string (.getString rs "metrics_json"))
             :observed-at (.getString rs "observed_at")}))))))

(defn enqueue-agent-run-command!
  [store {:keys [run-id command-type payload]
          :or {payload {}}}]
  (let [id (str (UUID/randomUUID))
        created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO agent_run_commands (id, run_id, command_type, payload_json, status, created_at, acknowledged_at, completed_at, error)
                                            VALUES (?, ?, ?, ?, 'pending', ?, NULL, NULL, NULL)")]
          (.setString stmt 1 id)
          (.setString stmt 2 run-id)
          (.setString stmt 3 (normalize-name command-type))
          (.setString stmt 4 (json-string payload))
          (.setString stmt 5 created-at)
          (.executeUpdate stmt))))
    {:id id
     :run-id run-id
     :command-type (normalize-name command-type)
     :payload payload
     :status "pending"
     :created-at created-at
     :acknowledged-at nil
     :completed-at nil
     :error nil}))

(defn list-agent-run-commands
  ([store run-id] (list-agent-run-commands store run-id {}))
  ([store run-id {:keys [status limit] :or {limit 100}}]
   (with-connection
     store
     (fn [conn]
       (with-open [stmt (.prepareStatement conn
                                          "SELECT id, run_id, command_type, payload_json, status, created_at, acknowledged_at, completed_at, error
                                           FROM agent_run_commands
                                           WHERE run_id = ?
                                             AND (? IS NULL OR status = ?)
                                           ORDER BY created_at ASC
                                           LIMIT ?")]
         (.setString stmt 1 run-id)
         (.setString stmt 2 status)
         (.setString stmt 3 status)
         (.setInt stmt 4 (int limit))
         (with-open [rs (.executeQuery stmt)]
           (loop [acc []]
             (if (.next rs)
               (recur (conj acc {:id (.getString rs "id")
                                 :run-id (.getString rs "run_id")
                                 :command-type (.getString rs "command_type")
                                 :payload (parse-json-string (.getString rs "payload_json"))
                                 :status (.getString rs "status")
                                 :created-at (.getString rs "created_at")
                                 :acknowledged-at (.getString rs "acknowledged_at")
                                 :completed-at (.getString rs "completed_at")
                                 :error (.getString rs "error")}))
               acc))))))))

(defn update-agent-run-command!
  [store command-id {:keys [status error]}]
  (let [status* (some-> status normalize-name)
        now* (str (Instant/now))
        acknowledged-at (when (= status* "acknowledged") now*)
        completed-at (when (contains? #{"completed" "failed" "cancelled"} status*) now*)]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "UPDATE agent_run_commands
                                            SET status = COALESCE(?, status),
                                                acknowledged_at = COALESCE(?, acknowledged_at),
                                                completed_at = COALESCE(?, completed_at),
                                                error = COALESCE(?, error)
                                            WHERE id = ?")]
          (.setString stmt 1 status*)
          (.setString stmt 2 acknowledged-at)
          (.setString stmt 3 completed-at)
          (.setString stmt 4 error)
          (.setString stmt 5 command-id)
          (let [updated (.executeUpdate stmt)]
            (when (zero? updated)
              (throw (ex-info "Command not found" {:type :command-not-found
                                                   :command-id command-id})))))))
    command-id))

(defn create-agent-run-checkpoint!
  [store {:keys [run-id sequence-no checkpoint-type state]}]
  (let [id (str (UUID/randomUUID))
        created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO agent_run_checkpoints (id, run_id, sequence_no, checkpoint_type, state_json, created_at)
                                            VALUES (?, ?, ?, ?, ?, ?)")]
          (.setString stmt 1 id)
          (.setString stmt 2 run-id)
          (.setInt stmt 3 (int sequence-no))
          (.setString stmt 4 (normalize-name checkpoint-type))
          (.setString stmt 5 (json-string state))
          (.setString stmt 6 created-at)
          (.executeUpdate stmt))))
    {:id id
     :run-id run-id
     :sequence-no sequence-no
     :checkpoint-type (normalize-name checkpoint-type)
     :state state
     :created-at created-at}))

(defn latest-agent-run-checkpoint
  [store run-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT id, run_id, sequence_no, checkpoint_type, state_json, created_at
                                          FROM agent_run_checkpoints
                                          WHERE run_id = ?
                                          ORDER BY sequence_no DESC, created_at DESC
                                          LIMIT 1")]
        (.setString stmt 1 run-id)
        (with-open [rs (.executeQuery stmt)]
          (when (.next rs)
            {:id (.getString rs "id")
             :run-id (.getString rs "run_id")
             :sequence-no (.getInt rs "sequence_no")
             :checkpoint-type (.getString rs "checkpoint_type")
             :state (parse-json-string (.getString rs "state_json"))
             :created-at (.getString rs "created_at")}))))))

(defn health-check
  [store]
  (try
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn "SELECT COUNT(*) AS n FROM sessions")
                    rs (.executeQuery stmt)]
          (.next rs)
          (let [schema-version (get-user-version conn)
                event-count (if (table-exists? conn "agent_events")
                              (with-open [event-stmt (.prepareStatement conn "SELECT COUNT(*) AS n FROM agent_events")
                                          event-rs (.executeQuery event-stmt)]
                                (.next event-rs)
                                (.getInt event-rs "n"))
                              0)
                approval-count (if (table-exists? conn "tool_approvals")
                                 (with-open [approval-stmt (.prepareStatement conn "SELECT COUNT(*) AS n FROM tool_approvals")
                                             approval-rs (.executeQuery approval-stmt)]
                                   (.next approval-rs)
                                   (.getInt approval-rs "n"))
                                 0)
                run-count (if (table-exists? conn "agent_runs")
                            (with-open [run-stmt (.prepareStatement conn "SELECT COUNT(*) AS n FROM agent_runs")
                                        run-rs (.executeQuery run-stmt)]
                              (.next run-rs)
                              (.getInt run-rs "n"))
                            0)]
            {:healthy true
             :details {:path (:path store)
                       :session-count (.getInt rs "n")
                       :event-count event-count
                       :tool-approval-count approval-count
                       :agent-run-count run-count
                       :schema-version schema-version
                       :latest-schema-version latest-schema-version
                       :up-to-date? (= schema-version latest-schema-version)}}))))
    (catch Exception e
      {:healthy false
       :details {:path (:path store)
                 :error (.getMessage e)}})))
