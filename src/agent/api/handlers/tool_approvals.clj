(ns agent.api.handlers.tool-approvals
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]))

(defn- tool-description [system tool-name]
  (some-> (:tool-registry system)
          (tools/get-tool tool-name)
          tools/describe))

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
        approval (tool-approvals/request-with-magi!
                  (:store system)
                  {:magi-service (:magi-service system)
                   :event-sink (:event-sink system)}
                  {:tool-name tool-name
                   :input input
                   :requested-by (or (:requested_by body) "api")
                   :reason (:reason body)
                   :expires-at (tool-approvals/default-expires-at system)}
                  (tool-description system tool-name)
                  {:user (or (:requested_by body) "api")})]
    (responses/json-response 201 {:data (ser/approval->response approval)})))

(defn decide [system request approval-id status]
  (let [body (h/read-json-body request)
        actor (or (:actor body) "api")
        reason (:reason body)
        updated (case status
                  :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                  :denied (tool-approvals/deny! (:store system) approval-id actor reason))]
    (tool-approvals/log-decision! (:event-sink system) updated status actor reason)
    (responses/json-response 200 {:data (ser/approval->response updated)})))
