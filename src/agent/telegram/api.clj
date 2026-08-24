(ns agent.telegram.api
  "Telegram Bot API client and send helpers."
  (:require
   [agent.telegram.format :as fmt]
   [agent.telegram.rich :as rich]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private telegram-api "https://api.telegram.org")
(def ^:private max-message-chars 4096)
(def ^:private max-source-chars 3400)
(def ^:private disabled-link-preview
  {:link_preview_options {:is_disabled true}})

(defn- parse-body [body]
  (cond
    (map? body) body
    (string? body) (json/parse-string body true)
    :else body))

(defn- api-url [token method]
  (str telegram-api "/bot" token "/" method))

(defn request!
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

(defn multipart-request!
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
  (request! token "getFile" {:file_id file-id}))

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
  (request! token "getUpdates"
            (cond-> {:timeout timeout
                     :limit limit
                     :allowed_updates ["message" "callback_query"
                                       "stopped_message_generation"]}
              offset (assoc :offset offset))))

(defn- text-payload
  [text]
  (let [s (str text)
        md (fmt/safe-md->markdown-v2 s)]
    (if (and md (<= (count md) max-message-chars))
      {:text md :parse_mode "MarkdownV2"}
      {:text (if (> (count s) max-message-chars)
               (subs s 0 max-message-chars)
               s)})))

(defn- without-link-preview [payload]
  (merge payload disabled-link-preview))

(declare send-message!)

(defn- input-rich-message
  [markdown {:keys [media is-rtl? skip-entity-detection?]}]
  (cond-> {:markdown markdown}
    (seq media) (assoc :media media)
    (some? is-rtl?) (assoc :is_rtl (boolean is-rtl?))
    (some? skip-entity-detection?)
    (assoc :skip_entity_detection (boolean skip-entity-detection?))))

(defn- send-rich-chunks!
  [token chat-id markdown opts]
  (let [chunks (rich/final-chunks nil markdown)
        reply-markup (:reply-markup opts)
        ephemeral-message-parameters (:ephemeral-message-parameters opts)]
    (when (and (< 1 (count chunks))
               (or ephemeral-message-parameters (seq (:media opts))))
      (throw (ex-info "Rich messages with media or ephemeral parameters must fit in one chunk"
                      {:type :telegram-rich-message-not-single-chunk
                       :chunk-count (count chunks)})))
    (if (seq chunks)
      (mapv (fn [idx chunk]
              (request! token "sendRichMessage"
                        (without-link-preview
                         (cond-> {:chat_id chat-id
                                  :rich_message (input-rich-message
                                                 chunk
                                                 (if (zero? idx) opts {}))}
                           (and (zero? idx) reply-markup)
                           (assoc :reply_markup reply-markup)
                           (and (zero? idx) ephemeral-message-parameters)
                           (assoc :ephemeral_message_parameters
                                  ephemeral-message-parameters)))))
            (range)
            chunks)
      [])))

(defn- send-rich-or-legacy!
  [token chat-id text opts]
  (try
    (send-rich-chunks! token chat-id text opts)
    (catch Exception _
      (if-let [reply-markup (:reply-markup opts)]
        (let [chunks (fmt/chunk-markdown (str text) max-source-chars)]
          (mapv (fn [idx chunk]
                  (request! token "sendMessage"
                            (without-link-preview
                             (cond-> (assoc (text-payload chunk) :chat_id chat-id)
                               (and (zero? idx) reply-markup)
                               (assoc :reply_markup reply-markup)))))
                (range)
                chunks))
        (send-message! token chat-id text)))))

(defn send-message!
  [token chat-id text]
  (mapv (fn [chunk]
          (request! token "sendMessage"
                    (without-link-preview
                     (assoc (text-payload chunk) :chat_id chat-id))))
        (fmt/chunk-markdown (str text) max-source-chars)))

(defn send-message-with-reply-markup!
  [token chat-id text reply-markup]
  (send-rich-or-legacy! token chat-id text {:reply-markup reply-markup}))

(defn send-html-message-with-reply-markup!
  [token chat-id text reply-markup]
  (send-rich-or-legacy! token chat-id text {:reply-markup reply-markup}))

(defn send-html-message!
  [token chat-id text]
  (send-rich-or-legacy! token chat-id text nil))

(defn send-chat-action!
  [token chat-id action]
  (request! token "sendChatAction"
            {:chat_id chat-id
             :action action}))

(defn answer-callback-query!
  ([token callback-query-id] (answer-callback-query! token callback-query-id nil))
  ([token callback-query-id opts]
   (request! token "answerCallbackQuery"
             (merge {:callback_query_id callback-query-id} (or opts {})))))

(defn edit-message-reply-markup!
  [token chat-id message-id reply-markup]
  (request! token "editMessageReplyMarkup"
            (cond-> {:chat_id chat-id
                     :message_id message-id}
              (some? reply-markup) (assoc :reply_markup reply-markup))))

(defn edit-rich-message!
  [token chat-id message-id markdown]
  (request! token "editMessageText"
            (without-link-preview
             {:chat_id chat-id
              :message_id message-id
              :rich_message {:markdown markdown}})))

(defn send-rich-message!
  "Sends a rich message. Supports Bot API 10.3 embedded media references and
   ephemeral-message parameters through opts."
  ([token chat-id markdown] (send-rich-message! token chat-id markdown nil))
  ([token chat-id markdown opts]
   (send-rich-chunks! token chat-id markdown (or opts {}))))

(defn send-rich-message-draft!
  "Streams a partial rich message as an ephemeral draft (private chats only).
   Re-sending with the same draft-id animates the update; the draft must be
   finalized with send-rich-message!."
  ([token chat-id draft-id markdown]
   (send-rich-message-draft! token chat-id draft-id markdown nil))
  ([token chat-id draft-id markdown {:keys [can-stop? keep-on-stop?]}]
   (request! token "sendRichMessageDraft"
             (without-link-preview
              (cond-> {:chat_id chat-id
                       :draft_id draft-id
                       :rich_message {:markdown markdown}}
                (some? can-stop?) (assoc :can_stop (boolean can-stop?))
                (some? keep-on-stop?) (assoc :keep_on_stop (boolean keep-on-stop?)))))))

(defn send-message-draft!
  ([token chat-id draft-id text]
   (send-message-draft! token chat-id draft-id text nil))
  ([token chat-id draft-id text {:keys [can-stop? keep-on-stop?]}]
   (let [s (str text)
         clamped (if (> (count s) max-source-chars)
                   (subs s 0 max-source-chars)
                   s)
         payload (text-payload clamped)]
     (request! token "sendMessageDraft"
               (without-link-preview
                (cond-> (merge {:chat_id chat-id :draft_id draft-id} payload)
                  (some? can-stop?) (assoc :can_stop (boolean can-stop?))
                  (some? keep-on-stop?) (assoc :keep_on_stop (boolean keep-on-stop?))))))))

(defn edit-ephemeral-rich-message!
  [token chat-id receiver-user-id ephemeral-message-id markdown]
  (request! token "editEphemeralMessageText"
            (without-link-preview
             {:chat_id chat-id
              :receiver_user_id receiver-user-id
              :ephemeral_message_id ephemeral-message-id
              :rich_message {:markdown markdown}})))

(defn edit-ephemeral-message-media!
  [token chat-id receiver-user-id ephemeral-message-id media]
  (request! token "editEphemeralMessageMedia"
            {:chat_id chat-id
             :receiver_user_id receiver-user-id
             :ephemeral_message_id ephemeral-message-id
             :media media}))

(defn edit-ephemeral-message-media-file!
  [token chat-id receiver-user-id ephemeral-message-id media file]
  (let [attachment-name "ephemeral_media"
        media* (assoc media :media (str "attach://" attachment-name))]
    (multipart-request!
     token
     "editEphemeralMessageMedia"
     [{:name "chat_id" :content (str chat-id)}
      {:name "receiver_user_id" :content (str receiver-user-id)}
      {:name "ephemeral_message_id" :content (str ephemeral-message-id)}
      {:name "media" :content (json/generate-string media*)}
      {:name attachment-name :content (io/file file)}])))

(defn delete-ephemeral-message!
  [token chat-id receiver-user-id ephemeral-message-id]
  (request! token "deleteEphemeralMessage"
            {:chat_id chat-id
             :receiver_user_id receiver-user-id
             :ephemeral_message_id ephemeral-message-id}))

(defn- attachment-payload
  [chat-id media-key media caption]
  (let [base {:chat_id chat-id media-key media}]
    (if (str/blank? caption)
      base
      (let [{:keys [text parse_mode]} (text-payload caption)]
        (cond-> (assoc base :caption text)
          parse_mode (assoc :parse_mode parse_mode))))))

(defn send-photo!
  ([token chat-id photo] (send-photo! token chat-id photo nil))
  ([token chat-id photo caption]
   (request! token "sendPhoto" (attachment-payload chat-id :photo photo caption))))

(defn send-document!
  ([token chat-id document] (send-document! token chat-id document nil))
  ([token chat-id document caption]
   (request! token "sendDocument" (attachment-payload chat-id :document document caption))))

(defn send-document-file!
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
     (multipart-request! token "sendDocument" parts))))
