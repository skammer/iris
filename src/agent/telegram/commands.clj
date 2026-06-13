(ns agent.telegram.commands
  "Telegram slash command handling."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.skills :as skills]
   [agent.telegram.api :as tg-api]
   [agent.telegram.sessions :as telegram-sessions]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(defn parse-args [text]
  (let [parts (str/split text #"\s+" 2)]
    {:command (str/lower-case (first parts))
     :rest (or (second parts) "")}))

(defn- memory-status [system session-id]
  (let [notes (sqlite/count-vault-notes (:store system))
        chunks (sqlite/count-vault-chunks (:store system))
        messages (count (sqlite/list-messages (:store system) session-id))]
    (str "Memory: " notes " vault notes, " chunks " chunks, "
         messages " session messages.")))

(defn- status-text [system session-id]
  (str "OK. Session: " session-id
       ". Tools: " (count (:tools (tools/registry-health (:tool-registry system))))))

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

(defn invoked-skill-names [system text]
  (let [catalog (set (map :name (skills/skill-catalog (:skills-registry system))))]
    (->> (skills/parse-invoked-skill-names text)
         (filter catalog)
         vec)))

(defn response
  [system chat command-text]
  (when (str/starts-with? command-text "/")
    (let [mapping (telegram-sessions/ensure-session! (:store system) chat)
          session-id (:session-id mapping)
          {:keys [command rest]} (parse-args command-text)]
      (case command
        "/start" "Ready. Send message to chat."
        "/help" "/start /help /stop /reset /memory /status /prompt [name|off] /loop [prompt|status|stop|run|plan] /skills [prefix] /photo <url> [caption] /file <url> [caption]"
        "/reset" (do
                   (telegram-sessions/reset-session! (:store system) chat)
                   "Session reset.")
        "/memory" (memory-status system session-id)
        "/status" (status-text system session-id)
        "/prompt" (prompt-command-response (:store system) session-id rest)
        "/skills" (skills-command-response system rest)
        nil))))

(defn- split-caption [s]
  (let [parts (str/split s #"\s+" 2)
        url (first parts)
        caption (some-> (second parts) str/trim)]
    [url (when-not (str/blank? caption) caption)]))

(defn handle-media!
  [{:keys [bot-token]} {:keys [send-photo-fn send-document-fn send-message-fn]} chat-id text]
  (let [{:keys [command rest]} (parse-args text)
        send-message! (or send-message-fn #(tg-api/send-message! bot-token %1 %2))]
    (case command
      "/photo"
      (let [[url caption] (split-caption rest)]
        (if (str/blank? url)
          (send-message! chat-id "Usage: /photo <url> [caption]")
          (try
            ((or send-photo-fn #(tg-api/send-photo! bot-token %1 %2 %3)) chat-id url caption)
            (catch Exception e
              (send-message! chat-id (str "Photo send failed: " (.getMessage e))))))
        true)

      "/file"
      (let [[url caption] (split-caption rest)]
        (if (str/blank? url)
          (send-message! chat-id "Usage: /file <url> [caption]")
          (try
            ((or send-document-fn #(tg-api/send-document! bot-token %1 %2 %3)) chat-id url caption)
            (catch Exception e
              (send-message! chat-id (str "Document send failed: " (.getMessage e))))))
        true)

      nil)))
