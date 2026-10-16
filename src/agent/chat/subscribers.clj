(ns agent.chat.subscribers
  "Chat-turn side effects driven by runtime events. Persists final messages,
   accumulates streaming deltas, invokes tool callbacks, and forwards every
   event to the system event sink."
  (:require
   [agent.chat.history :as history]
   [agent.chat.service :as service]
   [agent.chat.util :as chat-util]
   [agent.persistence.sqlite :as sqlite]))

(defn loop-event-sink
  [system subscribers]
  (fn [event]
    (doseq [{:keys [operation f required?]} subscribers]
      (try
        (f event)
        (catch Exception e
          (when required?
            (throw (ex-info "Required chat persistence failed"
                            {:type :chat-persistence-failed :operation operation} e)))
          (chat-util/emit-operation-failed! system
                                           (:entity-id event)
                                           (:request-id event)
                                           operation
                                           e
                                           {:trigger-event-type (:event-type event)}))))
    (chat-util/emit! system event)))

(defn- content-block-thinking [content-blocks]
  (not-empty
   (apply str
          (keep (fn [block]
                  (when (= "thinking" (some-> (:type block) name))
                    (:text block)))
                content-blocks))))

(defn persistence-subscriber
  [system session-id prompt request-id persisted]
  (fn [event]
    (let [payload (chat-util/event-payload event)]
      (cond
        (chat-util/same-event-type? event :message-start)
        (swap! persisted dissoc :thinking)

        (and (chat-util/same-event-type? event :message-update)
             (string? (:thinking-delta payload))
             (not= "" (:thinking-delta payload)))
        (swap! persisted update :thinking (fnil str "") (:thinking-delta payload))

        (and session-id
             (chat-util/same-event-type? event :message-update)
             (contains? #{:context-compacted "context-compacted"} (:kind payload)))
        (sqlite/append-entry! (:store system)
                              session-id
                              {:type :compaction
                               :payload (:compaction payload)})

        (chat-util/same-event-type? event :message-end)
        (let [{:keys [role content final? tool-turn? audit?]} payload
              thinking (or (not-empty (:thinking @persisted))
                           (content-block-thinking (:content-blocks payload)))
              payload* (cond-> payload
                         (and thinking (= "assistant" role))
                         (assoc :metadata (assoc (or (:metadata payload) {})
                                                 :thinking thinking)))]
          (cond
            (and (= "assistant" role) final?)
            (let [message (history/persist-completion! system
                                                       session-id
                                                       prompt
                                                       content
                                                       request-id
                                                       (history/message-extra payload* request-id))]
              (swap! persisted dissoc :thinking)
              (swap! persisted assoc :assistant-message message))

            (and session-id (= "assistant" role) (or audit? tool-turn?))
            (do
              (history/append-message-record! system session-id "assistant" content (history/message-extra payload* request-id))
              (swap! persisted dissoc :thinking))

            (and session-id (= "tool" role) tool-turn?)
            (history/append-message-record! system session-id "tool" content (history/message-extra payload* request-id))))))))

(defn streaming-subscriber
  [system session-id on-delta]
  (fn [event]
    (let [payload (chat-util/event-payload event)
          service (service/require-service system)
          request-id (:request-id event)]
      (locking (:manager-lock service)
        (when (let [owner (or (get-in @(:session-runtimes service) [session-id :active :request-id])
                             (get-in @(:streaming-state service) [session-id :request-id]))]
                (or (nil? owner) (= owner request-id)))
          (cond
            (and (chat-util/same-event-type? event :message-update)
                 (string? (:delta payload))
                 (not= "" (:delta payload)))
            (do
              (when session-id
                (swap! (:streaming-state (service/require-service system))
                       update session-id
                       (fn [state]
                         (update (assoc (or state {}) :request-id request-id)
                                 :content (fnil str "") (:delta payload)))))
              (when on-delta
                (on-delta (:delta payload))))

            (and (chat-util/same-event-type? event :message-update)
                 (string? (:thinking-delta payload))
                 (not= "" (:thinking-delta payload)))
            (when session-id
              (swap! (:streaming-state (service/require-service system))
                     update session-id
                     (fn [state]
                       (update (assoc (or state {}) :request-id request-id)
                               :thinking (fnil str "") (:thinking-delta payload)))))

            (and (chat-util/same-event-type? event :message-end)
                 (or (:final? payload) (:tool-turn? payload)))
            (service/clear-streaming! system session-id request-id)))))))

(defn tool-call-subscriber
  [on-tool-call]
  (fn [event]
    (when (and on-tool-call
               (chat-util/same-event-type? event :tool-execution-end))
      (let [{:keys [tool-call receipt]} (chat-util/event-payload event)]
        (on-tool-call {:tool-call tool-call :receipt receipt})))))
