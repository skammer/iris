(ns agent.llm.providers.openai-compatible.stream
  "SSE parsers for OpenAI-compatible providers."
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.messages :as llm-messages]
   [agent.llm.providers.openai-compatible.parse :as parse]
   [agent.llm.providers.openai-compatible.usage :as usage]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn parse-sse-line [line]
  (when (str/starts-with? line "data:")
    (let [payload (str/triml (subs line 5))]
      (when-not (= "[DONE]" payload)
        (json/parse-string payload true)))))

(defn emit-thinking-delta! [on-thinking-delta chunk]
  (when (and on-thinking-delta (string? chunk) (not= "" chunk))
    (on-thinking-delta chunk)))

(defn merge-tool-call-deltas [tool-calls deltas]
  ;; OpenAI streams tool_calls as partial deltas keyed by :index. Each delta may
  ;; carry id/type/function.name once and successive function.arguments fragments
  ;; that must be string-concatenated into a complete JSON payload.
  (let [fresh-call? (fn [tc]
                      (or (:id tc)
                          (get-in tc [:function :name])))]
    (:tool-calls
     (reduce (fn [{:keys [tool-calls last-index] :as state} tc]
               (let [idx (or (:index tc)
                             (when (fresh-call? tc) (count tool-calls))
                             last-index
                             (count tool-calls))
                     tc-name (get-in tc [:function :name])
                     tc-args (get-in tc [:function :arguments])]
                 (-> state
                     (assoc :last-index idx)
                     (update :tool-calls update idx
                             (fn [existing]
                               (cond-> (or existing {})
                                 (:id tc) (assoc :id (:id tc))
                                 (:type tc) (assoc :type (:type tc))
                                 tc-name (assoc-in [:function :name] tc-name)
                                 tc-args (update-in [:function :arguments]
                                                    (fnil str "") tc-args)))))))
             {:tool-calls tool-calls
              :last-index (some-> (last tool-calls) key)}
             deltas))))

(defn stream->turn
  ([body-stream] (stream->turn body-stream nil))
  ([body-stream on-content-delta] (stream->turn body-stream on-content-delta nil))
  ([body-stream on-content-delta on-thinking-delta]
   (with-open [reader (io/reader body-stream)]
     (loop [content []
            reasoning []
            tool-calls (sorted-map)
            usage nil
            raw []
            finish-reason nil
            reasoning-chars 0
            event-count 0]
       (if-let [line (.readLine reader)]
         (if-let [event (parse-sse-line line)]
           (let [delta (-> event :choices first :delta)
                 choice (-> event :choices first)
                 chunk (:content delta)
                 reasoning-chunk (llm-messages/reasoning-text delta)]
             (when (and on-content-delta (string? chunk) (not= "" chunk))
               (on-content-delta chunk))
             (emit-thinking-delta! on-thinking-delta reasoning-chunk)
             (recur (cond-> content chunk (conj chunk))
                    (cond-> reasoning reasoning-chunk (conj reasoning-chunk))
                    (if-let [tc-deltas (:tool_calls delta)]
                      (merge-tool-call-deltas tool-calls tc-deltas)
                      tool-calls)
                    (or (:usage event) usage)
                    (conj raw event)
                    (or (:finish_reason choice) finish-reason)
                    (+ reasoning-chars (count (or reasoning-chunk "")))
                    (inc event-count)))
           (recur content reasoning tool-calls usage raw finish-reason reasoning-chars event-count))
         (let [turn (dsml/recover-tool-calls
                     (cond-> {:role "assistant"
                              :content (apply str content)
                              :tool-calls (vec (vals tool-calls))
                              :usage (usage/chat->estimate {:usage usage})
                              :raw raw}
                       (seq reasoning) (assoc :reasoning-content (apply str reasoning))))]
           (parse/throw-empty-content! (:content turn)
                                       (:tool-calls turn)
                                       {:finish-reason finish-reason
                                        :reasoning-content? (pos? reasoning-chars)
                                        :reasoning-chars reasoning-chars
                                        :event-count event-count
                                        :usage (usage/chat->estimate {:usage usage})})
           turn))))))

(defn responses-stream->turn
  ([body-stream] (responses-stream->turn body-stream nil))
  ([body-stream on-content-delta] (responses-stream->turn body-stream on-content-delta nil))
  ([body-stream on-content-delta on-thinking-delta]
   (with-open [reader (io/reader body-stream)]
     (loop [content []
            reasoning []
            output-items (sorted-map)
            final-response nil
            failed-response nil
            event-count 0]
       (if-let [line (.readLine reader)]
         (if-let [event (parse-sse-line line)]
           (let [event-type (:type event)
                 chunk (case event-type
                         "response.output_text.delta" (:delta event)
                         "response.refusal.delta" (:delta event)
                         nil)
                 reasoning-chunk (when (and (string? event-type)
                                            (str/includes? event-type "reasoning")
                                            (string? (:delta event)))
                                   (:delta event))]
             (when (and on-content-delta (string? chunk) (not= "" chunk))
               (on-content-delta chunk))
             (emit-thinking-delta! on-thinking-delta reasoning-chunk)
             (recur (cond-> content chunk (conj chunk))
                    (cond-> reasoning reasoning-chunk (conj reasoning-chunk))
                    (if (= "response.output_item.done" event-type)
                      (assoc output-items (:output_index event) (:item event))
                      output-items)
                    (if (= "response.completed" event-type)
                      (:response event)
                      final-response)
                    (if (or (= "response.failed" event-type)
                            (= "error" event-type))
                      (or (:response event) event)
                      failed-response)
                    (inc event-count)))
           (recur content reasoning output-items final-response failed-response event-count))
         (let [body (cond
                      final-response final-response
                      failed-response failed-response
                      :else {:output (vec (vals output-items))
                             :usage nil
                             :status "completed"})
               error (or (:error failed-response)
                         (:error body))]
           (when error
             (throw (llm-core/llm-error :provider-error
                                        (or (:message error)
                                            (:message body)
                                            "LLM response failed")
                                        {:response body
                                         :event-count event-count})))
           (let [usable-output? (or (seq (remove str/blank?
                                                  (keep parse/responses-output-text (:output body))))
                                    (seq (keep parse/responses-tool-call (:output body))))
                 body* (if usable-output?
                         body
                         (assoc body :output [{:type "message"
                                               :role "assistant"
                                               :content [{:type "output_text"
                                                          :text (apply str content)}]}]))]
             (cond-> (parse/responses->turn body*)
               (seq reasoning) (assoc :reasoning-content (apply str reasoning))))))))))
