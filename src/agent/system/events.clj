(ns agent.system.events
  "System event broker, replay, and sinks."
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local-broker]
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.telemetry :as telemetry]))

(defn- replay-broker-messages
  [store pattern {:keys [limit after-id since-sequence request-id]
                  :or {limit 100}}]
  (let [pattern* (str pattern)]
    (cond
      (nil? store) []

      (= pattern* (broker/all-events-subject))
      (mapv (fn [event] {:subject "events.all" :payload event})
            (reverse (sqlite/list-events store {:limit limit
                                                :after-id after-id})))

      (= pattern* (broker/all-runs-subject))
      (mapcat broker/event->messages
              (reverse (sqlite/list-events store {:entity-type :agent_run
                                                  :limit limit
                                                  :after-id after-id})))

      (re-matches #"runs\.([^\.]+)\.events" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.events" pattern*)]
        (mapv (fn [event] {:subject (broker/run-events-subject run-id)
                           :payload event})
              (reverse (sqlite/list-events store {:entity-type :agent_run
                                                  :entity-id run-id
                                                  :limit limit
                                                  :after-id after-id}))))

      (re-matches #"runs\.([^\.]+)\.commands" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.commands" pattern*)]
        (mapv broker/command->message
              (sqlite/list-agent-run-commands store run-id {:limit limit
                                                            :request-id request-id})))

      (re-matches #"runs\.([^\.]+)\.heartbeats" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.heartbeats" pattern*)]
        (mapv broker/heartbeat->message
              (sqlite/list-agent-run-heartbeats store run-id {:limit limit
                                                              :since-sequence since-sequence})))

      (re-matches #"runs\.([^\.]+)\.checkpoints" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.checkpoints" pattern*)]
        (mapv broker/checkpoint->message
              (sqlite/list-agent-run-checkpoints store run-id {:limit limit
                                                               :since-sequence since-sequence})))

      (re-matches #"runs\.([^\.]+)\.output" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.output" pattern*)]
        (mapv (fn [event] {:subject (broker/run-output-subject run-id)
                           :payload event})
              (reverse (sqlite/list-events store {:entity-type :agent_run
                                                  :entity-id run-id
                                                  :event-type "agent.run.output"
                                                  :limit limit
                                                  :after-id after-id}))))

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
    (telemetry/record-event! observer {:event-type :system/event
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

(defn create-recorded-event-sink
  ([broker-instance]
   (create-recorded-event-sink broker-instance nil))
  ([broker-instance telemetry-collector]
   (create-recorded-event-sink broker-instance telemetry-collector nil nil))
  ([broker-instance telemetry-collector observer trace]
   (fn [recorded]
     (observe-system-event! telemetry-collector observer recorded)
     (trace-system-event! trace recorded)
     (doseq [message (broker/event->messages recorded)]
       (broker/publish! broker-instance message))
     recorded)))

(defn subscribe-events
  ([system] (subscribe-events system (broker/all-events-subject)))
  ([system pattern]
   (broker/subscribe! (:broker system) pattern)))

(defn unsubscribe-events
  [system subscription]
  (broker/unsubscribe! (:broker system) subscription))

(defn log-event!
  [system event]
  ((:event-sink system) event))

(defn list-events
  ([system] (list-events system {}))
  ([system opts]
   (sqlite/list-events (:store system) opts)))
