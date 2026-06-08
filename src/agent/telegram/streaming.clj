(ns agent.telegram.streaming
  "Telegram draft streaming, typing indicator, thinking quote, and tool summaries."
  (:require
   [agent.telegram.api :as tg-api]
   [agent.tools.display :as tool-display]
   [clojure.string :as str]))

(def ^:private max-source-chars 3400)
(def ^:private stream-flush-ms 1200)
(def ^:private typing-refresh-ms 4000)
(def ^:private max-draft-id 2147483647)

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- thinking-quote-html [text]
  (let [source (str text)
        clipped (if (> (count source) max-source-chars)
                  (str (subs source 0 max-source-chars) "\n\n[truncated]")
                  source)]
    (str "<blockquote expandable>thinking\n\n" (escape-html clipped) "</blockquote>")))

(defn- private-chat? [chat]
  (= "private" (:type chat)))

(defn- next-draft-id []
  (inc (mod (System/currentTimeMillis) max-draft-id)))

(defn- rotate-draft-id
  [id]
  (if (>= id max-draft-id) 1 (inc id)))

(defn build-controls
  [safe-telegram! system config opts chat chat-id]
  (when (private-chat? chat)
    (let [token (:bot-token config)
          draft-id (atom (next-draft-id))
          accumulator (atom "")
          thinking-accumulator (atom "")
          last-flush (atom 0)
          send-draft! (or (:send-message-draft-fn opts)
                          (fn [cid did text] (tg-api/send-message-draft! token cid did text)))
          send-msg! (or (:send-message-fn opts)
                        (fn [cid text] (tg-api/send-message! token cid text)))
          send-html! (or (:send-html-message-fn opts)
                         (fn [cid text] (tg-api/send-html-message! token cid text)))
          flush! (fn []
                   (let [now (System/currentTimeMillis)
                         text @accumulator]
                     (when (and (not (str/blank? text))
                                (>= (- now @last-flush) stream-flush-ms))
                       (reset! last-flush now)
                       (safe-telegram! system chat-id :draft-update
                                       #(send-draft! chat-id @draft-id text)))))
          finalize-thinking! (fn []
                               (let [text @thinking-accumulator]
                                 (reset! thinking-accumulator "")
                                 (when-not (str/blank? text)
                                   (safe-telegram! system chat-id :thinking-summary
                                                   #(send-html! chat-id (thinking-quote-html text))))))
          finalize! (fn []
                      (let [text @accumulator]
                        (reset! accumulator "")
                        (reset! last-flush 0)
                        (swap! draft-id rotate-draft-id)
                        (finalize-thinking!)
                        (when-not (str/blank? text)
                          (safe-telegram! system chat-id :draft-finalize
                                          #(send-msg! chat-id text)))))]
      {:on-delta (fn [delta]
                   (swap! accumulator str delta)
                   (flush!))
       :on-thinking-delta (fn [delta]
                            (swap! thinking-accumulator str delta))
       :finalize-thinking! finalize-thinking!
       :finalize! finalize!})))

(defn build-on-tool-call
  [safe-telegram! system opts chat-id stream-controls]
  (let [cfg (tool-display/channel-config system :telegram nil)]
    (when (true? (:show-tool-calls? cfg))
      (let [send! (or (:send-message-fn opts)
                      (fn [cid text]
                        (tg-api/send-html-message! (get-in system [:config :channel-adapters :telegram :bot-token])
                                                   cid text)))
            finalize! (:finalize! stream-controls)]
        (fn [{:keys [receipt]}]
          (safe-telegram! system chat-id :tool-call-summary
                          (fn []
                            (when finalize! (finalize!))
                            (let [text (tool-display/telegram-summary system receipt)]
                              (when-not (str/blank? text)
                                (send! chat-id text))))))))))

(defn start-typing-indicator!
  [safe-telegram! system config opts chat-id]
  (let [token (:bot-token config)
        running? (atom true)
        send-action! (or (:send-chat-action-fn opts)
                         (fn [cid action] (tg-api/send-chat-action! token cid action)))
        worker (future
                 (while @running?
                   (safe-telegram! system chat-id :typing
                                   #(send-action! chat-id "typing"))
                   (Thread/sleep typing-refresh-ms)))]
    (fn []
      (reset! running? false)
      (future-cancel worker))))
