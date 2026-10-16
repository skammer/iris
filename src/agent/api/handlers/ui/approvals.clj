(ns agent.api.handlers.ui.approvals
  "Tool approval review and execution handlers."
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.api.handlers.ui.params :as params]))

(defn list-tool-approvals [system request]
  (let [limit (params/request-ui-limit request)]
    (responses/html-response 200
                             (ui/tool-approvals-fragment
                              (tool-approvals/list-review-records (:store system) {:limit limit})
                              {:limit limit}))))

(defn tool-approvals-status [system request]
  (let [limit (params/request-ui-limit request)]
    (responses/html-response
     200
     (ui/tool-approvals-status-fragment
      (tool-approvals/list-review-records (:store system) {:limit 100})
      limit))))

(defn tool-approval-detail [system _request approval-id]
  (responses/html-response 200
                           (ui/tool-approval-detail-fragment
                            (tool-approvals/get-request (:store system) approval-id))))

(defn tool-approval-decision [system request approval-id status]
  (let [body (h/read-form-body request)
        actor (or (:actor body) "operator")
        reason (:reason body)
        updated (case status
                  :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                  :denied (tool-approvals/deny! (:store system) approval-id actor reason))]
    (tool-approvals/log-decision! (:event-sink system) updated status actor reason)
    (responses/html-response 200
                             (ui/tool-approvals-fragment
                              (tool-approvals/list-review-records (:store system) {:limit 20})))))

(defn tool-approval-run [system _request approval-id]
  (let [{:keys [tool-name input permissions approval]} (tool-approvals/resolve-approved-request (:store system) approval-id)
        user (or (not-empty (:requested-by approval)) "ui")]
    (try
      (responses/html-response
       200
       (str (ui/tool-approvals-fragment
             (tool-approvals/list-review-records (:store system) {:limit 20}))
            (ui/tool-results-fragment
             tool-name
             200
             {:result (tools/execute-tool (:tool-registry system)
                                          tool-name
                                          input
                                          {:permissions permissions
                                           :approval-id approval-id
                                           :approval-reason (:decision-reason approval)
                                           :user user
                                           :activity (:activity input)})})))
      (catch Exception e
        (let [api-e (errors/domain-error->api-error e)]
          (responses/html-response
           (:status (ex-data api-e))
           (ui/tool-results-fragment
            tool-name
            (:status (ex-data api-e))
            {:error (:error (ex-data api-e))
             :message (.getMessage api-e)
             :details (:details (ex-data api-e))})))))))
