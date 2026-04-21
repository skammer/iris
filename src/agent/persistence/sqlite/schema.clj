(ns agent.persistence.sqlite.schema
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.events :as events]
   [agent.persistence.sqlite.federation :as federation]
   [agent.persistence.sqlite.migrations :as migrations]
   [agent.persistence.sqlite.runs :as runs]
   [agent.persistence.sqlite.tools :as tools]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/schema.sql")

(defn count-sessions [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-sessions-sqlvec) identity) :n int))))

(defn health-check [store]
  (try
    {:healthy true
     :details {:path (:path store)
               :session-count (count-sessions store)
               :event-count (events/count-events store)
               :tool-approval-count (tools/count-tool-approvals store)
               :agent-run-count (runs/count-agent-runs store)
               :federation-peer-key-count (federation/count-peer-keys store)
               :federation-outbox-count (federation/count-outbox store)
               :schema-version (migrations/schema-version store)
               :latest-schema-version migrations/latest-schema-version
               :up-to-date? (= (migrations/schema-version store)
                               migrations/latest-schema-version)}}
    (catch Exception e
      {:healthy false
       :details {:path (:path store)
                 :error (.getMessage e)}})))
