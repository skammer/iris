(ns agent.telegram.tool-summary
  "LLM-generated titles for Telegram tool-call batches."
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm]
   [agent.llm.messages :as llm-messages]
   [clojure.string :as str]))

(def ^:private max-request-chars 1200)
(def ^:private max-purpose-chars 240)
(def ^:private max-title-chars 80)
(def ^:private max-receipts 12)

(defn- truncate [value n]
  (let [s (str (or value ""))]
    (if (> (count s) n) (subs s 0 n) s)))

(defn- clean-title [value]
  (let [title (-> (or value "")
                  str
                  str/split-lines
                  first
                  (or "")
                  (str/replace #"^[\"'«]+|[\"'»]+$" "")
                  (str/replace #"\s+" " ")
                  str/trim)]
    (when-not (str/blank? title)
      (truncate title max-title-chars))))

(defn- fallback-title [user-request]
  (if (re-find #"[А-Яа-яЁё]" (or user-request ""))
    "Выполнил вспомогательные действия"
    "Completed supporting work"))

(defn- receipt-line [{:keys [tool-name status input reason]}]
  (str "- tool=" (or (some-> tool-name name) "unknown")
       " status=" (or (some-> status name) "unknown")
       (when-let [purpose (some-> (or (:purpose input) (get input "purpose"))
                                   str str/trim not-empty)]
         (str " purpose=" (pr-str (truncate purpose max-purpose-chars))))
       (when-let [reason* (some-> reason str str/trim not-empty)]
         (str " error=" (pr-str (truncate reason* max-purpose-chars))))))

(defn generate-title
  "Return a short activity title in the original request's language. Failures
   are non-fatal; caller keeps its deterministic fallback."
  [system session-id user-request receipts]
  (or
   (when-let [provider (or (:note-llm-provider system) (:llm-provider system))]
     (try
       (let [request* (truncate user-request max-request-chars)
             activity (str/join "\n" (map receipt-line (take max-receipts receipts)))
             response (llm/invoke
                       provider
                       {:model (config/active-model (config/llm-config (:config system)))
                        :session-id session-id
                        :messages
                        [{:role "system"
                          :content (str "Write a natural title for a collapsed tool-activity block. "
                                        "Use the same language as the user request. Describe completed activity, "
                                        "not implementation. Use 2-8 words, past tense, no tool names, counts, "
                                        "quotes, labels, or final punctuation. Output only the title.")}
                         {:role "user"
                          :content (str "User request:\n" request*
                                        "\n\nTool activity:\n" activity)}]
                        :max-tokens 40
                        :temperature 0.2
                        :metadata {:telegram-tool-summary-title true}})]
         (clean-title (if (map? response)
                        (or (:content response)
                            (llm-messages/content-text response)
                            "")
                        response)))
       (catch Exception _ nil)))
   (fallback-title user-request)))
