(ns agent.persistence.sqlite
  "SQLite-backed persistence facade."
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.events :as events]
   [agent.persistence.sqlite.migrations :as migrations]
   [agent.persistence.sqlite.runs :as runs]
   [agent.persistence.sqlite.schema :as schema]
   [agent.persistence.sqlite.sessions :as sessions]
   [agent.persistence.sqlite.tools :as tools]))

(def latest-schema-version migrations/latest-schema-version)
(def default-busy-timeout-ms common/default-busy-timeout-ms)

(defn jdbc-url [path]
  (common/jdbc-url path))

(defn init-store!
  [{:keys [path busy-timeout-ms] :as config}]
  (Class/forName "org.sqlite.JDBC")
  (let [store {:path path
               :busy-timeout-ms (or busy-timeout-ms default-busy-timeout-ms)
               :datasource (common/create-datasource config)
               :tx-lock (Object.)
               :evict-on-close? (true? (:evict-on-close? config))
               :journal-mode (or (:journal-mode config) "WAL")}]
    (common/apply-journal-mode! store)
    (migrations/migrate! store)
    store))

(defn create-store [config]
  (init-store! config))

(defn close-store! [store]
  (common/close-store! store))

(defn schema-version [store]
  (migrations/schema-version store))

(defn migration-history [store]
  (migrations/migration-history store))

(defn create-session!
  ([store] (sessions/create-session! store))
  ([store title] (sessions/create-session! store title)))

(defn list-sessions [store]
  (sessions/list-sessions store))

(defn session-exists? [store session-id]
  (sessions/session-exists? store session-id))

(defn append-message! [store session-id role content]
  (sessions/append-message! store session-id role content))

(defn list-messages [store session-id]
  (sessions/list-messages store session-id))

(defn search-messages
  ([store query] (sessions/search-messages store query))
  ([store query opts] (sessions/search-messages store query opts)))

(defn log-completion! [store completion]
  (sessions/log-completion! store completion))

(defn log-event! [store event]
  (events/log-event! store event))

(defn list-events
  ([store] (events/list-events store))
  ([store opts] (events/list-events store opts)))

(defn search-events
  ([store query] (events/search-events store query))
  ([store query opts] (events/search-events store query opts)))

(defn create-tool-approval! [store approval]
  (tools/create-tool-approval! store approval))

(defn get-tool-approval [store approval-id]
  (tools/get-tool-approval store approval-id))

(defn list-tool-approvals
  ([store] (tools/list-tool-approvals store))
  ([store opts] (tools/list-tool-approvals store opts)))

(defn decide-tool-approval! [store approval-id status actor decision-reason]
  (tools/decide-tool-approval! store approval-id status actor decision-reason))

(defn create-agent-run! [store run]
  (runs/create-agent-run! store run))

(defn get-agent-run [store run-id]
  (runs/get-agent-run store run-id))

(defn list-agent-runs
  ([store] (runs/list-agent-runs store))
  ([store opts] (runs/list-agent-runs store opts)))

(defn update-agent-run! [store run-id updates]
  (runs/update-agent-run! store run-id updates))

(defn create-agent-run-lease! [store lease]
  (runs/create-agent-run-lease! store lease))

(defn latest-agent-run-lease [store run-id]
  (runs/latest-agent-run-lease store run-id))

(defn renew-agent-run-lease! [store lease-id expires-at]
  (runs/renew-agent-run-lease! store lease-id expires-at))

(defn release-agent-run-lease! [store lease-id]
  (runs/release-agent-run-lease! store lease-id))

(defn record-agent-run-heartbeat! [store heartbeat]
  (runs/record-agent-run-heartbeat! store heartbeat))

(defn latest-agent-run-heartbeat [store run-id]
  (runs/latest-agent-run-heartbeat store run-id))

(defn list-agent-run-heartbeats
  ([store run-id] (runs/list-agent-run-heartbeats store run-id))
  ([store run-id opts] (runs/list-agent-run-heartbeats store run-id opts)))

(defn enqueue-agent-run-command! [store command]
  (runs/enqueue-agent-run-command! store command))

(defn list-agent-run-commands
  ([store run-id] (runs/list-agent-run-commands store run-id))
  ([store run-id opts] (runs/list-agent-run-commands store run-id opts)))

(defn get-agent-run-command [store command-id]
  (runs/get-agent-run-command store command-id))

(defn update-agent-run-command! [store command-id updates]
  (runs/update-agent-run-command! store command-id updates))

(defn create-agent-run-checkpoint! [store checkpoint]
  (runs/create-agent-run-checkpoint! store checkpoint))

(defn latest-agent-run-checkpoint [store run-id]
  (runs/latest-agent-run-checkpoint store run-id))

(defn list-agent-run-checkpoints
  ([store run-id] (runs/list-agent-run-checkpoints store run-id))
  ([store run-id opts] (runs/list-agent-run-checkpoints store run-id opts)))

(defn health-check [store]
  (schema/health-check store))
