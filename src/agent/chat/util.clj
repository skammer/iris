(ns agent.chat.util
  "Shared chat helpers: event emission and event-shape utilities used across the
   chat front-end and its extracted concern namespaces."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str])
  )

(defn emit!
  "Emit an event through the system event-sink, falling back to direct
   persistence when no sink is wired."
  [system event]
  (if-let [sink (:event-sink system)]
    (sink event)
    (sqlite/log-event! (:store system) event)))

(defn emit-operation-failed!
  ([system session-id request-id operation error]
   (emit-operation-failed! system session-id request-id operation error nil))
  ([system session-id request-id operation error extra]
   (emit! system {:event-type :chat.operation.failed
                  :entity-type :session
                  :entity-id session-id
                  :request-id request-id
                  :payload (cond-> {:operation operation
                                    :message (.getMessage ^Throwable error)}
                             (ex-data error) (assoc :type (some-> error ex-data :type))
                             extra (merge extra))})))

(defn canonical-event-type [event]
  (keyword (str/replace (name (:event-type event)) #"_" "-")))

(defn same-event-type? [event event-type]
  (= event-type (canonical-event-type event)))

(defn event-payload [event]
  (let [payload (:payload event)]
    (if (map? payload) payload {:value payload})))
