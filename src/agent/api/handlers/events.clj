(ns agent.api.handlers.events
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.persistence.sqlite :as sqlite]
   [clojure.core.async :as async]))

(defn list-events [system _request]
  (responses/json-response 200
                           {:data (mapv ser/event->response
                                        (sqlite/list-events (:store system) {:limit 100}))}))

(defn stream-response
  [system request]
  (let [stream-id (str "events-" (System/currentTimeMillis))
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (loop []
             (when @open?
               (when-let [event (some-> (async/<!! ch) :payload)]
                 (streaming/send-sse-chunk! channel
                                            {:id stream-id
                                             :object "event.chunk"
                                             :event (ser/event->response event)})
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))
