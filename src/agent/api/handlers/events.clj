(ns agent.api.handlers.events
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.defaults :as defaults]
   [agent.persistence.sqlite :as sqlite]))

(def ^:private default-limit 100)
(def ^:private max-limit 1000)

(defn- bounded-limit [limit]
  (min max-limit (max 1 (long (or limit default-limit)))))

(defn list-events [system request]
  (let [{:keys [limit]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/event->response
                                          (sqlite/list-events (:store system)
                                                              {:limit (bounded-limit limit)}))})))

(defn- next-event-id [stream-id counter event]
  (str (or (:id event)
           (str stream-id "-" (swap! counter inc)))))

(defn- send-event! [ctx stream-id counter event]
  (let [event-id (next-event-id stream-id counter event)]
    (streaming/send-sse-chunk! ctx
                               event-id
                               {:id event-id
                                :object "event.chunk"
                                :event (ser/event->response event)})))

(defn stream-response
  [system request]
  (let [stream-id (str "events-" (System/currentTimeMillis))
        fallback-id (atom 0)
        broker-instance (or (:event-bus system) (:broker system))]
    (streaming/managed-response
	     request
	     {:name :events-stream
          :metrics (:sse-metrics system)
	      :on-error (fn [ctx _error]
                  (streaming/send-sse-error! ctx "stream_error" "Stream failed"))}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                               broker-instance
                                               (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)]
         (loop []
           (when-let [event (some-> (streaming/take! ctx ch) :payload)]
             (send-event! ctx stream-id fallback-id event)
            (recur))))))))
