(ns agent.chat.subscribers
  "Runtime event subscribers for persistence, streaming, and tool callbacks."
  (:require
   [agent.chat.history :as history]
   [agent.chat.service :as service]
   [agent.chat.util :as chat-util]
   [agent.persistence.sqlite :as sqlite]))

(defn loop-event-sink
  [system subscribers]
  (fn [event]
    (doseq [{:keys [operation f]} subscribers]
      (try
        (f event)
        (catch Exception e
          (chat-util/emit-operation-failed! system
                                           (:entity-id event)
                                           (:request-id event)
                                           operation
                                           e
                                           {:trigger-event-type (:event-type event)}))))
    (chat-util/emit! system event)))

(defn persistence-subscriber
  [system session-id prompt request-id persisted]
  (fn [event]
    (let [payload (chat-util/event-payload event)]
      (cond
        (and session-id
             (chat-util/same-event-type? event :message-update)
             (contains? #{:context-compacted "context-compacted"} (:kind payload)))
        (sqlite/append-entry! (:store system)
                              session-id
                              {:type :compaction
                               :payload (:compaction payload)})

        (chat-util/same-event-type? event :message-end)
        (let [{:keys [role content final? tool-turn? audit? tool-call-id]} payload]
          (cond
            (and (= "assistant" role) final?)
            (let [message (history/persist-final-assistant! system
                                                           session-id
                                                           prompt
                                                           content
                                                           request-id
                                                           (history/message-extra payload))]
              (swap! persisted assoc :assistant-message message))

            (and session-id (= "assistant" role) audit?)
            (history/append-message-record! system session-id "assistant" content (history/message-extra payload))

            (and session-id (= "assistant" role) tool-turn?)
            (history/append-message-record! system session-id "assistant" content (history/message-extra payload))

            (and session-id (= "tool" role) tool-turn?)
            (history/append-message-record! system session-id "tool" content {:tool-call-id tool-call-id})))))))

(defn streaming-subscriber
  [system session-id on-delta]
  (fn [event]
    (let [payload (chat-util/event-payload event)]
      (cond
        (and (chat-util/same-event-type? event :message-update)
             (string? (:delta payload))
             (not= "" (:delta payload)))
        (do
          (when session-id
            (swap! (:streaming-state (service/require-service system))
                   update session-id (fnil str "") (:delta payload)))
          (when on-delta
            (on-delta (:delta payload))))

        (and (chat-util/same-event-type? event :message-end)
             (or (:final? payload) (:tool-turn? payload)))
        (service/clear-streaming! system session-id)))))

(defn tool-call-subscriber
  [on-tool-call]
  (fn [event]
    (when (and on-tool-call
               (chat-util/same-event-type? event :tool-execution-end))
      (let [{:keys [tool-call receipt]} (chat-util/event-payload event)]
        (on-tool-call {:tool-call tool-call :receipt receipt})))))
