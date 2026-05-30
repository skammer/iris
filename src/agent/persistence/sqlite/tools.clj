(ns agent.persistence.sqlite.tools
  (:require
   [agent.persistence.sqlite.common :as common]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/tools.sql")

(defn- row->approval [{:keys [id tool_name status input_json input_hash requested_permissions_json requested_by reason actor decision_reason expires_at created_at decided_at]}]
  {:id id
   :tool-name tool_name
   :status status
   :input (common/parse-json-string input_json)
   :input-hash input_hash
   :requested-permissions (set (map keyword (or (common/parse-json-string requested_permissions_json) [])))
   :requested-by requested_by
   :reason reason
   :actor actor
   :decision-reason decision_reason
   :expires-at expires_at
   :created-at created_at
   :decided-at decided_at})

(defn create-tool-approval! [store {:keys [tool-name input input-hash requested-permissions requested-by reason expires-at]}]
  (let [approval {:id (common/uuid-str)
                  :tool_name (common/normalize-name tool-name)
                  :input_json (common/json-string input)
                  :input_hash input-hash
                  :requested_permissions_json (common/json-string (mapv name requested-permissions))
                  :requested_by requested-by
                  :reason reason
                  :expires_at expires-at
                  :created_at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (create-tool-approval-sqlvec approval))))
    (row->approval (assoc approval
                          :status "pending"
                          :actor nil
                          :decision_reason nil
                          :decided_at nil))))

(defn get-tool-approval [store approval-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-tool-approval-sqlvec {:id approval-id}) identity)
              row->approval))))

(defn list-tool-approvals
  ([store] (list-tool-approvals store {}))
  ([store {:keys [status limit] :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->approval
             (common/select-many conn
                                 (list-tool-approvals-sqlvec {:status status
                                                              :limit limit})
                                 identity))))))

(defn decide-tool-approval! [store approval-id status actor decision-reason]
  (let [status* (common/normalize-name status)
        decided-at (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (let [updated (common/execute! conn
                                       (decide-tool-approval-sqlvec {:id approval-id
                                                                     :status status*
                                                                     :actor actor
                                                                     :decision_reason decision-reason
                                                                     :decided_at decided-at}))]
          (when (zero? updated)
            (throw (ex-info "Approval request is not pending or does not exist"
                            {:type :approval-decision-conflict
                             :approval-id approval-id})))))))
  (get-tool-approval store approval-id))

(defn count-tool-approvals [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-tool-approvals-sqlvec) identity) :n int))))
