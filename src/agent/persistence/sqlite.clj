(ns agent.persistence.sqlite
  "SQLite persistence facade. Owns database lifecycle/migrations and exposes
   cohesive session, event, tool approval, memory, and todo operations to the
   rest of the system."
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.events :as events]
   [agent.persistence.sqlite.memory :as memory]
   [agent.persistence.sqlite.migrations :as migrations]
   [agent.persistence.sqlite.sessions :as sessions]
   [agent.persistence.sqlite.tasks :as tasks]
   [agent.persistence.sqlite.todos :as todos]
   [agent.persistence.sqlite.tools :as tools]
   [clojure.java.io :as io]))

(def latest-schema-version migrations/latest-schema-version)

(defn jdbc-url [path]
  (common/jdbc-url path))

(defn- sqlite-files [path]
  (mapv #(io/file (str path %)) ["" "-wal" "-shm" "-journal"]))

(defn- delete-sqlite-files! [path]
  (doseq [file (sqlite-files path)]
    (when (.exists file)
      (io/delete-file file true))))

(defn- drift? [e]
  (= :migration-drift (:type (ex-data e))))

(defn- drift-recovery-error [path cause]
  (ex-info
   (str "SQLite migration drift detected. Delete the database files listed in ex-data, "
        "or set :storage :sqlite :destructive-reset-on-drift? true to rebuild them automatically.")
   (merge (ex-data cause)
          {:type :migration-drift
           :path path
           :files-to-delete (mapv #(.getAbsolutePath %) (sqlite-files path))
           :config-option [:storage :sqlite :destructive-reset-on-drift?]})
   cause))

(defn- open-store!
  [{:keys [path busy-timeout-ms] :as config}]
  (Class/forName "org.sqlite.JDBC")
  (let [store {:path path
               :busy-timeout-ms (or busy-timeout-ms common/default-busy-timeout-ms)
               :datasource (common/create-datasource config)
               :tx-lock (Object.)
               :evict-on-close? (true? (:evict-on-close? config))
               :journal-mode (or (:journal-mode config) "WAL")}]
    (try
      (common/apply-journal-mode! store)
      (migrations/migrate! store)
      store
      (catch Exception e
        (common/close-store! store)
        (throw e)))))

(defn create-store
  [{:keys [path destructive-reset-on-drift?] :as config}]
  (try
    (open-store! config)
    (catch Exception e
      (if (drift? e)
        (if destructive-reset-on-drift?
          (do
            (delete-sqlite-files! path)
            (open-store! config))
          (throw (drift-recovery-error path e)))
        (throw e)))))

(defn close-store! [store]
  (common/close-store! store))

(defn schema-version [store]
  (migrations/schema-version store))

(defn migration-history [store]
  (migrations/migration-history store))

(defn create-session!
  ([store] (sessions/create-session! store))
  ([store title] (sessions/create-session! store title))
  ([store title opts] (sessions/create-session! store title opts)))

(defn list-sessions
  ([store] (sessions/list-sessions store))
  ([store opts] (sessions/list-sessions store opts)))

(defn get-session [store session-id]
  (sessions/get-session store session-id))

(defn set-session-active-mode! [store session-id mode]
  (sessions/set-session-active-mode! store session-id mode))

(defn set-session-title-if-blank! [store session-id title]
  (sessions/set-session-title-if-blank! store session-id title))

(defn session-exists? [store session-id]
  (sessions/session-exists? store session-id))

(defn append-message!
  ([store session-id role content]
   (sessions/append-message! store session-id role content))
  ([store session-id role content extra]
   (sessions/append-message! store session-id role content extra)))

(defn list-messages [store session-id]
  (sessions/list-messages store session-id))

(defn list-messages-after [store session-id opts]
  (sessions/list-messages-after store session-id opts))

(defn update-message-runtime-flags! [store message-id flags]
  (sessions/update-message-runtime-flags! store message-id flags))

(defn append-entry!
  ([store session-id type payload]
   (sessions/append-entry! store session-id type payload))
  ([store session-id entry]
   (sessions/append-entry! store session-id entry)))

(defn list-entries [store session-id]
  (sessions/list-entries store session-id))

(defn leaf-entry [store session-id]
  (sessions/leaf-entry store session-id))

(defn select-leaf! [store session-id entry-id]
  (sessions/select-leaf! store session-id entry-id))

(defn branch-path
  ([store session-id] (sessions/branch-path store session-id))
  ([store session-id leaf-id] (sessions/branch-path store session-id leaf-id)))

(defn session-tree [store session-id]
  (sessions/session-tree store session-id))

(defn current-llm-context
  ([store session-id]
   (sessions/current-llm-context store session-id))
  ([store session-id opts]
   (sessions/current-llm-context store session-id opts)))

(defn search-messages
  ([store query] (sessions/search-messages store query))
  ([store query opts] (sessions/search-messages store query opts)))

(defn log-completion! [store completion]
  (sessions/log-completion! store completion))

(defn create-task! [store task]
  (tasks/create-task! store task))

(defn get-task [store task-id]
  (tasks/get-task store task-id))

(defn get-task-by-idempotency-key [store idempotency-key]
  (tasks/get-task-by-idempotency-key store idempotency-key))

(defn list-tasks
  ([store] (tasks/list-tasks store))
  ([store opts] (tasks/list-tasks store opts)))

(defn mark-task-started! [store task-id]
  (tasks/mark-task-started! store task-id))

(defn finish-task! [store task-id result]
  (tasks/finish-task! store task-id result))

(defn cancel-task! [store task-id]
  (tasks/cancel-task! store task-id))

(defn cancel-session-tasks! [store session-id]
  (tasks/cancel-session-tasks! store session-id))

(defn get-channel-session-mapping [store source external-chat-id]
  (sessions/get-channel-session-mapping store source external-chat-id))

(defn list-channel-session-mappings [store source]
  (sessions/list-channel-session-mappings store source))

(defn ensure-channel-session! [store mapping]
  (sessions/ensure-channel-session! store mapping))

(defn reset-channel-session! [store mapping]
  (sessions/reset-channel-session! store mapping))

(defn get-channel-offset [store source]
  (sessions/get-channel-offset store source))

(defn save-channel-offset! [store source next-offset]
  (sessions/save-channel-offset! store source next-offset))

(defn upsert-channel-inbox-update! [store source update-id update]
  (sessions/upsert-channel-inbox-update! store source update-id update))

(defn mark-channel-inbox-update! [store source update-id status last-error]
  (sessions/mark-channel-inbox-update! store source update-id status last-error))

(defn get-channel-inbox-update [store source update-id]
  (sessions/get-channel-inbox-update store source update-id))

(defn log-event! [store event]
  (events/log-event! store event))

(defn list-events
  ([store] (events/list-events store))
  ([store opts] (events/list-events store opts)))

(defn latest-event-id [store]
  (events/latest-event-id store))

(defn search-events
  ([store query] (events/search-events store query))
  ([store query opts] (events/search-events store query opts)))

(defn replace-vault-index!
  ([store notes] (memory/replace-vault-index! store notes))
  ([store notes opts] (memory/replace-vault-index! store notes opts)))

(defn search-vault-chunks
  ([store query] (memory/search-vault-chunks store query))
  ([store query opts] (memory/search-vault-chunks store query opts)))

(defn list-vault-notes
  ([store] (memory/list-vault-notes store))
  ([store opts] (memory/list-vault-notes store opts)))

(defn get-vault-note-by-id [store note-id]
  (memory/get-vault-note-by-id store note-id))

(defn create-memory-note-update! [store update]
  (memory/create-memory-note-update! store update))

(defn get-memory-note-update [store update-id]
  (memory/get-memory-note-update store update-id))

(defn list-memory-note-updates
  ([store] (memory/list-memory-note-updates store))
  ([store opts] (memory/list-memory-note-updates store opts)))

(defn update-memory-note-update-status!
  [store update-id expected-status status decision reason]
  (memory/update-memory-note-update-status!
   store update-id expected-status status decision reason))

(defn count-vault-notes [store]
  (memory/count-vault-notes store))

(defn count-vault-chunks [store]
  (memory/count-vault-chunks store))

(defn list-vault-chunks
  ([store] (memory/list-vault-chunks store))
  ([store opts] (memory/list-vault-chunks store opts)))

(defn list-memory-embeddings
  ([store] (memory/list-memory-embeddings store))
  ([store opts] (memory/list-memory-embeddings store opts)))

(defn list-vault-chunk-embeddings
  ([store] (memory/list-vault-chunk-embeddings store))
  ([store opts] (memory/list-vault-chunk-embeddings store opts)))

(defn list-vault-chunk-embedding-candidates
  ([store] (memory/list-vault-chunk-embedding-candidates store))
  ([store opts] (memory/list-vault-chunk-embedding-candidates store opts)))

(defn list-idle-extraction-candidates [store opts]
  (memory/list-idle-extraction-candidates store opts))

(defn get-memory-extraction-state [store session-id]
  (memory/get-memory-extraction-state store session-id))

(defn mark-memory-extraction-success! [store opts]
  (memory/mark-memory-extraction-success! store opts))

(defn mark-memory-extraction-failure! [store opts]
  (memory/mark-memory-extraction-failure! store opts))

(defn save-todo-list! [store todo-list]
  (todos/save-list! store todo-list))

(defn get-todo-list [store opts]
  (todos/get-list store opts))

(defn list-todo-lists
  ([store] (todos/list-lists store))
  ([store opts] (todos/list-lists store opts)))

(defn search-todo-lists
  ([store query] (todos/search-lists store query))
  ([store query opts] (todos/search-lists store query opts)))

(defn count-todo-lists [store]
  (todos/count-lists store))

(defn create-tool-approval! [store approval]
  (tools/create-tool-approval! store approval))

(defn get-tool-approval [store approval-id]
  (tools/get-tool-approval store approval-id))

(defn list-tool-approvals
  ([store] (tools/list-tool-approvals store))
  ([store opts] (tools/list-tool-approvals store opts)))

(defn decide-tool-approval! [store approval-id status actor decision-reason]
  (tools/decide-tool-approval! store approval-id status actor decision-reason))

(defn health-check [store]
  (try
    {:healthy true
     :details {:path (:path store)
               :session-count (sessions/count-sessions store)
               :event-count (events/count-events store)
               :task-count (tasks/count-tasks store)
               :tool-approval-count (tools/count-tool-approvals store)
	               :vault-note-count (memory/count-vault-notes store)
	               :vault-chunk-count (memory/count-vault-chunks store)
	               :todo-list-count (todos/count-lists store)
               :schema-version (migrations/schema-version store)
               :latest-schema-version migrations/latest-schema-version
               :up-to-date? (= (migrations/schema-version store)
                               migrations/latest-schema-version)}}
    (catch Exception e
      {:healthy false
       :details {:path (:path store)
                 :error (.getMessage e)}})))
