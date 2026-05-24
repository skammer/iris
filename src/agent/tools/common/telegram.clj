(ns agent.tools.common.telegram
  "Telegram outbound media tools (sendPhoto, sendDocument).
   Tools read :telegram-chat-id from execution context (set by the
   Telegram adapter when invoking chat/run!). Bot token is closed over
   at registration time."
  (:require
   [agent.telegram :as telegram]
   [agent.tools.core :as tools]
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
  [{:keys [bot-token]}]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :telegram_send_document
     "Send a document/file to the current Telegram chat. Accepts a public URL or a previously-uploaded file_id."
     :category :messaging
     :input-schema [:map {:closed true}
                    [:document :string]
                    [:caption {:optional true} :string]]
     :operation :act)
    :execute-fn
    (fn [input context]
      (let [chat-id (require-chat-id! context :telegram_send_document)
            document (:document input)
            caption (:caption input)]
        (require-non-blank! :telegram_send_document :document document)
        (telegram/send-document! bot-token chat-id document caption)
        {:sent true :chat-id chat-id :document document :caption caption}))}))

(defn enabled?
  [{:keys [bot-token]}]
  (not (str/blank? bot-token)))
