(ns agent.telegram.test-support
  (:require
   [agent.broker.core :as broker]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-telegram-" ".db")))

(defn chat-stub
  "`chat/run!` replacement for the broker-events chat runner. Calls
   `(handler opts emit!)` where `emit!` publishes a session event (event-type +
   payload) on the system :event-bus, then emits the terminal agent-end event
   the runner waits for and returns the handler's result map. When the handler
   throws, no agent-end is emitted — the runner must treat the error itself as
   terminal."
  [handler]
  (fn [system {:keys [session-id] :as opts}]
    (let [bus (or (:event-bus system) (:broker system))
          emit! (fn [event-type payload]
                  (doseq [message (broker/event->messages
                                   {:event-type (name event-type)
                                    :entity-type "session"
                                    :entity-id session-id
                                    :payload payload})]
                    (broker/publish! bus message)))
          result (handler opts emit!)]
      (emit! :agent-end {:stop-reason (or (:stop-reason result) :completed)})
      result)))

(defn eventually
  [pred]
  (loop [remaining 80]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do
              (Thread/sleep 25)
              (recur (dec remaining))))))

(defn update-for
  [update-id chat-id user-id text]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :text text}})

(defn callback-update-for
  [update-id chat-id user-id message-id data]
  {:update_id update-id
   :callback_query {:id (str "callback-" update-id)
                    :from {:id user-id}
                    :message {:message_id message-id
                              :chat {:id chat-id
                                     :type "private"
                                     :first_name "Test"}}
                    :data data}})

(defn photo-update-for
  [update-id chat-id user-id caption]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :caption caption
             :photo [{:file_id "small" :file_size 10 :width 10 :height 10}
                     {:file_id "big" :file_size 20 :width 20 :height 20}]}})

(defn voice-update-for
  [update-id chat-id user-id]
  {:update_id update-id
   :message {:message_id update-id
             :from {:id user-id}
             :chat {:id chat-id
                    :type "private"
                    :first_name "Test"}
             :voice {:file_id "voice-1"
                     :file_size 12
                     :mime_type "audio/ogg"}}})

;; --- rich messages (Bot API 10.1) --------------------------------------------

(defn rich-test-config []
  {:bot-token "token"
   :rich-messages? true
   :allowlist {:allow-all? true}})

(defn recording-opts
  [{:keys [sent html-sent drafts rich-sent rich-drafts]}
   & {:keys [rich-draft-fn rich-send-fn]}]
  {:send-message-fn (fn [chat-id text]
                      (swap! sent conj {:chat-id chat-id :text text}))
   :send-html-message-fn (fn [chat-id text]
                           (swap! html-sent conj {:chat-id chat-id :text text}))
   :send-message-draft-fn (fn [chat-id draft-id text]
                            (swap! drafts conj {:chat-id chat-id
                                                :draft-id draft-id
                                                :text text}))
   :send-rich-message-fn (or rich-send-fn
                             (fn [chat-id markdown]
                               (swap! rich-sent conj {:chat-id chat-id
                                                      :markdown markdown})))
   :send-rich-message-draft-fn (or rich-draft-fn
                                   (fn [chat-id draft-id markdown]
                                     (swap! rich-drafts conj {:chat-id chat-id
                                                              :draft-id draft-id
                                                              :markdown markdown})))
   :send-chat-action-fn (fn [& _] nil)})
