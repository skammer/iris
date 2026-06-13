(ns agent.system.events
  "Durable event pipeline. Logs system/runtime events to SQLite, mirrors them
   into telemetry/trace observers, publishes live copies through the broker,
   and replays persisted events for streaming clients."
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local-broker]
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.telemetry :as telemetry]
   [agent.telemetry.observer :as telemetry-observer]))

(defn- replay-broker-messages
  [store pattern {:keys [limit after-id]
                  :or {limit 100}}]
  (let [pattern* (str pattern)]
    (cond
      (nil? store) []

      (= pattern* (broker/all-events-subject))
      (mapv (fn [event] {:subject "events.all" :payload event})
            (reverse (sqlite/list-events store {:limit limit
                                                :after-id after-id})))

      :else [])))

(defn create-broker
  [store]
  (local-broker/create-broker {:replay-fn #(replay-broker-messages store %1 %2)}))

(defn create-event-bus
  []
  (create-broker nil))

(defn- observe-system-event!
  [telemetry-collector observer recorded]
  (if observer
    (telemetry-observer/record-event! observer {:event-type :system/event
                                                :payload recorded})
    (do
      (logging/log-system-event! recorded)
      (telemetry/record-system-event! telemetry-collector recorded))))

(defn- trace-system-event!
  [trace recorded]
  (let [entity-type (if (keyword? (:entity-type recorded))
                      (name (:entity-type recorded))
                      (str (:entity-type recorded)))]
    (runtime-trace/record-event!
     trace
     {:event-type (:event-type recorded)
      :turn-id (:request-id recorded)
      :channel (when (= "channel" entity-type)
                 (:entity-id recorded))
      :success (not (or (= "telegram.error" (:event-type recorded))
                        (and (= "agent-end" (:event-type recorded))
                             (contains? #{"error" "planner-error" "max-tokens"}
                                        (some-> (get-in recorded [:payload :stop-reason]) name)))
                        (= "failed" (get-in recorded [:payload :status]))))
      :error-message (or (get-in recorded [:payload :message])
                         (get-in recorded [:payload :error]))
      :payload (select-keys recorded [:event-type :entity-type :entity-id :request-id :payload])})))

(defn create-event-sink
  ([store broker-instance]
   (create-event-sink store broker-instance nil))
  ([store broker-instance telemetry-collector]
   (create-event-sink store broker-instance telemetry-collector nil nil))
  ([store broker-instance telemetry-collector observer trace]
   (fn [event]
     (let [recorded (sqlite/log-event! store event)]
       (observe-system-event! telemetry-collector observer recorded)
       (trace-system-event! trace recorded)
       (doseq [message (broker/event->messages recorded)]
         (broker/publish! broker-instance message))
       recorded))))

(defn log-event!
  [system event]
  ((:event-sink system) event))
