(ns agent.chat.history
  "Chat message persistence and LLM history shaping."
  (:require
   [agent.chat.service :as service]
   [agent.chat.util :as chat-util]
   [agent.llm.messages :as llm-messages]
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def history-message-max-chars 8000)
(def queued-message-metadata-key :queued)

(defn- message-end-payload [role content extra]
  (cond-> {:role role :content content}
    (:content-blocks extra) (assoc :content-blocks (:content-blocks extra))
    (:tool-calls extra) (assoc :tool-calls (:tool-calls extra))
    (:tool-call-id extra) (assoc :tool-call-id (:tool-call-id extra))
    (:metadata extra) (assoc :metadata (:metadata extra))
    (:excluded-from-context? extra) (assoc :excluded-from-context? true)))

(defn append-message-record!
  ([system session-id role content]
   (append-message-record! system session-id role content nil))
  ([system session-id role content extra]
   (sqlite/append-message! (:store system) session-id role content extra)))

(defn append-message!
  ([system session-id role content]
   (append-message! system session-id role content nil))
  ([system session-id role content extra]
   (let [message (append-message-record! system session-id role content extra)]
     (chat-util/emit! system {:event-type :message-end
                              :entity-type :session
                              :entity-id session-id
                              :payload (message-end-payload role content extra)})
     message)))

(defn append-control-turn! [system session-id user-text content metadata]
  (when-not (str/blank? (or user-text ""))
    (append-message! system session-id "user" user-text {:metadata metadata}))
  (append-message! system session-id "assistant" content {:metadata metadata})
  (service/emit-session-state! system session-id :loop)
  {:content content
   :stop-reason :loop-control})

(defn user-message? [message]
  (= "user" (if (keyword? (:role message))
              (name (:role message))
              (:role message))))

(defn latest-user-message [messages]
  (last (filter user-message? messages)))

(def ^:private media-block-types #{:image :audio :video :file})

(defn- media-block? [block]
  (contains? media-block-types (:type block)))

(defn content-blocks-extra [message]
  (let [blocks (seq (get (llm-messages/message->internal message) :content))]
    (when (some media-block? blocks)
      {:content-blocks (vec blocks)})))

(defn latest-user-prompt [messages]
  (some-> (latest-user-message messages)
          llm-messages/content-text))

(defn- truncate-text [text max-chars]
  (let [text* (or text "")]
    (if (> (count text*) max-chars)
      (str (subs text* 0 max-chars)
           "\n\n[truncated "
           (- (count text*) max-chars)
           " chars]")
      text*)))

(defn- db-message-content [role content]
  (let [content* (or content "")]
    (if (and (= "tool" role) (string? content*))
      (truncate-text content* history-message-max-chars)
      content*)))

(defn session-messages [system session-id]
  (if session-id
    (mapv (fn [{:keys [role content] :as message}]
            (llm-messages/message->internal
             (assoc message :content (db-message-content role content))))
          (sqlite/current-llm-context (:store system)
                                      session-id
                                      {:include-entry-id? true}))
    (llm-messages/messages->internal [])))

(defn persist-user-turn! [system session-id messages]
  (when-let [message (and session-id (latest-user-message messages))]
    (let [content (llm-messages/content-text message)
          extra (content-blocks-extra message)]
      (when (or (not (str/blank? content)) extra)
        (append-message-record! system session-id "user" content extra)))))

(defn persist-completion!
  ([system session-id prompt content request-id]
   (persist-completion! system session-id prompt content request-id nil))
  ([system session-id prompt content _request-id extra]
   (let [llm (service/active-llm system)
         assistant-message (when session-id
                             (append-message-record! system session-id "assistant" content extra))]
     (sqlite/log-completion! (:store system)
                             {:session-id session-id
                              :provider (:provider llm)
                              :model (:model llm)
                              :prompt prompt
                              :response content})
     assistant-message)))

(defn persist-final-assistant!
  ([system session-id prompt content request-id]
   (persist-final-assistant! system session-id prompt content request-id nil))
  ([system session-id prompt content request-id extra]
   (persist-completion! system session-id prompt content request-id extra)))

(defn message-extra [payload]
  (select-keys payload [:tool-calls :tool-call-id :metadata :excluded-from-context?]))

(defn queued-user-metadata [request-id]
  {queued-message-metadata-key true
   :request-id request-id})

(defn persist-queued-user-turn! [system session-id messages request-id]
  (when-let [message (and session-id (latest-user-message messages))]
    (let [content (llm-messages/content-text message)
          extra (merge (content-blocks-extra message)
                       {:metadata (queued-user-metadata request-id)
                        :excluded-from-context? true
                        :select-leaf? false})]
      (when (or (not (str/blank? content)) (:content-blocks extra))
        (append-message-record! system
                                session-id
                                "user"
                                content
                                extra)))))

(defn activate-queued-message! [system {:keys [queued-message request-id]}]
  (when queued-message
    (let [metadata (-> (:metadata queued-message)
                       (dissoc queued-message-metadata-key)
                       (assoc :request-id request-id
                              :activated-at (str (Instant/now))))]
      (sqlite/update-message-runtime-flags! (:store system)
                                            (:id queued-message)
                                            {:metadata metadata
                                             :excluded-from-context? false
                                             :session-id (:session-id queued-message)
                                             :reparent-to-current-leaf? true
                                             :select-leaf? true})
      (chat-util/emit! system {:event-type :message.updated
                               :entity-type :session
                               :entity-id (:session-id queued-message)
                               :request-id request-id
                               :payload {:message-id (:id queued-message)
                                         :role "user"
                                         :metadata metadata
                                         :excluded-from-context? false}})
      (assoc queued-message :metadata metadata :excluded-from-context? false))))
