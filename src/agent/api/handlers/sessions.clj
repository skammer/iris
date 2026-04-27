(ns agent.api.handlers.sessions
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.validation :as v]
   [agent.persistence.sqlite :as sqlite]))

(defn create [system request]
  (let [{:keys [title]} (h/read-json-body request)
        session (sqlite/create-session! (:store system) title)]
    (v/emit-system-event! system
                          {:event-type :session.created
                           :entity-type :session
                           :entity-id (:id session)
                           :payload {:title title}})
    (responses/json-response 201 (ser/session->response session))))

(defn list-sessions [system _request]
  (responses/json-response 200
                           {:data (mapv ser/session->response
                                        (sqlite/list-sessions (:store system)))}))

(defn list-messages [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200
                           {:data (mapv ser/message->response
                                        (sqlite/list-messages (:store system) session-id))}))
