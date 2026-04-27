(ns agent.telegram
  "Telegram long-polling adapter."
  (:require
   [agent.chat :as chat]
   [agent.channels.core :as channels]
   [agent.persistence.sqlite :as sqlite]
   [agent.telegram.format :as fmt]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]))

(def ^:private telegram-api "https://api.telegram.org")
(def ^:private max-message-chars 4096)
;; Markdown source cap kept below the 4096 hard limit so MarkdownV2 escaping
;; doesn't push a chunk over the wire-level cap.
(def ^:private max-source-chars 3400)
;; Telegram per-chat send rate is ~1/sec; staying above this floor keeps
;; draft updates in order and avoids 429s on slow connections.
(def ^:private stream-flush-ms 1200)

(defn- parse-body [body]
  (cond
    (map? body) body
    (string? body) (json/parse-string body true)
    :else body))

(defn- api-url [token method]
  (str telegram-api "/bot" token "/" method))

(defn api-request!
  [token method body]
  (let [response (http/post (api-url token method)
                            {:body (json/generate-string body)
                             :content-type :json
                             :accept :json
                             :as :json
                             :throw-exceptions false
                             :socket-timeout 70000
                             :conn-timeout 10000})
        payload (parse-body (:body response))]
    (if (and (<= 200 (:status response) 299) (true? (:ok payload)))
      (:result payload)
      (throw (ex-info "Telegram API request failed"
                      {:type :telegram-api-error
                       :method method
                       :status (:status response)
                       :body payload})))))

(defn get-updates!
  [token {:keys [offset timeout limit]
          :or {timeout 30 limit 100}}]
  (api-request! token "getUpdates"
                (cond-> {:timeout timeout
                         :limit limit
                         :allowed_updates ["message"]}
                  offset (assoc :offset offset))))

(defn- text-payload
  "Builds the {:text :parse_mode} portion of a Telegram payload.
   Renders markdown→MarkdownV2 when possible; falls back to truncated raw text
   without parse_mode if conversion fails or the rendered text is too long."
  [text]
  (let [s (str text)
        md (fmt/safe-md->markdown-v2 s)]
    (if (and md (<= (count md) max-message-chars))
      {:text md :parse_mode "MarkdownV2"}
      {:text (if (> (count s) max-message-chars)
               (subs s 0 max-message-chars)
               s)})))

(defn send-message!
  [token chat-id text]
  (mapv (fn [chunk]
          (api-request! token "sendMessage"
                        (assoc (text-payload chunk) :chat_id chat-id)))
        (fmt/chunk-markdown (str text) max-source-chars)))

(defn send-message-draft!
  "Streams a partial message via Telegram Bot API 9.5 sendMessageDraft.
   `draft-id` is a non-zero int; same id animates updates. Private chats only.
   Returns true on success. Source is clamped to leave headroom for MarkdownV2
   expansion within Telegram's 4096-char limit."
  [token chat-id draft-id text]
  (let [s (str text)
        clamped (if (> (count s) max-source-chars)
                  (subs s 0 max-source-chars)
                  s)
        payload (text-payload clamped)]
    (api-request! token "sendMessageDraft"
                  (merge {:chat_id chat-id :draft_id draft-id} payload))))

(defn- attachment-payload
  "Builds a sendPhoto/sendDocument JSON payload from a URL or file_id string."
  [chat-id media-key media caption]
  (let [base {:chat_id chat-id media-key media}]
    (if (str/blank? caption)
      base
      (let [{:keys [text parse_mode]} (text-payload caption)]
        (cond-> (assoc base :caption text)
          parse_mode (assoc :parse_mode parse_mode))))))

(defn send-photo!
  "Sends a photo by URL or file_id. Caption is optional."
  ([token chat-id photo] (send-photo! token chat-id photo nil))
  ([token chat-id photo caption]
   (api-request! token "sendPhoto" (attachment-payload chat-id :photo photo caption))))

(defn send-document!
  "Sends a document by URL or file_id. Caption is optional."
  ([token chat-id document] (send-document! token chat-id document nil))
  ([token chat-id document caption]
   (api-request! token "sendDocument" (attachment-payload chat-id :document document caption))))

(defn- id-set [ids]
  (set (map str (or ids []))))

(defn allowed?
  [config update]
  (let [allowlist (:allowlist config)
        allow-all? (true? (:allow-all? allowlist))
        user-ids (id-set (:user-ids allowlist))
        chat-ids (id-set (:chat-ids allowlist))
        message (:message update)
        user-id (some-> message :from :id str)
        chat-id (some-> message :chat :id str)]
    (or allow-all?
        (contains? user-ids user-id)
        (contains? chat-ids chat-id))))

(defn- chat-title [chat]
  (or (:title chat)
      (:username chat)
      (not-empty (str/trim (str (or (:first_name chat) "")
                            " "
                            (or (:last_name chat) ""))))
      (str (:id chat))))

(defn- session-title [chat]
  (str "Telegram: " (chat-title chat)))

(defn- session-mapping! [store chat]
  (sqlite/ensure-channel-session!
   store
   {:source :telegram
    :external-chat-id (:id chat)
    :title (session-title chat)
    :metadata {:chat chat}}))

(defn- reset-session! [store chat]
  (sqlite/reset-channel-session!
   store
   {:source :telegram
    :external-chat-id (:id chat)
    :title (session-title chat)
    :metadata {:chat chat}}))

(defn- memory-status [system session-id]
  (let [facts (sqlite/count-memory-facts (:store system))
        messages (count (sqlite/list-messages (:store system) session-id))]
    (str "Memory: " facts " facts, " messages " session messages.")))

(defn- status-text [system session-id]
  (str "OK. Session: " session-id
       ". Tools: " (count (:tools (tools/registry-health (:tool-registry system))))))

(defn- parse-command-args [text]
  (let [parts (str/split text #"\s+" 2)]
    {:command (str/lower-case (first parts))
     :rest (or (second parts) "")}))

(defn- split-caption [s]
  (let [parts (str/split s #"\s+" 2)
        url (first parts)
        caption (some-> (second parts) str/trim)]
    [url (when-not (str/blank? caption) caption)]))

(defn command-response
  [system chat command]
  (when (str/starts-with? command "/")
    (let [mapping (session-mapping! (:store system) chat)
          session-id (:session-id mapping)
          command* (-> command str/lower-case (str/split #"\s+") first)]
      (case command*
        "/start" "Ready. Send message to chat."
        "/help" "/start /help /reset /memory /status /photo <url> [caption] /file <url> [caption]"
        "/reset" (do
                   (reset-session! (:store system) chat)
                   "Session reset.")
        "/memory" (memory-status system session-id)
        "/status" (status-text system session-id)
        nil))))

(defn- handle-media-command!
  "Handles /photo and /file slash commands. Returns true if handled, nil otherwise."
  [{:keys [bot-token]} {:keys [send-photo-fn send-document-fn send-message-fn]} chat-id text]
  (let [{:keys [command rest]} (parse-command-args text)
        send-message! (or send-message-fn #(send-message! bot-token %1 %2))]
    (case command
      "/photo"
      (let [[url caption] (split-caption rest)]
        (if (str/blank? url)
          (send-message! chat-id "Usage: /photo <url> [caption]")
          (try
            ((or send-photo-fn #(send-photo! bot-token %1 %2 %3)) chat-id url caption)
            (catch Exception e
              (send-message! chat-id (str "Photo send failed: " (.getMessage e))))))
        true)

      "/file"
      (let [[url caption] (split-caption rest)]
        (if (str/blank? url)
          (send-message! chat-id "Usage: /file <url> [caption]")
          (try
            ((or send-document-fn #(send-document! bot-token %1 %2 %3)) chat-id url caption)
            (catch Exception e
              (send-message! chat-id (str "Document send failed: " (.getMessage e))))))
        true)

      nil)))

(defn- private-chat? [chat]
  (= "private" (:type chat)))

(defn- build-stream-on-delta
  "Returns an on-delta callback that animates a partial reply via sendMessageDraft.
   Returns nil when the chat doesn't support live updates (groups, channels)."
  [config opts chat chat-id]
  (when (private-chat? chat)
    (let [token (:bot-token config)
          draft-id (inc (mod (System/currentTimeMillis) 2147483647))
          accumulator (atom "")
          last-flush (atom 0)
          send-draft! (or (:send-message-draft-fn opts)
                          (fn [cid did text] (send-message-draft! token cid did text)))
          flush! (fn []
                   (let [now (System/currentTimeMillis)
                         text @accumulator]
                     (when (and (not (str/blank? text))
                                (>= (- now @last-flush) stream-flush-ms))
                       (reset! last-flush now)
                       (try
                         (send-draft! chat-id draft-id text)
                         (catch Exception _ nil)))))]
      (fn [delta]
        (swap! accumulator str delta)
        (flush!)))))

(defn- run-chat!
  [system config opts chat chat-id session-id user-text]
  (let [token (:bot-token config)
        send! (or (:send-message-fn opts)
                  (fn [cid text] (send-message! token cid text)))
        on-delta (build-stream-on-delta config opts chat chat-id)
        result ((or (:chat-fn opts) chat/run!)
                system
                (cond-> {:session-id session-id
                         :messages [{:role "user" :content user-text}]
                         :context {:telegram-chat-id chat-id}}
                  on-delta (assoc :on-delta on-delta)))
        final (or (:content result) "")]
    (send! chat-id (if (str/blank? final) "(no response)" final))
    final))

(defn process-update!
  [system config {:keys [send-message-fn] :as opts} update]
  (let [message (:message update)
        chat (:chat message)
        chat-id (:id chat)
        text (:text message)]
    (when (and chat-id (not (str/blank? text)))
      (if-not (allowed? config update)
        (do
          ((:event-sink system) {:event-type :telegram.blocked
                                 :entity-type :telegram_chat
                                 :entity-id (str chat-id)
                                 :payload {:chat-id chat-id
                                           :user-id (get-in message [:from :id])}})
          :blocked)
        (let [send! (or send-message-fn #(send-message! (:bot-token config) %1 %2))]
          (cond
            (handle-media-command! config opts chat-id text)
            :processed

            :else
            (let [builtin-reply (command-response system chat text)]
              (if builtin-reply
                (do (send! chat-id builtin-reply) :processed)
                (let [mapping (session-mapping! (:store system) chat)]
                  (run-chat! system config opts chat chat-id
                             (:session-id mapping) text)
                  :processed)))))))))

(declare start! stop! health-check)

(defn- description []
  (channels/create-adapter-description
   :telegram
   "Telegram"
   :polling
   #{:supports-outbound :supports-streaming :supports-voice-ingest :supports-reactions :supports-location :supports-otp}
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
    (send-message! (:bot-token config) destination message)))

(defn create-service
  ([system] (create-service system {}))
  ([system opts]
   (->TelegramService system
                      (get-in system [:config :channel-adapters :telegram])
                      (atom false)
                      (atom nil)
                      (atom nil)
                      opts)))

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
                             #(get-updates! (:bot-token config) %))
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
