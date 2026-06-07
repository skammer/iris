(ns agent.chat.streaming
  "Streaming delta coalescing for the chat loop. The flusher batches rapid
  message-update deltas into one event per flush interval and passes all other
  events straight through."
  (:require
   [agent.chat.util :as chat-util]
   [agent.util :as util])
  (:import
   (java.util.concurrent RejectedExecutionException ScheduledExecutorService TimeUnit)))

(def stream-flush-interval-ms 50)

(defn- delta-field [event]
  (let [payload (chat-util/event-payload event)]
    (when (chat-util/same-event-type? event :message-update)
      (cond
        (and (string? (:delta payload)) (not= "" (:delta payload))) :delta
        (and (string? (:thinking-delta payload)) (not= "" (:thinking-delta payload))) :thinking-delta))))

(defn- buffered-delta-event [event field text]
  (-> event
      (assoc :payload (assoc (chat-util/event-payload event) field text))
      (assoc :timestamp (util/now-str))))

(defn- fallback-schedule! [flush!]
  (future
    (Thread/sleep stream-flush-interval-ms)
    (flush!)))

(defn- schedule-flush! [scheduler flush!]
  (if (and scheduler
           (not (.isShutdown ^ScheduledExecutorService scheduler)))
    (try
      (.schedule ^ScheduledExecutorService scheduler
                 ^Runnable flush!
                 (long stream-flush-interval-ms)
                 TimeUnit/MILLISECONDS)
      (catch RejectedExecutionException _
        (fallback-schedule! flush!)))
    (fallback-schedule! flush!)))

(defn stream-delta-flusher
  "Returns {:flush! fn :emit! fn}. :emit! coalesces consecutive content or
   thinking deltas and flushes when delta kind changes, preserving ordering."
  ([emit-event!] (stream-delta-flusher emit-event! nil))
  ([emit-event! scheduler]
   (let [lock (Object.)
         state (atom {:field nil
                      :text ""
                      :event nil
                      :scheduled? false
                      :timer-id 0})
         flush! (fn [expected-timer-id]
                  (let [event* (locking lock
                                 (let [{:keys [field text event timer-id]} @state]
                                   (when (or (nil? expected-timer-id)
                                             (= expected-timer-id timer-id))
                                     (swap! state assoc
                                            :field nil
                                            :text ""
                                            :event nil
                                            :scheduled? false)
                                     (when (and event (not= "" text))
                                       (buffered-delta-event event field text)))))]
                    (when event*
                      (emit-event! event*))))]
     {:flush! #(flush! nil)
      :emit! (fn [event]
               (if-let [field (delta-field event)]
                 (let [payload (chat-util/event-payload event)
                       value (get payload field)
                       [event* schedule? timer-id] (locking lock
                                                      (let [flush-now? (and (:field @state)
                                                                            (not= field (:field @state)))
                                                            event* (when flush-now?
                                                                     (let [{:keys [field text event]} @state]
                                                                       (when (and event (not= "" text))
                                                                         (buffered-delta-event event field text))))
                                                            schedule? (or flush-now? (not (:scheduled? @state)))]
                                                        (swap! state
                                                               (fn [s]
                                                                 (cond-> (-> s
                                                                             (assoc :field field
                                                                                    :event event
                                                                                    :scheduled? true)
                                                                             (update :text #(str (if flush-now? "" %) value)))
                                                                   schedule? (update :timer-id inc))))
                                                        [event* schedule? (:timer-id @state)]))]
                   (when event*
                     (emit-event! event*))
                   (when schedule?
                     (schedule-flush! scheduler #(flush! timer-id))))
                 (do
                   (flush! nil)
                   (emit-event! event))))})))
