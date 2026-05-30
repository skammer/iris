(ns agent.telegram
  "Telegram long-polling adapter."
  (:require
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.channels.core :as channels]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.skills :as skills]
   [agent.telegram.format :as fmt]
   [agent.tools.core :as tools]
   [agent.tools.display :as tool-display]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.util Base64)))

(def ^:private telegram-api "https://api.telegram.org")
(def ^:private max-message-chars 4096)
;; Markdown source cap kept below the 4096 hard limit so MarkdownV2 escaping
;; doesn't push a chunk over the wire-level cap.
(def ^:private max-source-chars 3400)
;; Telegram per-chat send rate is ~1/sec; staying above this floor keeps
;; draft updates in order and avoids 429s on slow connections.
(def ^:private stream-flush-ms 1200)
;; Telegram chat actions expire after ~5s; refresh below that window.
(def ^:private typing-refresh-ms 4000)
(def ^:private default-max-download-bytes (* 20 1024 1024))

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

(defn api-multipart-request!
  [token method parts]
  (let [response (http/post (api-url token method)
                            {:multipart parts
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

(defn get-file!
  [token file-id]
  (api-request! token "getFile" {:file_id file-id}))

(defn- file-download-url [token file-path]
  (str telegram-api "/file/bot" token "/" file-path))

(defn download-file!
  [token file-path]
  (let [response (http/get (file-download-url token file-path)
                           {:as :byte-array
                            :throw-exceptions false
                            :socket-timeout 70000
                            :conn-timeout 10000})]
    (if (<= 200 (:status response 0) 299)
      (:body response)
      (throw (ex-info "Telegram file download failed"
                      {:type :telegram-file-download-error
                       :status (:status response)
                       :file-path file-path})))))

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

(defn send-html-message!
  [token chat-id text]
  (let [s (str text)]
    (api-request! token "sendMessage"
                  {:chat_id chat-id
                   :text (if (> (count s) max-message-chars)
                           (subs s 0 max-message-chars)
                           s)
                   :parse_mode "HTML"})))

(defn send-chat-action!
  [token chat-id action]
  (api-request! token "sendChatAction"
                {:chat_id chat-id
                 :action action}))

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

(defn send-document-file!
  "Uploads a local file as a Telegram document. Caption is optional."
  ([token chat-id file] (send-document-file! token chat-id file nil))
  ([token chat-id file caption]
   (let [caption-payload (when-not (str/blank? caption)
                           (text-payload caption))
         parts (cond-> [{:name "chat_id" :content (str chat-id)}
                        {:name "document" :content (io/file file)}]
                 (:text caption-payload)
                 (conj {:name "caption" :content (:text caption-payload)})
                 (:parse_mode caption-payload)
                 (conj {:name "parse_mode" :content (:parse_mode caption-payload)}))]
     (api-multipart-request! token "sendDocument" parts))))

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

(defn- max-download-bytes [config]
  (long (or (:max-download-bytes config) default-max-download-bytes)))

(defn- mime-kind [mime-type fallback]
  (let [mime (str/lower-case (or mime-type ""))]
    (cond
      (str/starts-with? mime "image/") :image
      (str/starts-with? mime "audio/") :audio
      (str/starts-with? mime "video/") :video
      :else fallback)))

(defn- photo-size [photo]
  (or (:file_size photo)
      (* (long (or (:width photo) 0))
         (long (or (:height photo) 0)))))

(defn- largest-photo [photos]
  (last (sort-by photo-size photos)))

(defn- descriptor
  [kind media & [{:keys [media-type filename alt]}]]
  (when-let [file-id (:file_id media)]
    {:kind kind
     :file-id file-id
     :file-size (:file_size media)
     :media-type media-type
     :filename filename
     :alt alt}))

(defn- sticker-media-type [sticker]
  (cond
    (:is_video sticker) "video/webm"
    (:is_animated sticker) "application/x-tgsticker"
    :else "image/webp"))

(defn- media-descriptors [message]
  (cond-> []
    (seq (:photo message))
    (conj (descriptor :image
                      (largest-photo (:photo message))
                      {:media-type "image/jpeg"
                       :alt "Telegram photo"}))

    (:document message)
    (conj (let [doc (:document message)
                mime (:mime_type doc)]
            (descriptor (mime-kind mime :file)
                        doc
                        {:media-type mime
                         :filename (:file_name doc)
                         :alt (:file_name doc)})))

    (:audio message)
    (conj (let [audio (:audio message)]
            (descriptor :audio
                        audio
                        {:media-type (:mime_type audio)
                         :filename (:file_name audio)
                         :alt (:title audio)})))

    (:voice message)
    (conj (descriptor :audio
                      (:voice message)
                      {:media-type (or (get-in message [:voice :mime_type]) "audio/ogg")
                       :alt "Telegram voice message"}))

    (:video message)
    (conj (let [video (:video message)]
            (descriptor :video
                        video
                        {:media-type (:mime_type video)
                         :filename (:file_name video)
                         :alt (:file_name video)})))

    (:video_note message)
    (conj (descriptor :video
                      (:video_note message)
                      {:media-type "video/mp4"
                       :alt "Telegram video note"}))

    (:animation message)
    (conj (let [animation (:animation message)]
            (descriptor :video
                        animation
                        {:media-type (:mime_type animation)
                         :filename (:file_name animation)
                         :alt (:file_name animation)})))

    (:sticker message)
    (conj (let [sticker (:sticker message)
                mime (sticker-media-type sticker)]
            (descriptor (mime-kind mime :file)
                        sticker
                        {:media-type mime
                         :alt (or (:emoji sticker) "Telegram sticker")})))))

(defn- ensure-download-size! [limit {:keys [file-size file-id]}]
  (when (and file-size (> (long file-size) limit))
    (throw (ex-info "Telegram media is too large to send to LLM"
                    {:type :telegram-media-too-large
                     :file-id file-id
                     :file-size file-size
                     :max-download-bytes limit}))))

(defn- infer-filename [descriptor file-path]
  (or (:filename descriptor)
      (some-> file-path (str/split #"/") last not-empty)
      (str (:file-id descriptor))))

(defn- media-block!
  [config opts descriptor]
  (let [token (:bot-token config)
        limit (max-download-bytes config)
        get-file (or (:get-file-fn opts) get-file!)
        download-file (or (:download-file-fn opts) download-file!)]
    (ensure-download-size! limit descriptor)
    (let [file (get-file token (:file-id descriptor))
          file-path (:file_path file)]
      (ensure-download-size! limit (assoc descriptor :file-size (or (:file_size file)
                                                                    (:file-size descriptor))))
      (when (str/blank? file-path)
        (throw (ex-info "Telegram getFile response missing file_path"
                        {:type :telegram-file-path-missing
                         :file-id (:file-id descriptor)})))
      (let [bytes (download-file token file-path)
            filename (infer-filename descriptor file-path)
            media-type (or (:media-type descriptor) "application/octet-stream")]
        (cond-> {:type (:kind descriptor)
                 :source {:type :base64
                          :media-type media-type
                          :value (.encodeToString (Base64/getEncoder) bytes)}}
          (:alt descriptor) (assoc :alt (:alt descriptor))
          filename (assoc :filename filename))))))

(defn- default-media-prompt [descriptors]
  (let [kinds (->> descriptors (map (comp name :kind)) distinct (str/join ", "))]
    (str "Analyze attached " kinds ".")))

(defn- user-content!
  [config opts message]
  (let [text (or (:text message) (:caption message))
        descriptors (vec (keep identity (media-descriptors message)))
        media-blocks (mapv #(media-block! config opts %) descriptors)
        prompt (or (some-> text str/trim not-empty)
                   (when (seq media-blocks) (default-media-prompt descriptors)))]
    (if (seq media-blocks)
      (cond-> []
        prompt (conj {:type :text :text prompt})
        true (into media-blocks))
      text)))

(defn- processable-message? [message]
  (or (not (str/blank? (:text message)))
      (not (str/blank? (:caption message)))
      (seq (media-descriptors message))))

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

(defn- available-prompt-modes []
  (str/join ", " (prompts/list-modes)))

(defn- prompt-command-response [store session-id rest]
  (let [requested (some-> rest str/trim str/lower-case not-empty)]
    (cond
      (nil? requested)
      (str "Prompt mode: " (or (:active-mode (sqlite/get-session store session-id)) "off")
           ". Available: " (available-prompt-modes) ".")

      (= "off" requested)
      (do
        (sqlite/set-session-active-mode! store session-id nil)
        "Prompt mode off.")

      (some #{requested} (prompts/list-modes))
      (do
        (sqlite/set-session-active-mode! store session-id requested)
        (str "Prompt mode: " requested "."))

      :else
      (str "Unknown prompt mode: " requested
           ". Available: " (available-prompt-modes) "."))))

(defn- skills-command-response [system rest]
  (let [prefix (some-> rest str/trim not-empty)
        page (skills/slash-commands-page (:skills-registry system)
                                         {:prefix prefix :page 1 :page-size 20})
        items (:items page)]
    (if (seq items)
      (str "Skills:\n"
           (str/join "\n"
                     (map (fn [{:keys [name description]}]
                            (str "/" name " - " description))
                          items)))
      "No skills found.")))

(defn- invoked-skill-names [system text]
  (let [catalog (set (map :name (skills/skill-catalog (:skills-registry system))))]
    (->> (skills/parse-invoked-skill-names text)
         (filter catalog)
         vec)))

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
          {:keys [command rest]} (parse-command-args command)]
      (case command
        "/start" "Ready. Send message to chat."
        "/help" "/start /help /stop /reset /memory /status /prompt [name|off] /loop [prompt|status|stop|run|plan] /skills [prefix] /photo <url> [caption] /file <url> [caption]"
        "/reset" (do
                   (reset-session! (:store system) chat)
                   "Session reset.")
        "/memory" (memory-status system session-id)
        "/status" (status-text system session-id)
        "/prompt" (prompt-command-response (:store system) session-id rest)
        "/skills" (skills-command-response system rest)
        nil))))

(defn- stop-chat!
  [system opts chat-id session-id]
  (chat/cancel-session! system session-id)
  (when-let [task (:future (get @(:active-tasks opts) chat-id))]
    (future-cancel task)
    (swap! (:active-tasks opts) dissoc chat-id))
  {:content "Stopping."})

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

(defn- next-draft-id []
  (inc (mod (System/currentTimeMillis) 2147483647)))

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

(defn- build-stream-controls
  "Returns `{:on-delta f :finalize! f}` for animating a partial reply via
   sendMessageDraft. `finalize!` promotes the accumulated draft to a real
   sendMessage (drafts are ephemeral and get cleared by any subsequent regular
   message), resets the accumulator, and rotates the draft id so the next step
   streams onto a fresh draft slot. Returns nil for non-private chats."
  [system config opts chat chat-id]
  (when (private-chat? chat)
    (let [token (:bot-token config)
          draft-id (atom (next-draft-id))
          accumulator (atom "")
          last-flush (atom 0)
          send-draft! (or (:send-message-draft-fn opts)
                          (fn [cid did text] (send-message-draft! token cid did text)))
          send-msg! (or (:send-message-fn opts)
                        (fn [cid text] (send-message! token cid text)))
          flush! (fn []
                   (let [now (System/currentTimeMillis)
                         text @accumulator]
                     (when (and (not (str/blank? text))
                                (>= (- now @last-flush) stream-flush-ms))
                       (reset! last-flush now)
                       (safe-telegram! system chat-id :draft-update
                                       #(send-draft! chat-id @draft-id text)))))
          finalize! (fn []
                      (let [text @accumulator]
                        (reset! accumulator "")
                        (reset! last-flush 0)
                        (swap! draft-id #(inc (mod % 2147483647)))
                        (when-not (str/blank? text)
                          (safe-telegram! system chat-id :draft-finalize
                                          #(send-msg! chat-id text)))))]
      {:on-delta (fn [delta]
                   (swap! accumulator str delta)
                   (flush!))
       :finalize! finalize!})))

(defn- build-on-tool-call
  "Builds an on-tool-call callback that finalizes any in-flight streamed
   draft (so streamed text isn't lost when sending the tool-call message
   clears the draft) and then sends a compact summary per tool turn. Returns
   nil when the channel disables tool-call display."
  [system opts chat-id stream-controls]
  (let [cfg (tool-display/channel-config system :telegram nil)]
    (when (true? (:show-tool-calls? cfg))
      (let [send! (or (:send-message-fn opts)
                      (fn [cid text]
                        (send-html-message! (get-in system [:config :channel-adapters :telegram :bot-token])
                                            cid text)))
            finalize! (:finalize! stream-controls)]
        (fn [{:keys [receipt]}]
          (safe-telegram! system chat-id :tool-call-summary
                          (fn []
                            (when finalize! (finalize!))
                            (let [text (tool-display/telegram-summary system receipt)]
                              (when-not (str/blank? text)
                                (send! chat-id text))))))))))

(defn- start-typing-indicator!
  [system config opts chat-id]
  (let [token (:bot-token config)
        running? (atom true)
        send-action! (or (:send-chat-action-fn opts)
                         (fn [cid action] (send-chat-action! token cid action)))
        worker (future
                 (while @running?
                   (safe-telegram! system chat-id :typing
                                   #(send-action! chat-id "typing"))
                   (Thread/sleep typing-refresh-ms)))]
    (fn []
      (reset! running? false)
      (future-cancel worker))))

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
     on-tool-call (assoc :on-tool-call on-tool-call))))

(defn- run-chat-events!
  [system chat-id session-id user-text stream-controls on-tool-call]
  (let [broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject)
                                         {:buffer-strategy :sliding
                                          :buffer-size 256
                                          :slow-client :drop-new})
        ch (:channel subscription)
        result-ch (async/chan 1)
        saw-delta? (atom false)
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
                  (reset! saw-delta? true)
                  (when-let [on-delta (:on-delta stream-controls)]
                    (on-delta (:delta payload))))
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
                  (when (and (not @saw-delta?)
                             (not (str/blank? (:content payload))))
                    (when-let [on-delta (:on-delta stream-controls)]
                      (on-delta (:content payload))))
                  (when finalize! (finalize!))
                  (reset! saw-delta? false))
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
                  (fn [cid text] (send-message! token cid text)))
        stop-typing! (start-typing-indicator! system config opts chat-id)
        stream-controls (build-stream-controls system config opts chat chat-id)
        on-tool-call (build-on-tool-call system opts chat-id stream-controls)
        callback-path? (or (:chat-fn opts)
                           (nil? (or (:event-bus system) (:broker system))))
        result (try
                 (if callback-path?
                   (run-chat-callbacks! system opts chat-id session-id user-text stream-controls on-tool-call)
                   (run-chat-events! system chat-id session-id user-text stream-controls on-tool-call))
                 (finally
                   (stop-typing!)))
        final (or (:content result) "")]
    (when (or callback-path?
              (nil? (:finalize! stream-controls)))
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
        message (:message update)
        chat (:chat message)
        chat-id (:id chat)
        text (:text message)]
    (when (and chat-id (processable-message? message))
      (if-not (allowed? config update)
        (do
          ((:event-sink system) {:event-type :telegram.blocked
                                 :entity-type :telegram_chat
                                 :entity-id (str chat-id)
                                 :payload {:chat-id chat-id
                                           :user-id (get-in message [:from :id])}})
          :blocked)
        (let [send! (or send-message-fn #(send-message! (:bot-token config) %1 %2))]
          ((:event-sink system) {:event-type :channel.message.received
                                 :entity-type :channel
                                 :entity-id "telegram"
                                 :payload {:channel :telegram
                                           :direction :inbound
                                           :chat-id (str chat-id)
                                           :message-id (some-> message :message_id str)
                                           :sender-id (some-> message :from :id str)
                                           :media-count (count (media-descriptors message))
                                           :thread-scope (str chat-id)}})
          (cond
            (and (not (str/blank? text))
                 (= "/stop" (-> text str/trim str/lower-case (str/split #"\s+") first)))
            (let [mapping (session-mapping! (:store system) chat)
                  result (stop-chat! system opts chat-id (:session-id mapping))]
              (send! chat-id (:content result))
              :processed)

            (and (not (str/blank? text))
                 (handle-media-command! config opts chat-id text))
            :processed

            :else
            (let [builtin-reply (when-not (str/blank? text)
                                  (command-response system chat text))]
              (if builtin-reply
                (do (send! chat-id builtin-reply) :processed)
                (let [mapping (session-mapping! (:store system) chat)]
                  (let [content (try
                                  (user-content! config opts message)
                                  (catch Exception e
                                    (send! chat-id (str "Media processing failed: " (.getMessage e)))
                                    ::media-processing-failed))]
                    (when-not (= ::media-processing-failed content)
                      (when-let [invoked (seq (invoked-skill-names system (or text "")))]
                        (send! chat-id (str "Skills: "
                                            (str/join ", " (map #(str "/" %) invoked)))))
                      (if (:async-chat? opts)
                        (run-chat-async! system config opts chat chat-id
                                         (:session-id mapping) content)
                        (run-chat! system config opts chat chat-id
                                   (:session-id mapping) content))))
                  :processed)))))))))

(declare start! stop! health-check)

(defn- description []
  (channels/create-adapter-description
   :telegram
   "Telegram"
   :polling
   #{:supports-outbound :supports-streaming :supports-typing :supports-draft-updates :supports-draft-lifecycle}
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
      (send-message! (:bot-token config) (:recipient message*) (:content message*))))
  channels/IChannelTyping
  (send-adapter-typing! [_ recipient _metadata]
    (send-chat-action! (:bot-token config) recipient "typing"))
  channels/IChannelDrafts
  (send-adapter-draft! [_ message]
    (let [message* (channels/normalize-send-message nil message)
          draft-id (long (or (get-in message* [:metadata :draft-id])
                             (next-draft-id)))]
      (send-message-draft! (:bot-token config) (:recipient message*) draft-id (:content message*))
      {:channel :telegram
       :recipient (:recipient message*)
       :draft-id draft-id}))
  (update-adapter-draft! [_ draft update]
    (let [content (or (:content update) (:text update) (str update))]
      (send-message-draft! (:bot-token config) (:recipient draft) (:draft-id draft) content)
      (assoc draft :content content)))
  (finalize-adapter-draft! [_ draft final]
    (let [content (or (:content final) (:text final) (str final))]
      (send-message! (:bot-token config) (:recipient draft) content)
      (assoc draft :content content :finalized? true))))

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
