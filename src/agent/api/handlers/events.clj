(ns agent.api.handlers.events
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.persistence.sqlite :as sqlite]))

(defn list-events [system _request]
  (responses/json-response 200
                           {:data (mapv ser/event->response
                                        (sqlite/list-events (:store system) {:limit 100}))}))

(defn stream-response
  [system request]
  (let [stream-id (str "events-" (System/currentTimeMillis))
        broker-instance (or (:event-bus system) (:broker system))]
    (streaming/managed-response
     request
     {:name :events-stream
      :on-error (fn [ctx error]
                  (streaming/send-sse-error! ctx "stream_error" (.getMessage error)))}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size 256
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)]
         (loop []
           (when-let [event (some-> (streaming/take! ctx ch) :payload)]
             (streaming/send-sse-chunk! ctx
                                        {:id stream-id
                                         :object "event.chunk"
                                         :event (ser/event->response event)})
            (recur))))))))
