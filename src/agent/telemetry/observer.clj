(ns agent.telemetry.observer
  "IObserver protocol and observer sinks (multi/telemetry-collector/logging)."
  (:require
   [agent.logging :as logging]
   [agent.telemetry :as telemetry]))

(defprotocol IObserver
  (record-event! [this event])
  (record-metric! [this metric])
  (flush! [this])
  (observer-name [this]))

(defn- safe-observer-call
  [observer operation f]
  (try
    (f)
    (catch Exception e
      (logging/log-error! :agent.observer/callback-failed
                          e
                          {:observer/name (observer-name observer)
                           :observer/operation operation})
      nil)))

(defrecord MultiObserver [observers best-effort?]
  IObserver
  (record-event! [_ event]
    (doseq [observer observers]
      (if best-effort?
        (safe-observer-call observer :record-event #(record-event! observer event))
        (record-event! observer event))))
  (record-metric! [_ metric]
    (doseq [observer observers]
      (if best-effort?
        (safe-observer-call observer :record-metric #(record-metric! observer metric))
        (record-metric! observer metric))))
  (flush! [_]
    (doseq [observer observers]
      (if best-effort?
        (safe-observer-call observer :flush #(flush! observer))
        (flush! observer))))
  (observer-name [_] "multi"))

(defrecord TelemetryCollectorObserver [collector]
  IObserver
  (record-event! [_ {:keys [event-type payload]}]
    (case event-type
      :system/event (telemetry/record-system-event! collector payload)
      :llm/call (telemetry/record-llm-call! collector payload)
      :tool/call (telemetry/record-tool! collector payload)
      nil))
  (record-metric! [_ _metric] nil)
  (flush! [_] nil)
  (observer-name [_] "telemetry-collector"))

(defrecord LoggingObserver []
  IObserver
  (record-event! [_ {:keys [event-type payload] :as event}]
    (if (= :system/event event-type)
      (logging/log-system-event! payload)
      (logging/log! :agent.observer/event
                    {:observer/event-type (name event-type)
                     :observer/event event})))
  (record-metric! [_ metric]
    (logging/log! :agent.observer/metric {:observer/metric metric}))
  (flush! [_] nil)
  (observer-name [_] "logging"))

(defn create-observer
  ([collector] (create-observer collector {}))
  ([collector {:keys [enabled sinks best-effort?]
               :or {enabled true
                    sinks [:telemetry :logging]
                    best-effort? true}}]
   (if-not (true? enabled)
     nil
     (let [sinks* (set sinks)
           observers (cond-> []
                       (and collector (contains? sinks* :telemetry))
                       (conj (->TelemetryCollectorObserver collector))
                       (contains? sinks* :logging)
                       (conj (->LoggingObserver)))]
       (->MultiObserver observers (not (false? best-effort?)))))))
