(ns agent.persistence.sqlite
  "SQLite-backed persistence facade."
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.events :as events]
   [agent.persistence.sqlite.federation :as federation]
   [agent.persistence.sqlite.memory :as memory]
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

(defn get-channel-session-mapping [store source external-chat-id]
  (sessions/get-channel-session-mapping store source external-chat-id))

(defn upsert-channel-session-mapping! [store mapping]
  (sessions/upsert-channel-session-mapping! store mapping))

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

(defn save-memory-fact! [store fact]
  (memory/save-fact! store fact))

(defn merge-memory-fact-source! [store existing fact]
  (memory/merge-fact-source! store existing fact))

(defn search-memory-facts
  ([store query] (memory/search-facts store query))
  ([store query opts] (memory/search-facts store query opts)))

(defn count-memory-facts [store]
  (memory/count-facts store))

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

(defn get-agent-run-by-idempotency-key [store idempotency-key]
  (runs/get-agent-run-by-idempotency-key store idempotency-key))

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

(defn get-agent-run-heartbeat-by-sequence [store run-id sequence-no]
  (runs/get-agent-run-heartbeat-by-sequence store run-id sequence-no))

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

(defn get-agent-run-checkpoint-by-sequence-type [store run-id sequence-no checkpoint-type]
  (runs/get-agent-run-checkpoint-by-sequence-type store run-id sequence-no checkpoint-type))

(defn list-agent-run-checkpoints
  ([store run-id] (runs/list-agent-run-checkpoints store run-id))
  ([store run-id opts] (runs/list-agent-run-checkpoints store run-id opts)))

(defn start-agent-run-activity! [store activity]
  (runs/start-agent-run-activity! store activity))

(defn get-agent-run-activity [store activity-key]
  (runs/get-agent-run-activity store activity-key))

(defn complete-agent-run-activity! [store activity-key updates]
  (runs/complete-agent-run-activity! store activity-key updates))

(defn list-agent-run-activities
  ([store run-id] (runs/list-agent-run-activities store run-id))
  ([store run-id opts] (runs/list-agent-run-activities store run-id opts)))

(defn upsert-federation-peer-key! [store peer-key]
  (federation/upsert-peer-key! store peer-key))

(defn get-federation-peer-key [store peer-id key-id]
  (federation/get-peer-key store peer-id key-id))

(defn insert-federation-nonce! [store nonce]
  (federation/insert-nonce! store nonce))

(defn create-federation-outbox! [store outbox]
  (federation/create-outbox! store outbox))

(defn update-federation-outbox! [store id updates]
  (federation/update-outbox! store id updates))

(defn get-federation-outbox [store id]
  (federation/get-outbox store id))

(defn health-check [store]
  (schema/health-check store))
