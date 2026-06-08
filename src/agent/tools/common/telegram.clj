(ns agent.tools.common.telegram
  "Telegram outbound tools.
   Tools read :telegram-chat-id from execution context (set by the
   Telegram adapter when invoking chat/run!). Bot token is closed over
   at registration time."
  (:require
   [agent.telegram :as telegram]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- require-chat-id! [context tool-name]
  (or (:telegram-chat-id context)
      (throw (tools/tool-error :missing-context
                               (str (name tool-name) " requires a Telegram session: no :telegram-chat-id in context")
                               {:tool-name tool-name}))))

(defn- require-non-blank! [tool-name field value]
  (when (or (not (string? value)) (str/blank? value))
    (throw (tools/validation-error (str (name tool-name) " requires non-blank " (name field))
                                   {:tool-name tool-name :field field}))))

(def ^:private sample-document-url
  "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf")

(defn- expand-home [path]
  (let [home (System/getProperty "user.home")]
    (cond
      (= path "~") home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else path)))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file (expand-home path))))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- local-file [roots document]
  (when (and (string? document)
             (or (str/starts-with? document "/")
                 (str/starts-with? document ".")
                 (str/starts-with? document "~")))
    (let [path (canonical-path document)
          file (io/file path)]
      (when-not (within-root? roots path)
        (throw (tools/tool-error :path-not-allowed
                                 "Document path is outside allowed roots"
                                 {:path path :roots roots})))
      (when-not (.isFile file)
        (throw (tools/tool-error :not-found "Document file not found" {:path path})))
      file)))

(defn create-send-photo-tool
  [{:keys [bot-token]}]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :telegram_send_photo
     "Send a photo to the current Telegram chat. Use when the user asked for an image, when an image is the most direct answer, or when an image was generated/fetched and should be delivered to the user."
     :category :messaging
     :input-schema [:map {:closed true}
                    [:photo :string]
                    [:caption {:optional true} :string]]
     :operation :act)
    :execute-fn
    (fn [input context]
      (let [chat-id (require-chat-id! context :telegram_send_photo)
            photo (:photo input)
            caption (:caption input)]
        (require-non-blank! :telegram_send_photo :photo photo)
        (telegram/send-photo! bot-token chat-id photo caption)
        {:sent true :chat-id chat-id :photo photo :caption caption}))}))

(defn create-send-document-tool
  [{:keys [bot-token document-roots max-document-bytes]
    :or {document-roots ["."]
         max-document-bytes (* 20 1024 1024)}}]
  (let [roots (mapv canonical-path document-roots)]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :telegram_send_document
       "Send a document/file to the current Telegram chat. Accepts public URL, Telegram file_id, or local path under allowed document roots. If the user asks for any/example document and gives no source, omit document to send a small sample PDF."
       :category :messaging
       :input-schema [:map {:closed true}
                      [:document {:optional true} [:maybe :string]]
                      [:caption {:optional true} :string]]
       :operation :act)
      :execute-fn
      (fn [input context]
        (let [chat-id (require-chat-id! context :telegram_send_document)
              document (if (str/blank? (or (:document input) ""))
                         sample-document-url
                         (:document input))
              caption (:caption input)]
          (if-let [file (local-file roots document)]
            (do
              (when (> (.length file) max-document-bytes)
                (throw (tools/tool-error :file-too-large
                                         "Document exceeds max-document-bytes"
                                         {:path (.getCanonicalPath file)
                                          :size (.length file)
                                          :max-document-bytes max-document-bytes})))
              (telegram/send-document-file! bot-token chat-id file caption)
              {:sent true :chat-id chat-id :document (.getCanonicalPath file) :caption caption :uploaded? true})
            (do
              (telegram/send-document! bot-token chat-id document caption)
              {:sent true :chat-id chat-id :document document :caption caption}))))})))

(defn- keyboard-rows [choices]
  (mapv (fn [row]
          (mapv (fn [choice] {:text choice}) row))
        (partition-all 2 choices)))

(defn- reply-keyboard
  [{:keys [choices one-time-keyboard resize-keyboard input-placeholder]}]
  (cond-> {:keyboard (keyboard-rows choices)
           :resize_keyboard (not (false? resize-keyboard))
           :one_time_keyboard (not (false? one-time-keyboard))}
    (not (str/blank? input-placeholder))
    (assoc :input_field_placeholder input-placeholder)))

(defn create-ask-tool
  [{:keys [bot-token]}]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :telegram_ask
     "Ask the current Telegram user a question with a custom reply keyboard. Use when you need the user's choice; their tap arrives as the next chat message."
     :category :messaging
     :input-schema [:map {:closed true}
                    [:question :string]
                    [:choices [:vector {:min 1 :max 12} :string]]
                    [:one-time-keyboard {:optional true} :boolean]
                    [:resize-keyboard {:optional true} :boolean]
                    [:input-placeholder {:optional true} :string]]
     :operation :act)
    :execute-fn
    (fn [input context]
      (let [chat-id (require-chat-id! context :telegram_ask)
            question (:question input)
            choices (mapv str (:choices input))]
        (require-non-blank! :telegram_ask :question question)
        (doseq [choice choices]
          (require-non-blank! :telegram_ask :choices choice))
        (telegram/send-message-with-reply-markup!
         bot-token
         chat-id
         question
         (reply-keyboard (assoc input :choices choices)))
        {:sent true
         :chat-id chat-id
         :awaiting-reply true
         :choices choices}))}))

(defn enabled?
  [{:keys [bot-token]}]
  (not (str/blank? bot-token)))
