(ns agent.telegram
  "Telegram long-polling adapter."
  (:require
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]))

(def ^:private telegram-api "https://api.telegram.org")
(def ^:private max-message-chars 4096)

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

(defn- chunks [text]
  (let [s (str text)]
    (if (<= (count s) max-message-chars)
      [s]
      (loop [remaining s
             acc []]
        (if (<= (count remaining) max-message-chars)
          (conj acc remaining)
          (recur (subs remaining max-message-chars)
                 (conj acc (subs remaining 0 max-message-chars))))))))

(defn send-message!
  [token chat-id text]
  (mapv #(api-request! token "sendMessage" {:chat_id chat-id
                                            :text %})
        (chunks text)))

(defn- id-set [ids]
  (set (map str (or ids []))))

(defn allowed?
  [config update]
  (let [allowlist (:allowlist config)
        user-ids (id-set (:user-ids allowlist))
        chat-ids (id-set (:chat-ids allowlist))
        message (:message update)
        user-id (some-> message :from :id str)
        chat-id (some-> message :chat :id str)]
    (or (and (empty? user-ids) (empty? chat-ids))
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

(defn command-response
  [system chat command]
  (when (str/starts-with? command "/")
    (let [mapping (session-mapping! (:store system) chat)
          session-id (:session-id mapping)
          command* (-> command str/lower-case (str/split #"\s+") first)]
      (case command*
        "/start" "Ready. Send message to chat."
        "/help" "/start /help /reset /memory /status"
        "/reset" (do
                   (reset-session! (:store system) chat)
                   "Session reset.")
        "/memory" (memory-status system session-id)
        "/status" (status-text system session-id)
        nil))))

(defn process-update!
  [system config {:keys [send-message-fn run-chat-fn]} update]
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
        (let [send! (or send-message-fn #(send-message! (:bot-token config) %1 %2))
              response (or (command-response system chat text)
                           (let [mapping (session-mapping! (:store system) chat)
                                 result ((or run-chat-fn chat/run!)
                                         system
                                         {:session-id (:session-id mapping)
                                          :messages [{:role "user"
                                                      :content text}]})]
                             (:content result)))]
          (send! chat-id response)
          :processed)))))

(defn create-service
  ([system] (create-service system {}))
  ([system opts]
   {:system system
    :config (get-in system [:config :channel-adapters :telegram])
    :running? (atom false)
    :future (atom nil)
    :last-offset (atom nil)
    :opts opts}))

(defn enabled? [service]
  (and (true? (get-in service [:config :enabled]))
       (not (str/blank? (get-in service [:config :bot-token])))))

(defn start!
  [service]
  (if-not (enabled? service)
    service
    (let [{:keys [system config running? last-offset opts]} service
          worker (:future service)
          get-updates-fn (or (:get-updates-fn opts)
                             #(get-updates! (:bot-token config) %))
          poll-timeout (or (:poll-timeout-seconds config) 30)
          poll-limit (or (:poll-limit config) 100)]
      (when-not @running?
        (reset! running? true)
        (reset!
         worker
         (future
           (while @running?
             (try
               (let [updates (get-updates-fn {:offset @last-offset
                                              :timeout poll-timeout
                                              :limit poll-limit})]
                 (doseq [update updates]
                   (reset! last-offset (inc (long (:update_id update))))
                   (process-update! system config opts update)))
               (catch Exception e
                 ((:event-sink system) {:event-type :telegram.error
                                        :entity-type :telegram
                                        :payload {:message (.getMessage e)
                                                  :type (some-> e ex-data :type)}})
                 (Thread/sleep 1000)))))))
      service)))

(defn stop!
  [service]
  (when service
    (reset! (:running? service) false)
    (when-let [f @(:future service)]
      (future-cancel f))
    (reset! (:future service) nil))
  service)

(defn health-check [service]
  {:healthy true
   :enabled (boolean (enabled? service))
   :running (boolean (some-> service :running? deref))
   :last-offset (some-> service :last-offset deref)})
