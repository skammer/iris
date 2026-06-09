(ns agent.chat.history
  "Chat message persistence and LLM history shaping."
  (:require
   [agent.chat.service :as service]
   [agent.chat.util :as chat-util]
   [agent.llm.messages :as llm-messages]
   [agent.persistence.sqlite :as sqlite]
   [agent.util :as util]
   [clojure.string :as str]))

(def history-message-max-chars 8000)
(def queued-message-metadata-key :queued)

(defn- with-request-metadata [extra request-id]
  (cond
    request-id (update (or extra {}) :metadata #(assoc (or % {}) :request-id request-id))
    extra extra
    :else nil))

(defn- message-end-payload [message role content extra]
  (cond-> {:message-id (:id message) :role role :content content}
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
                              :payload (message-end-payload message role content extra)})
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

(defn- db-message-content [role content]
  (let [content* (or content "")]
    (if (and (= "tool" role) (string? content*))
      (util/truncate content* history-message-max-chars
                     #(str "\n\n[truncated " % " chars]"))
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

(defn- persist-latest-user-message!
  "Persists the latest user message in `messages` when its content is non-blank
   or `(persist-extra? extra)` is truthy, where `extra` is `(extra-fn message)`."
  [system session-id messages extra-fn persist-extra?]
  (when-let [message (and session-id (latest-user-message messages))]
    (let [content (llm-messages/content-text message)
          extra (extra-fn message)]
      (when (or (not (str/blank? content)) (persist-extra? extra))
        (append-message-record! system session-id "user" content extra)))))

(defn persist-user-turn! [system session-id messages request-id]
  (persist-latest-user-message! system session-id messages
                                #(with-request-metadata (content-blocks-extra %) request-id)
                                identity))

(defn persist-completion!
  ([system session-id prompt content request-id]
   (persist-completion! system session-id prompt content request-id nil))
  ([system session-id prompt content request-id extra]
   (let [llm (service/active-llm system)
         assistant-message (when session-id
                             (append-message-record! system
                                                     session-id
                                                     "assistant"
                                                     content
                                                     (with-request-metadata extra request-id)))]
     (sqlite/log-completion! (:store system)
                             {:session-id session-id
                              :provider (:provider llm)
                              :model (:model llm)
                              :prompt prompt
                              :response content})
     assistant-message)))

(defn message-extra
  ([payload]
   (select-keys payload [:content-blocks :tool-calls :tool-call-id :metadata :excluded-from-context?]))
  ([payload request-id]
   (with-request-metadata (message-extra payload) request-id)))

(defn queued-user-metadata [request-id]
  {queued-message-metadata-key true
   :request-id request-id})

(defn persist-queued-user-turn! [system session-id messages request-id]
  (persist-latest-user-message! system session-id messages
                                #(merge (content-blocks-extra %)
                                        {:metadata (queued-user-metadata request-id)
                                         :excluded-from-context? true
                                         :select-leaf? false})
                                :content-blocks))

(defn- update-queued-message!
  "Drops the queued flag from `queued-message`, merges `metadata-extra` into its
   metadata, persists the runtime flags, and emits :message.updated."
  [system queued-message request-id {:keys [metadata-extra excluded? runtime-flags]}]
  (when queued-message
    (let [metadata (-> (:metadata queued-message)
                       (dissoc queued-message-metadata-key)
                       (assoc :request-id request-id)
                       (merge metadata-extra))]
      (sqlite/update-message-runtime-flags! (:store system)
                                            (:id queued-message)
                                            (merge {:metadata metadata
                                                    :excluded-from-context? excluded?
                                                    :session-id (:session-id queued-message)}
                                                   runtime-flags))
      (chat-util/emit! system {:event-type :message.updated
                               :entity-type :session
                               :entity-id (:session-id queued-message)
                               :request-id request-id
                               :payload {:message-id (:id queued-message)
                                         :role "user"
                                         :metadata metadata
                                         :excluded-from-context? excluded?}})
      (assoc queued-message :metadata metadata :excluded-from-context? excluded?))))

(defn activate-queued-message! [system {:keys [queued-message request-id]}]
  (update-queued-message! system queued-message request-id
                          {:metadata-extra {:activated-at (util/now-str)}
                           :excluded? false
                           :runtime-flags {:reparent-to-current-leaf? true
                                           :select-leaf? true}}))

(defn mark-queued-message-cancelled! [system queued-message request-id]
  (update-queued-message! system queued-message request-id
                          {:metadata-extra {:cancelled? true
                                            :cancelled-at (util/now-str)}
                           :excluded? true
                           :runtime-flags {:select-leaf? false}}))
