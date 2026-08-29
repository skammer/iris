(ns agent.telegram
  "Telegram channel adapter. Polls Telegram updates, maps chats to Iris sessions,
   streams chat turns back to Telegram, handles approvals/media/commands, and
   exposes adapter lifecycle and health."
  (:require
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.chat.history :as chat-history]
   [agent.channels.core :as channels]
   [agent.defaults :as defaults]
   [agent.llm.messages :as llm-messages]
   [agent.persistence.sqlite :as sqlite]
   [agent.telegram.api :as tg-api]
   [agent.telegram.approvals :as tg-approvals]
   [agent.telegram.commands :as tg-commands]
   [agent.telegram.media :as tg-media]
   [agent.telegram.rich :as tg-rich]
   [agent.telegram.sessions :as tg-sessions]
   [agent.telegram.streaming :as tg-streaming]
   [clojure.core.async :as async]
   [clojure.string :as str]))

(defn- id-set [ids]
  (set (map str (or ids []))))

(defn- update-message [update]
  (or (:message update)
      (get-in update [:callback_query :message])
      (when-let [chat (get-in update [:stopped_message_generation :chat])]
        {:chat chat})))

(defn- update-user-id [update]
  (or (some-> update :message :from :id)
      (some-> update :callback_query :from :id)
      (some-> update :stopped_message_generation :chat :id)))

(defn allowed?
  [config update]
  (let [allowlist (:allowlist config)
        allow-all? (true? (:allow-all? allowlist))
        user-ids (id-set (:user-ids allowlist))
        chat-ids (id-set (:chat-ids allowlist))
        message (update-message update)
        user-id (some-> (update-user-id update) str)
        chat-id (some-> message :chat :id str)]
    (or allow-all?
        (contains? user-ids user-id)
        (contains? chat-ids chat-id))))

(defn- stop-chat!
  [system opts chat-id session-id]
  (when-let [finalize! (:finalize! (get @(:active-tasks opts) chat-id))]
    (finalize!))
  (chat/cancel-session! system session-id)
  (when-let [task (:future (get @(:active-tasks opts) chat-id))]
    (future-cancel task))
  (swap! (:active-tasks opts) dissoc chat-id)
  {:content "Stopping."})

(defn- deref-if-needed [value]
  (if (instance? clojure.lang.IDeref value) @value value))

(defn- active-generation
  [opts chat-id draft-id]
  (let [active (get @(:active-tasks opts) chat-id)]
    (when (and active
               (= draft-id (deref-if-needed (:draft-id active))))
      active)))

(defn- telegram-operation-failed!
  [system chat-id operation error]
  (when-let [event-sink (:event-sink system)]
    (let [data (ex-data error)]
      (event-sink {:event-type :telegram.operation.failed
                   :entity-type :telegram_chat
                   :entity-id (str chat-id)
                   :payload (cond-> {:operation operation
                                     :chat-id chat-id
                                     :message (.getMessage error)
                                     :type (:type data)}
                              (:status data)
                              (assoc :status (:status data))
                              (get-in data [:body :description])
                              (assoc :description (get-in data [:body :description])))}))))

(defn- safe-telegram!
  [system chat-id operation f]
  (try
    (f)
    (catch Exception e
      (telegram-operation-failed! system chat-id operation e)
      nil)))

(def ^:private agent-started-content "Agent started.")
(def ^:private agent-stopped-content "Agent stopped.")

(defn- send-lifecycle-message!
  [system config opts chat-id operation content]
  (let [send! (or (:send-message-fn opts)
                  (fn [cid text] (tg-api/send-message! (:bot-token config) cid text)))
        send-with-markup! (or (:send-message-with-reply-markup-fn opts)
                              (when-not (:send-message-fn opts)
                                (fn [cid text reply-markup]
                                  (tg-api/send-message-with-reply-markup!
                                   (:bot-token config) cid text reply-markup)))
                              (fn [cid text _] (send! cid text)))]
    (safe-telegram! system chat-id operation
                    #(if (= :agent-start-notification operation)
                       (send-with-markup! chat-id content {:remove_keyboard true})
                       (send! chat-id content)))))

(defn- cancel-pending-reply-keyboard!
  [system chat-id]
  (when-let [pending* (:telegram-reply-keyboards system)]
    (when-let [pending (get @pending* chat-id)]
      (some-> (:future pending) future-cancel)
      (swap! pending* dissoc chat-id))))

(defn- cancel-all-pending-reply-keyboards!
  [system]
  (when-let [pending* (:telegram-reply-keyboards system)]
    (doseq [{:keys [future]} (vals @pending*)]
      (some-> future future-cancel))
    (reset! pending* {})))

(defn- parse-chat-id [value]
  (let [value* (str value)]
    (if (re-matches #"-?\d+" value*)
      (Long/parseLong value*)
      value*)))

(defn- lifecycle-chat-ids [system]
  (when-let [store (:store system)]
    (->> (sqlite/list-channel-session-mappings store :telegram)
         (keep #(some-> (:external-chat-id %) parse-chat-id))
         distinct
         vec)))

(defn- notify-lifecycle!
  [system config opts operation content]
  (doseq [chat-id (lifecycle-chat-ids system)]
    (send-lifecycle-message! system config opts chat-id operation content)))

(defn- session-history
  [system session-id]
  (if (and (not (str/blank? (or session-id "")))
           (sqlite/get-session (:store system) session-id))
    (chat-history/session-messages system session-id)
    []))

(defn- session-event? [event session-id event-type]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= event-type (:event-type event))))

(defn- terminal-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "agent-end" (:event-type event))))

(defn- run-chat-events!
  [system config opts chat-id session-id messages stream-controls tool-call-controls
   reply-keyboard-active?]
  (let [broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject)
                                         {:buffer-strategy :sliding
                                          :buffer-size defaults/event-stream-buffer-size
                                          :slow-client :drop-new})
        ch (:channel subscription)
        result-ch (async/chan 1)
        saw-delta? (atom false)
        suppress-approval-final? (atom false)
        finalize! (:finalize! stream-controls)
        flush-tool-calls! (:flush! tool-call-controls)]
    (try
      (future
        (try
          (async/>!! result-ch
                     {:result (chat/run! system
                                         {:session-id session-id
                                          :messages messages
                                          :context {:telegram-chat-id chat-id}
                                          :stream? true})})
          (catch Throwable t
            (async/>!! result-ch {:error t}))))
      (loop [result-value nil
             terminal? false]
        (if (and result-value terminal?)
          (if-let [error (:error result-value)]
            (throw error)
            (:result result-value))
          (let [[value port] (async/alts!! [result-ch ch])]
            (cond
              (= port result-ch)
              ;; An error from chat/run! means the runtime loop may never have
              ;; started, so no terminal agent-end event is coming — waiting
              ;; for one would spin this loop (and the typing indicator) forever.
              (recur value (or terminal? (some? (:error value))))

              (= port ch)
              (let [event (:payload value)
                    payload (:payload event)]
                (when (and event
                           (session-event? event session-id "message-update")
                           (string? (:delta payload)))
                  (when flush-tool-calls! (flush-tool-calls!))
                  (when-not (and @suppress-approval-final?
                                 (:synthetic? payload))
                    (reset! saw-delta? true)
                    (when-let [on-delta (:on-delta stream-controls)]
                      (on-delta (:delta payload)))))
                (when (and event
                           (session-event? event session-id "message-update")
                           (string? (:thinking-delta payload)))
                  (when flush-tool-calls! (flush-tool-calls!))
                  (when-let [on-thinking-delta (:on-thinking-delta stream-controls)]
                    (on-thinking-delta (:thinking-delta payload))))
                (when (and event
                           (session-event? event session-id "message-end")
                           (:tool-turn? payload)
                           (= "assistant" (:role payload)))
                  (when (and (not @saw-delta?)
                             (not (str/blank? (:content payload))))
                    (when flush-tool-calls! (flush-tool-calls!))
                    (when-let [on-delta (:on-delta stream-controls)]
                      (on-delta (:content payload))))
                  (reset! saw-delta? false))
                (when (and event
                           (session-event? event session-id "message-end")
                           (:final? payload))
                  (when flush-tool-calls! (flush-tool-calls!))
                  (if (and @suppress-approval-final?
                           (= :approval-required (keyword (:stop-reason payload))))
                    (reset! suppress-approval-final? false)
                    (do
                      (when (and (not @saw-delta?)
                                 (not (str/blank? (:content payload))))
                        (when-let [on-delta (:on-delta stream-controls)]
                          (on-delta (:content payload))))
                      (when finalize! (finalize!))))
                  (reset! saw-delta? false))
                (when (and event
                           (session-event? event session-id "tool-execution-update")
                           (= :approval-required (keyword (:kind payload))))
                  (when flush-tool-calls! (flush-tool-calls!))
                  (reset! suppress-approval-final? true)
                  (doseq [approval (:approvals payload)]
                    (tg-approvals/send-card! safe-telegram! system config opts chat-id approval)))
                (when (and event
                           (session-event? event session-id "tool-execution-start"))
                  (when-let [on-start! (:on-start! tool-call-controls)]
                    (on-start! payload)))
                (when (and event
                           (session-event? event session-id "tool-execution-end"))
                  (when (and (= "telegram_ask"
                                (some-> (or (:tool-name payload)
                                            (get-in payload [:receipt :tool-name]))
                                        name))
                             (= :ok (some-> (or (:status payload)
                                                (get-in payload [:receipt :status]))
                                            keyword)))
                    (reset! reply-keyboard-active? true))
                  (when-let [on-end! (:on-end! tool-call-controls)]
                    (on-end! {:receipt (:receipt payload)
                              :tool-call (:tool-call payload)})))
                (when (and event
                           (terminal-session-event? event session-id)
                           flush-tool-calls!)
                  (flush-tool-calls!))
                (recur result-value (or terminal?
                                        (and event
                                             (terminal-session-event? event session-id)))))))))
      (finally
        (broker/unsubscribe! broker-instance subscription)))))

(defn- rich-send-fn
  "Final-reply sender that tries sendRichMessage and falls back to the
   legacy chunked MarkdownV2 path, recording the rich failure."
  [system config opts]
  (let [token (:bot-token config)
        send-rich! (or (:send-rich-message-fn opts)
                       (fn [cid markdown] (tg-api/send-rich-message! token cid markdown)))
        legacy! (or (:send-message-fn opts)
                    (fn [cid text] (tg-api/send-message! token cid text)))]
    (fn [chat-id text]
      (try
        (doseq [chunk (tg-rich/final-chunks nil text)]
          (send-rich! chat-id chunk))
        (catch Exception e
          (telegram-operation-failed! system chat-id :rich-send e)
          (legacy! chat-id text))))))

(defn- activity-user-request [messages]
  (or (->> messages
           reverse
           (filter chat-history/user-message?)
           (map llm-messages/content-text)
           (remove #(str/starts-with? % "Approved tool result."))
           first)
      (chat-history/latest-user-prompt messages)))

(defn- run-turn!
  "Shared chat-turn runner: typing indicator, draft streaming controls,
   tool-call summaries, approval cards, and final reply delivery."
  [system config opts chat chat-id session-id messages]
  (let [token (:bot-token config)
        reply-keyboard-active? (atom false)
        opts (assoc opts
                    :clear-reply-keyboard? true
                    :reply-keyboard-active? reply-keyboard-active?)
        send! (if (tg-rich/enabled? config)
                (rich-send-fn system config opts)
                (or (:send-message-fn opts)
                    (fn [cid text] (tg-api/send-message! token cid text))))
        stop-typing! (tg-streaming/start-typing-indicator! safe-telegram! system config opts chat-id)
        stream-controls (tg-streaming/build-controls safe-telegram! system config opts chat chat-id)
        _ (when (and stream-controls (:active-tasks opts))
            (swap! (:active-tasks opts)
                   (fn [tasks]
                     (if (contains? tasks chat-id)
                       (update tasks chat-id assoc
                               :draft-id (:draft-id stream-controls)
                               :finalize! (:finalize! stream-controls))
                       tasks))))
        tool-call-controls (tg-streaming/build-tool-call-controls
                            safe-telegram!
                            system
                            config
                            (assoc opts
                                   :session-id session-id
                                   :user-request (activity-user-request messages))
                            chat-id
                            stream-controls)
        result (try
                 (run-chat-events! system config opts chat-id session-id messages
                                   stream-controls tool-call-controls reply-keyboard-active?)
                 (finally
                   (stop-typing!)
                   (when-let [stop-tool-calls! (:stop! tool-call-controls)]
                     (stop-tool-calls!))
                   (when-let [stop-controls! (:stop! stream-controls)]
                     (stop-controls!))))
        final (or (:content result) "")]
    (when (nil? (:finalize! stream-controls))
      (send! chat-id (if (str/blank? final) "(no response)" final)))
    final))

(defn- run-chat!
  [system config opts chat chat-id session-id user-content]
  (run-turn! system config opts chat chat-id session-id
             [{:role "user" :content user-content}]))

(defn- run-approved-continuation!
  [system config opts chat chat-id session-id tool-name input result]
  (run-turn! system config opts chat chat-id session-id
             (conj (vec (session-history system session-id))
                   {:role "user"
                    :content (tg-approvals/result-context-text tool-name input result)})))

(defn- run-chat-async!
  [system config opts chat chat-id session-id user-text]
  (let [active-tasks (:active-tasks opts)
        task-id (str (java.util.UUID/randomUUID))
        registered (promise)
        task (future
               @registered
               (try
                 (run-chat! system config opts chat chat-id session-id user-text)
                 (finally
                   (swap! active-tasks
                          (fn [tasks]
                            (if (= task-id (get-in tasks [chat-id :id]))
                              (dissoc tasks chat-id)
                              tasks))))))]
    (swap! active-tasks assoc chat-id {:id task-id
                                       :future task
                                       :session-id session-id})
    (deliver registered true)
    task))

(defn process-update!
  [system config {:keys [send-message-fn] :as opts} update]
  (let [opts (cond-> opts
               (nil? (:active-tasks opts)) (assoc :active-tasks (atom {})))
        callback-query (:callback_query update)
        stopped-generation (:stopped_message_generation update)
        message (:message update)
        chat (:chat message)
        chat-id (:id chat)
        text (:text message)]
    (cond
	      stopped-generation
	      (if-not (allowed? config update)
	        :blocked
	        (let [chat-id (get-in stopped-generation [:chat :id])
	              draft-id (:draft_id stopped-generation)]
	          (if-let [active (active-generation opts chat-id draft-id)]
	            (do
	              (stop-chat! system opts chat-id (:session-id active))
	              (when-let [event-sink (:event-sink system)]
	                (event-sink {:event-type :telegram.generation-stopped
	                             :entity-type :telegram_chat
	                             :entity-id (str chat-id)
	                             :payload {:chat-id chat-id
	                                       :draft-id draft-id}}))
	              :processed)
	            :ignored)))

	      callback-query
	      (if-not (allowed? config update)
	        (do
	          (tg-approvals/answer-callback! safe-telegram! system config opts callback-query "Not allowed." {:alert? true})
	          :blocked)
	        (if-let [callback (tg-approvals/parse-callback (:data callback-query))]
	          (try
	            (tg-approvals/process-callback!
	             safe-telegram!
	             (fn [chat chat-id session-id tool-name input result]
	               (run-approved-continuation! system config opts chat chat-id session-id tool-name input result))
	             system
	             config
	             opts
	             callback-query
	             callback)
	            (catch Exception e
	              (tg-approvals/answer-callback! safe-telegram! system config opts callback-query (.getMessage e) {:alert? true})
	              (throw e)))
	          (do
	            (tg-approvals/answer-callback! safe-telegram! system config opts callback-query "Unknown action." {:alert? true})
	            :ignored)))

      (and chat-id (tg-media/processable-message? message))
      (if-not (allowed? config update)
        (do
          ((:event-sink system) {:event-type :telegram.blocked
                                 :entity-type :telegram_chat
                                 :entity-id (str chat-id)
                                 :payload {:chat-id chat-id
                                           :user-id (get-in message [:from :id])}})
          :blocked)
        (let [send-plain! (or send-message-fn #(tg-api/send-message! (:bot-token config) %1 %2))
              send-with-markup! (or (:send-message-with-reply-markup-fn opts)
                                    (when-not send-message-fn
                                      (fn [cid content reply-markup]
                                        (tg-api/send-message-with-reply-markup!
                                         (:bot-token config) cid content reply-markup)))
                                    (fn [cid content _] (send-plain! cid content)))
              send! (fn [cid content]
                      (send-with-markup! cid content {:remove_keyboard true}))
              reset-policy (cond-> (:session-reset config)
                             (get @(:active-tasks opts) chat-id) (assoc :mode :none))
              mapping (tg-sessions/ensure-session! (:store system) chat reset-policy)]
          (cancel-pending-reply-keyboard! system chat-id)
          ((:event-sink system) {:event-type :channel.message.received
                                 :entity-type :channel
                                 :entity-id "telegram"
                                 :payload {:channel :telegram
                                           :direction :inbound
                                           :chat-id (str chat-id)
                                           :message-id (some-> message :message_id str)
                                           :sender-id (some-> message :from :id str)
                                           :media-count (tg-media/count-media message)
                                           :thread-scope (str chat-id)}})
          (when-let [reason (:reset-reason mapping)]
            (when-let [event-sink (:event-sink system)]
              (event-sink {:event-type :telegram.session.auto-reset
                           :entity-type :telegram_chat
                           :entity-id (str chat-id)
                           :payload {:chat-id chat-id
                                     :reason reason
                                     :previous-session-id (:previous-session-id mapping)
                                     :session-id (:session-id mapping)}}))
            (when (not= false (:notify? reset-policy))
              (send! chat-id (case reason
                               :idle "Session reset after inactivity."
                               :daily "Session reset at the daily boundary."
                               "Session reset."))))
          (cond
            (and (not (str/blank? text))
                 (= "/stop" (-> text str/trim str/lower-case (str/split #"\s+") first)))
            (let [result (stop-chat! system opts chat-id (:session-id mapping))]
              (send! chat-id (:content result))
              :processed)

            (and (not (str/blank? text))
                 (tg-commands/handle-media! config opts chat-id text))
            :processed

            :else
            (let [builtin-reply (when-not (str/blank? text)
                                  (tg-commands/response system chat text mapping))]
              (if builtin-reply
                (do (send! chat-id builtin-reply) :processed)
                (let [content (try
                                (tg-media/user-content! config opts message)
                                (catch Exception e
                                  (send! chat-id (str "Media processing failed: " (.getMessage e)))
                                  ::media-processing-failed))]
                  (when-not (= ::media-processing-failed content)
                    (when-let [invoked (seq (tg-commands/invoked-skill-names system (or text "")))]
                      (send! chat-id (str "Skills: "
                                          (str/join ", " (map #(str "/" %) invoked)))))
                    (if (:async-chat? opts)
                      (run-chat-async! system config opts chat chat-id
                                       (:session-id mapping) content)
                      (run-chat! system config opts chat chat-id
                                 (:session-id mapping) content)))
                  :processed))))))

      :else nil)))

(declare start! stop! health-check)

(defn- description []
  (channels/create-adapter-description
   :telegram
   "Telegram"
   :polling
   #{:supports-outbound :supports-typing}
   :public-url-required? false
   :config-schema {:enabled :boolean
                   :bot-token :string
                   :rich-messages? :boolean
                   :allowlist :map}))

(defrecord TelegramService [system config running? future last-offset opts]
  channels/IChannelAdapter
  (describe-adapter [_] (description))
  (adapter-health-check [this] (health-check this))
  (start-adapter! [this] (start! this))
  (stop-adapter! [this] (stop! this))
  (send-adapter-message! [_ destination message]
    (let [message* (channels/normalize-send-message destination message)]
      (when (seq (:attachments message*))
        (channels/unsupported-operation! :send-attachments {:adapter :telegram}))
      (if (not= false (:rich-messages? config))
        ((rich-send-fn system config opts) (:recipient message*) (:content message*))
        (tg-api/send-message! (:bot-token config) (:recipient message*) (:content message*)))))
  channels/IChannelTyping
  (send-adapter-typing! [_ recipient _metadata]
    (tg-api/send-chat-action! (:bot-token config) recipient "typing")))

(defn create-service
  ([system] (create-service system {}))
   ([system opts]
   (->TelegramService system
                      (get-in system [:config :channel-adapters :telegram])
                      (atom false)
                      (atom nil)
                      (atom nil)
                      (merge {:active-tasks (atom {})
                              :async-chat? true}
                             opts))))

(defn- current-system [system]
  (if-let [system-ref (:system-ref system)]
    (or @system-ref system)
    system))

(defn enabled? [service]
  (and (true? (get-in service [:config :enabled]))
       (not (str/blank? (get-in service [:config :bot-token])))))

(defn- process-polled-update!
  [system config opts last-offset update]
  (let [update-id (long (:update_id update))
        next-offset (inc update-id)
        system* (current-system system)
        store (:store system*)]
    (sqlite/upsert-channel-inbox-update! store :telegram update-id update)
    (try
      (process-update! system* config opts update)
      (sqlite/mark-channel-inbox-update! store :telegram update-id :processed nil)
      (sqlite/save-channel-offset! store :telegram next-offset)
      (reset! last-offset next-offset)
      (catch Exception e
        ;; Advance past the poisoned update: the row is preserved as :failed in
        ;; channel_inbox for replay/inspection, and the poller must not refetch
        ;; it forever (head-of-line blocking for the whole channel).
        (sqlite/mark-channel-inbox-update! store :telegram update-id :failed (.getMessage e))
        (sqlite/save-channel-offset! store :telegram next-offset)
        (reset! last-offset next-offset)
        (throw e)))))

(defn start!
  [service]
  (when (enabled? service)
    (let [{:keys [system config running? last-offset opts]} service
          worker (:future service)
          get-updates-fn (or (:get-updates-fn opts)
                             #(tg-api/get-updates! (:bot-token config) %))
          poll-timeout (or (:poll-timeout-seconds config) 30)
          poll-limit (or (:poll-limit config) 100)
          initial-offset (or (:next_offset (sqlite/get-channel-offset (:store system) :telegram))
                             @last-offset)]
      (when-not @running?
        (reset! last-offset initial-offset)
        (reset! running? true)
        (reset! worker
                (future
                  (while @running?
                    (try
                      (let [updates (get-updates-fn {:offset @last-offset
                                                     :timeout poll-timeout
                                                     :limit poll-limit})]
                        (doseq [update updates]
                          (process-polled-update! system config opts last-offset update)))
                      (catch Exception e
                        ((:event-sink (current-system system))
                         {:event-type :telegram.error
                          :entity-type :telegram
                          :payload {:message (.getMessage e)
                                    :type (some-> e ex-data :type)}})
                        (Thread/sleep 1000))))))
        (notify-lifecycle! system config opts
                           :agent-start-notification
                           agent-started-content))))
  service)

(defn stop!
  ([service] (stop! service 5000))
  ([service timeout-ms]
   (when service
     (let [was-running? @(:running? service)]
       (when was-running?
         (cancel-all-pending-reply-keyboards! (current-system (:system service)))
         (notify-lifecycle! (:system service) (:config service) (:opts service)
                            :agent-stop-notification
                            agent-stopped-content)))
     (reset! (:running? service) false)
     (when-let [f @(:future service)]
       (deref f timeout-ms ::timeout)
       (future-cancel f))
     (reset! (:future service) nil))
   service))

(defn health-check [service]
  {:healthy true
   :enabled (boolean (enabled? service))
   :running (boolean (some-> service :running? deref))
   :last-offset (some-> service :last-offset deref)})
