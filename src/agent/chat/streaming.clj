(ns agent.chat.streaming
  "Streaming delta coalescing for the chat loop. The flusher batches rapid
  message-update deltas into one event per flush interval and passes all other
  events straight through."
  (:require
   [agent.chat.util :as chat-util]
   [agent.util :as util])
  (:import
   (java.util.concurrent TimeUnit)))

(def stream-flush-interval-ms 50)

(defn- text-delta-event? [event]
  (let [payload (chat-util/event-payload event)]
    (and (chat-util/same-event-type? event :message-update)
         (string? (:delta payload))
         (not= "" (:delta payload)))))

(defn- buffered-delta-event [event text]
  (-> event
      (assoc :payload (assoc (chat-util/event-payload event) :delta text))
      (assoc :timestamp (util/now-str))))

(defn stream-delta-flusher
  "Returns {:flush! fn :emit! fn}. :emit! coalesces consecutive text deltas and
   schedules a flush; any non-delta event flushes pending text first, preserving
   ordering."
  ([emit-event!] (stream-delta-flusher emit-event! nil))
  ([emit-event! scheduler]
  (let [lock (Object.)
        state (atom {:text ""
                     :event nil
                     :scheduled? false
                     :timer-id 0})
        flush! (fn [expected-timer-id]
                 (let [event* (locking lock
                                (let [{:keys [text event timer-id]} @state]
                                  (when (or (nil? expected-timer-id)
                                            (= expected-timer-id timer-id))
                                    (swap! state assoc
                                           :text ""
                                           :event nil
                                           :scheduled? false)
                                    (when (and event (not= "" text))
                                      (buffered-delta-event event text)))))]
                   (when event*
                     (emit-event! event*))))]
    {:flush! #(flush! nil)
     :emit! (fn [event]
              (if (text-delta-event? event)
                (let [[schedule? timer-id] (locking lock
                                             (let [schedule? (not (:scheduled? @state))]
                                               (swap! state
                                                      (fn [s]
                                                        (cond-> (-> s
                                                                    (update :text str (get-in event [:payload :delta]))
                                                                    (assoc :event event
                                                                           :scheduled? true))
                                                          schedule? (update :timer-id inc))))
                                               [schedule? (:timer-id @state)]))]
                  (when schedule?
                    (if scheduler
                      (.schedule scheduler
                                 ^Runnable #(flush! timer-id)
                                 (long stream-flush-interval-ms)
                                 TimeUnit/MILLISECONDS)
                      (future
                        (Thread/sleep stream-flush-interval-ms)
                        (flush! timer-id)))))
                (do
                  (flush! nil)
                  (emit-event! event))))})))
