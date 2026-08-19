(ns agent.persistence.sqlite.handoffs
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.sessions :as sessions]
   [hugsql.core :as hugsql]))

(declare upsert-restart-handoff-sqlvec
         get-restart-handoff-sqlvec
         get-session-restart-handoff-sqlvec
         list-resumable-restart-handoffs-sqlvec
         mark-restart-handoff-running-sqlvec
         attach-restart-handoff-message-sqlvec
         finish-restart-handoff-sqlvec
         find-restart-handoff-message-sqlvec)

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/handoffs.sql")

(def terminal-statuses #{:succeeded :failed})

(defn- row->handoff
  [{:keys [id session_id message permission_profile status message_id attempts
           last_error created_at started_at finished_at updated_at]}]
  {:id id
   :session-id session_id
   :message message
   :permission-profile (keyword permission_profile)
   :status (keyword status)
   :message-id message_id
   :attempts (long attempts)
   :last-error last_error
   :created-at created_at
   :started-at started_at
   :finished-at finished_at
   :updated-at updated_at})

(defn- row->message
  [{:keys [id session_id role content tool_calls tool_call_id metadata_json
           excluded_from_context created_at]}]
  (cond-> {:id id
           :session-id session_id
           :role role
           :content content
           :created-at created_at}
    tool_calls (assoc :tool-calls (common/parse-json-string tool_calls))
    tool_call_id (assoc :tool-call-id tool_call_id)
    metadata_json (assoc :metadata (common/parse-json-string metadata_json))
    (pos? (int (or excluded_from_context 0)))
    (assoc :excluded-from-context? true)))

(defn get-handoff [store handoff-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-restart-handoff-sqlvec {:id handoff-id})
                                 identity)
              row->handoff))))

(defn get-session-handoff [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-session-restart-handoff-sqlvec
                                  {:session_id session-id})
                                 identity)
              row->handoff))))

(defn schedule!
  [store {:keys [session-id message permission-profile]}]
  (let [now (common/now-str)
        handoff {:id (common/uuid-str)
                 :session_id session-id
                 :message message
                 :permission_profile (name (or permission-profile :chat))
                 :created_at now
                 :updated_at now}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (upsert-restart-handoff-sqlvec handoff))))
    (get-handoff store (:id handoff))))

(defn claim-resumable!
  [store]
  (let [now (common/now-str)]
    (common/with-transaction
      store
      (fn [conn]
        (let [rows (common/select-many conn
                                       (list-resumable-restart-handoffs-sqlvec)
                                       identity)]
          (doseq [{:keys [id]} rows]
            (common/execute! conn
                             (mark-restart-handoff-running-sqlvec
                              {:id id :started_at now :updated_at now})))
          (mapv #(-> (row->handoff %)
                     (assoc :status :running :started-at now :updated-at now)
                     (update :attempts inc))
                rows))))))

(defn- find-message [store {:keys [id session-id]}]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (find-restart-handoff-message-sqlvec
                                  {:session_id session-id :handoff_id id})
                                 identity)
              row->message))))

(defn ensure-message!
  [store {:keys [id session-id message] :as handoff}]
  (let [persisted (or (find-message store handoff)
                      (sessions/append-message!
                       store session-id "user" message
                       {:metadata {:restart-handoff-id id}}))
        now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn
                         (attach-restart-handoff-message-sqlvec
                          {:id id :message_id (:id persisted) :updated_at now}))))
    persisted))

(defn finish!
  [store handoff-id status error]
  (when-not (contains? terminal-statuses status)
    (throw (ex-info "Invalid restart handoff terminal status"
                    {:type :validation-failed :status status})))
  (let [now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn
                         (finish-restart-handoff-sqlvec
                          {:id handoff-id
                           :status (name status)
                           :last_error error
                           :finished_at now
                           :updated_at now}))))
    (get-handoff store handoff-id)))
