(ns agent.chat.title
  "Best-effort title generation for blank sessions after initial messages."
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.llm.messages :as llm-messages]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.sessions.service :as sessions]
   [clojure.string :as str]))

(def max-title-chars 50)
(def max-title-messages 6)
(def max-message-chars 1200)

(defn- blank-title? [session]
  (str/blank? (or (:title session) "")))

(defn- message-line [{:keys [role content]}]
  (let [text (-> (llm-messages/content-text {:role role :content content})
                 (or "")
                 (str/replace #"\s+" " ")
                 str/trim)]
    (when-not (str/blank? text)
      (str (name role) ": "
           (if (> (count text) max-message-chars)
             (str (subs text 0 max-message-chars) "...")
             text)))))

(defn- transcript [messages]
  (->> messages
       (filter #(contains? #{"user" "assistant"} (name (:role %))))
       (take max-title-messages)
       (keep message-line)
       (str/join "\n")))

(defn- strip-wrapping-quotes [title]
  (let [title* (str/trim title)]
    (or (second (re-matches #"^\"(.+)\"$" title*))
        (second (re-matches #"^'(.+)'$" title*))
        title*)))

(defn- clean-title [value]
  (let [title (-> (or value "")
                  str
                  str/split-lines
                  first
                  (or "")
                  strip-wrapping-quotes
                  (str/replace #"\s+" " ")
                  str/trim)]
    (when-not (str/blank? title)
      (if (> (count title) max-title-chars)
        (str (subs title 0 (- max-title-chars 3)) "...")
        title))))

(defn- generate-title! [system session-id messages]
  (let [provider (or (:note-llm-provider system) (:llm-provider system))
        prompt (prompts/render "chat-title" {:transcript (transcript messages)})
        response (llm/invoke provider
                             {:model (config/active-model (config/llm-config (:config system)))
                              :session-id session-id
                              :messages [{:role "system"
                                          :content "Generate a concise chat title. Output only the title."}
                                         {:role "user"
                                          :content prompt}]
                              :max-tokens 32
                              :temperature 0.2
                              :metadata {:chat-title true}})]
    (clean-title (if (map? response)
                   (or (:content response)
                       (llm-messages/content-text response)
                       "")
                   response))))

(defn maybe-generate-title!
  [system session-id request-id]
  (when session-id
    (try
      (let [session (sqlite/get-session (:store system) session-id)]
        (when (blank-title? session)
          (let [messages (sqlite/current-llm-context (:store system) session-id)
                title (when (>= (count (filter #(contains? #{"user" "assistant"}
                                                          (name (:role %)))
                                               messages))
                                2)
                        (generate-title! system session-id messages))]
            (when title
              (sessions/set-title-if-blank! system session-id title)))))
      (catch Exception e
        (when-let [sink (:event-sink system)]
          (sink {:event-type :chat.operation.failed
                 :entity-type :session
                 :entity-id session-id
                 :request-id request-id
                 :payload {:operation :title-generation
                           :message (.getMessage ^Throwable e)}}))))))
