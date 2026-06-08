(ns agent.telegram
  "Telegram long-polling adapter."
  (:require
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.chat.history :as chat-history]
   [agent.channels.core :as channels]
   [agent.defaults :as defaults]
   [agent.persistence.sqlite :as sqlite]
   [agent.telegram.api :as tg-api]
   [agent.telegram.approvals :as tg-approvals]
   [agent.telegram.commands :as tg-commands]
   [agent.telegram.media :as tg-media]
   [agent.telegram.sessions :as tg-sessions]
   [agent.telegram.streaming :as tg-streaming]
   [clojure.core.async :as async]
   [clojure.string :as str]))

(defn- id-set [ids]
  (set (map str (or ids []))))

(defn- update-message [update]
  (or (:message update)
      (get-in update [:callback_query :message])))

(defn- update-user-id [update]
  (or (some-> update :message :from :id)
      (some-> update :callback_query :from :id)))

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
  (chat/cancel-session! system session-id)
  (when-let [task (:future (get @(:active-tasks opts) chat-id))]
    (future-cancel task)
    (swap! (:active-tasks opts) dissoc chat-id))
  {:content "Stopping."})

(defn- telegram-operation-failed!
  [system chat-id operation error]
  (when-let [event-sink (:event-sink system)]
    (event-sink {:event-type :telegram.operation.failed
                 :entity-type :telegram_chat
                 :entity-id (str chat-id)
                 :payload {:operation operation
                           :chat-id chat-id
                           :message (.getMessage error)
                           :type (some-> error ex-data :type)}})))

(defn- safe-telegram!
  [system chat-id operation f]
  (try
    (f)
    (catch Exception e
      (telegram-operation-failed! system chat-id operation e)
      nil)))

(defn- session-history
  [system session-id]
  (if (and (not (str/blank? (or session-id "")))
           (sqlite/get-session (:store system) session-id))
    (chat-history/session-messages system session-id)
    []))

(defn- run-approved-continuation!
  [system config opts chat chat-id session-id tool-name input result]
  (let [token (:bot-token config)
        send! (or (:send-message-fn opts)
                  (fn [cid text] (tg-api/send-message! token cid text)))
        stop-typing! (tg-streaming/start-typing-indicator! safe-telegram! system config opts chat-id)
        stream-controls (tg-streaming/build-controls safe-telegram! system config opts chat chat-id)
        on-tool-call (tg-streaming/build-on-tool-call safe-telegram! system opts chat-id stream-controls)
        messages (conj (vec (session-history system session-id))
	                       {:role "user"
	                        :content (tg-approvals/result-context-text tool-name input result)})
        chat-run! (or (:chat-fn opts) chat/run!)]
    (try
      (let [response (chat-run! system
                                (cond-> {:session-id session-id
                                          :messages messages
                                          :context {:telegram-chat-id chat-id}
                                          :stream? true}
                                  (:on-delta stream-controls)
                                  (assoc :on-delta (:on-delta stream-controls))
                                  (:on-thinking-delta stream-controls)
                                  (assoc :on-thinking-delta (:on-thinking-delta stream-controls))
                                  on-tool-call
                                  (assoc :on-tool-call on-tool-call)))
            final (or (:content response) "")
            approvals (seq (:approvals response))]
        (when-let [finalize-thinking! (:finalize-thinking! stream-controls)]
          (finalize-thinking!))
        (if (and (= :approval-required (keyword (:stop-reason response)))
                 approvals)
	          (doseq [approval approvals]
	            (tg-approvals/send-card! safe-telegram! system config opts chat-id approval))
          (when-not (str/blank? final)
            (send! chat-id final)))
        response)
      (finally
        (stop-typing!)))))

(defn- session-event? [event session-id event-type]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= event-type (:event-type event))))

(defn- terminal-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "agent-end" (:event-type event))))

(defn- run-chat-callbacks!
  [system opts chat-id session-id user-text stream-controls on-tool-call]
  ((or (:chat-fn opts) chat/run!)
   system
   (cond-> {:session-id session-id
            :messages [{:role "user" :content user-text}]
            :context {:telegram-chat-id chat-id}}
     (:on-delta stream-controls) (assoc :on-delta (:on-delta stream-controls))
     (:on-thinking-delta stream-controls) (assoc :on-thinking-delta (:on-thinking-delta stream-controls))
     on-tool-call (assoc :on-tool-call on-tool-call))))

(defn- run-chat-events!
  [system config opts chat-id session-id user-text stream-controls on-tool-call]
  (let [broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject)
                                         {:buffer-strategy :sliding
                                          :buffer-size defaults/event-stream-buffer-size
                                          :slow-client :drop-new})
        ch (:channel subscription)
        result-ch (async/chan 1)
        saw-delta? (atom false)
        suppress-approval-final? (atom false)
        finalize! (:finalize! stream-controls)]
    (try
      (future
        (try
          (async/>!! result-ch
                     {:result (chat/run! system
                                         {:session-id session-id
                                          :messages [{:role "user" :content user-text}]
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
              (recur value terminal?)

              (= port ch)
              (let [event (:payload value)
                    payload (:payload event)]
                (when (and event
                           (session-event? event session-id "message-update")
                           (string? (:delta payload)))
                  (when-not (and @suppress-approval-final?
                                 (:synthetic? payload))
                    (reset! saw-delta? true)
                    (when-let [on-delta (:on-delta stream-controls)]
                      (on-delta (:delta payload)))))
                (when (and event
                           (session-event? event session-id "message-update")
                           (string? (:thinking-delta payload)))
                  (when-let [on-thinking-delta (:on-thinking-delta stream-controls)]
                    (on-thinking-delta (:thinking-delta payload))))
                (when (and event
                           (session-event? event session-id "message-end")
                           (:tool-turn? payload)
                           (= "assistant" (:role payload)))
                  (when (and (not @saw-delta?)
                             (not (str/blank? (:content payload))))
                    (when-let [on-delta (:on-delta stream-controls)]
                      (on-delta (:content payload)))))
                (when (and event
                           (session-event? event session-id "message-end")
                           (:final? payload))
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
	                  (reset! suppress-approval-final? true)
	                  (doseq [approval (:approvals payload)]
	                    (tg-approvals/send-card! safe-telegram! system config opts chat-id approval)))
                (when (and event
                           (session-event? event session-id "tool-execution-end"))
                  (when on-tool-call
                    (on-tool-call {:receipt (:receipt payload)
                                   :tool-call (:tool-call payload)}))
                  (reset! saw-delta? false))
                (recur result-value (or terminal?
                                        (and event
                                             (terminal-session-event? event session-id)))))))))
      (finally
        (broker/unsubscribe! broker-instance subscription)))))

(defn- run-chat!
  [system config opts chat chat-id session-id user-text]
  (let [token (:bot-token config)
        send! (or (:send-message-fn opts)
                  (fn [cid text] (tg-api/send-message! token cid text)))
        stop-typing! (tg-streaming/start-typing-indicator! safe-telegram! system config opts chat-id)
        stream-controls (tg-streaming/build-controls safe-telegram! system config opts chat chat-id)
        on-tool-call (tg-streaming/build-on-tool-call safe-telegram! system opts chat-id stream-controls)
        callback-path? (or (:chat-fn opts)
                           (nil? (or (:event-bus system) (:broker system))))
        result (try
                 (if callback-path?
                   (run-chat-callbacks! system opts chat-id session-id user-text stream-controls on-tool-call)
                   (run-chat-events! system config opts chat-id session-id user-text stream-controls on-tool-call))
                 (finally
                   (stop-typing!)))
        final (or (:content result) "")]
    (when (or callback-path?
              (nil? (:finalize! stream-controls)))
      (when callback-path?
        (when-let [finalize-thinking! (:finalize-thinking! stream-controls)]
          (finalize-thinking!)))
      (send! chat-id (if (str/blank? final) "(no response)" final)))
    final))

(defn- run-chat-async!
  [system config opts chat chat-id session-id user-text]
  (let [active-tasks (:active-tasks opts)
        task-id (str (java.util.UUID/randomUUID))
        task (future
               (try
                 (run-chat! system config opts chat chat-id session-id user-text)
                 (finally
                   (swap! active-tasks
                          (fn [tasks]
                            (if (= task-id (get-in tasks [chat-id :id]))
                              (dissoc tasks chat-id)
                              tasks))))))]
    (swap! active-tasks assoc chat-id {:id task-id :future task})
    task))

(defn process-update!
  [system config {:keys [send-message-fn] :as opts} update]
  (let [opts (cond-> opts
               (nil? (:active-tasks opts)) (assoc :active-tasks (atom {})))
        callback-query (:callback_query update)
        message (:message update)
        chat (:chat message)
        chat-id (:id chat)
        text (:text message)]
    (cond
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
        (let [send! (or send-message-fn #(tg-api/send-message! (:bot-token config) %1 %2))]
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
          (cond
            (and (not (str/blank? text))
                 (= "/stop" (-> text str/trim str/lower-case (str/split #"\s+") first)))
            (let [mapping (tg-sessions/ensure-session! (:store system) chat)
                  result (stop-chat! system opts chat-id (:session-id mapping))]
              (send! chat-id (:content result))
              :processed)

            (and (not (str/blank? text))
                 (tg-commands/handle-media! config opts chat-id text))
            :processed

            :else
            (let [builtin-reply (when-not (str/blank? text)
                                  (tg-commands/response system chat text))]
              (if builtin-reply
                (do (send! chat-id builtin-reply) :processed)
                (let [mapping (tg-sessions/ensure-session! (:store system) chat)
                      content (try
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
      (tg-api/send-message! (:bot-token config) (:recipient message*) (:content message*))))
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

(defn enabled? [service]
  (and (true? (get-in service [:config :enabled]))
       (not (str/blank? (get-in service [:config :bot-token])))))

(defn- process-polled-update!
  [system config opts last-offset update]
  (let [update-id (long (:update_id update))
        next-offset (inc update-id)
        store (:store system)]
    (sqlite/upsert-channel-inbox-update! store :telegram update-id update)
    (try
      (process-update! system config opts update)
      (sqlite/mark-channel-inbox-update! store :telegram update-id :processed nil)
      (sqlite/save-channel-offset! store :telegram next-offset)
      (reset! last-offset next-offset)
      (catch Exception e
        (sqlite/mark-channel-inbox-update! store :telegram update-id :failed (.getMessage e))
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
                        ((:event-sink system) {:event-type :telegram.error
                                               :entity-type :telegram
                                               :payload {:message (.getMessage e)
                                                         :type (some-> e ex-data :type)}})
                        (Thread/sleep 1000)))))))))
  service)

(defn stop!
  ([service] (stop! service 5000))
  ([service timeout-ms]
   (when service
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
