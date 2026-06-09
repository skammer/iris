(ns agent.api.handlers.tool-approvals
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.system.events :as events]
   [agent.tools.approvals :as tool-approvals])
  (:import
   (java.time Instant)))

(defn approval-expires-at [system]
  (str (.plusSeconds (Instant/now)
                     (long (get-in system [:config :tools :approvals :ttl-seconds] 900)))))

(defn list-approvals [system request]
  (responses/json-response 200
                           {:data (mapv ser/approval->response
                                        (tool-approvals/list-requests (:store system)
                                                                      {:status (-> request :parameters :query :status)
                                                                       :limit 100}))}))

(defn create [system request]
  (let [body (h/read-json-body request)
        tool-name (keyword (:tool body))
        input (:input body)
        approval (tool-approvals/create-request!
                  (:store system)
                  {:tool-name tool-name
                   :input input
                   :requested-by (or (:requested_by body) "api")
                   :reason (:reason body)
                   :expires-at (approval-expires-at system)})]
    (events/log-event! system
                       {:event-type :tool.approval.requested
                        :entity-type :tool_approval
                        :entity-id (:id approval)
                        :payload {:tool-name (name tool-name)
                                  :requested-by (:requested-by approval)
                                  :requested-permissions (mapv name (:requested-permissions approval))
                                  :expires-at (:expires-at approval)}})
    (responses/json-response 201 {:data (ser/approval->response approval)})))

(defn decide [system request approval-id status]
  (let [body (h/read-json-body request)
        actor (or (:actor body) "api")
        reason (:reason body)
        updated (try
                  (case status
                    :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                    :denied (tool-approvals/deny! (:store system) approval-id actor reason))
                  (catch clojure.lang.ExceptionInfo e
                    (throw (errors/tool-error->api-error e))))]
    (events/log-event! system
                       {:event-type (keyword (str "tool.approval." (name status)))
                        :entity-type :tool_approval
                        :entity-id approval-id
                        :payload {:tool-name (:tool-name updated)
                                  :actor actor
                                  :decision status
                                  :reason reason}})
    (responses/json-response 200 {:data (ser/approval->response updated)})))
