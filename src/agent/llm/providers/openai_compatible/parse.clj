(ns agent.llm.providers.openai-compatible.parse
  "Response parsers for OpenAI-compatible providers."
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.providers.openai-compatible.usage :as usage]
   [clojure.string :as str]))

(defn blank-content? [content]
  (or (nil? content)
      (and (string? content) (str/blank? content))))

(defn empty-content-error [details]
  (llm-core/llm-error
   :empty-response
   (if (= "length" (:finish-reason details))
     "LLM response ended before final content (finish_reason=length); increase :max-tokens or disable reasoning"
     "LLM response had no assistant content")
   details))

(defn throw-empty-content!
  [content tool-calls {:keys [finish-reason reasoning-content?] :as details}]
  (when (and (blank-content? content)
             (empty? tool-calls)
             (or (= "length" finish-reason) reasoning-content?))
    (throw (empty-content-error (assoc details
                                       :content-chars (count (or content ""))
                                       :tool-call-count (count tool-calls))))))

(defn message->turn [body]
  (let [choice (-> body :choices first)
        message (:message choice)
        turn (dsml/recover-tool-calls
              (cond-> {:role (:role message "assistant")
                       :content (:content message)
                       :tool-calls (vec (or (:tool_calls message) []))
                       :usage (usage/chat->estimate body)
                       :raw message}
                (:finish_reason choice)
                (assoc :stop-reason (:finish_reason choice))
                (some? (:reasoning_content message))
                (assoc :reasoning-content (:reasoning_content message))))]
    (throw-empty-content! (:content turn)
                          (:tool-calls turn)
                          {:finish-reason (:finish_reason choice)
                           :reasoning-content? (some? (:reasoning_content message))
                           :reasoning-chars (count (or (:reasoning_content message) ""))
                           :usage (usage/chat->estimate body)})
    turn))

(defn responses-output-text [item]
  (when (= "message" (:type item))
    (apply str
           (keep (fn [part]
                   (case (:type part)
                     "output_text" (:text part)
                     "refusal" (:refusal part)
                     nil))
                 (:content item)))))

(defn responses-tool-call [item]
  (when (= "function_call" (:type item))
    {:id (:call_id item)
     :type "function"
     :function {:name (:name item)
                :arguments (:arguments item)}
     :raw item}))

(defn responses->turn [body]
  (when (or (:error body) (= "failed" (:status body)))
    (let [error (:error body)]
      (throw (llm-core/llm-error :provider-error
                                 (or (:message error)
                                     (:message body)
                                     "LLM response failed")
                                 {:response body}))))
  (let [output (vec (or (:output body) []))
        content (apply str (keep responses-output-text output))
        tool-calls (vec (keep responses-tool-call output))
        usage (usage/responses->estimate body)
        incomplete? (= "incomplete" (:status body))
        turn (dsml/recover-tool-calls
              {:role "assistant"
               :content content
               :tool-calls tool-calls
               :usage usage
               :raw body
               :stop-reason (when incomplete? "length")})]
    (throw-empty-content! (:content turn)
                          (:tool-calls turn)
                          {:finish-reason (when incomplete? "length")
                           :reasoning-content? false
                           :usage usage})
    turn))
