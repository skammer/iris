(ns agent.api.handlers.channels
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.orchestrator :as orchestrator]))

(defn create [system request]
  (let [body (h/read-json-body request)
        name (or (:name body) "Channel")
        participants (or (:participants body) [])]
    (try
      (responses/json-response 201
                               (ser/channel->response
                                (orchestrator/create-channel! (:orchestrator system)
                                                              {:name name
                                                               :participants participants})))
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (errors/api-error 404 "agent_not_found" "Channel participant not found"))
          (throw e))))))

(defn list-channels [system _request]
  (responses/json-response 200
                           {:data (mapv ser/channel->response
                                        (orchestrator/list-channels (:orchestrator system)))}))

(defn list-messages [system _request channel-id]
  (try
    (responses/json-response 200
                             {:data (mapv ser/channel-message->response
                                          (orchestrator/list-channel-messages (:orchestrator system) channel-id))})
    (catch Exception e
      (if (= :channel-not-found (:type (ex-data e)))
        (throw (errors/api-error 404 "channel_not_found" "Channel not found"))
        (throw e)))))

(defn post-message [system request channel-id]
  (let [body (h/read-json-body request)
        sender-id (:sender_id body)
        content (:content body)]
    (try
      (responses/json-response 201
                               (ser/channel-message->response
                                (orchestrator/post-channel-message! (:orchestrator system)
                                                                    channel-id
                                                                    {:sender-id sender-id
                                                                     :content content})))
      (catch Exception e
        (case (:type (ex-data e))
          :channel-not-found (throw (errors/api-error 404 "channel_not_found" "Channel not found"))
          :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          :permission-denied (throw (errors/api-error 403 "permission_denied" "Sender is not a participant"))
          (throw e))))))
