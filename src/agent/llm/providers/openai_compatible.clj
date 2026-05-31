(ns agent.llm.providers.openai-compatible
  "OpenAI-compatible provider, used for OpenRouter first and other compatible APIs."
  (:require
   [agent.defaults :as defaults]
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.messages :as llm-messages]
   [agent.llm.providers.common :as provider-common]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- bearer-headers [{:keys [api-key site-url app-name extra-headers]}]
  (merge {"Content-Type" "application/json"}
         (when api-key
           {"Authorization" (str "Bearer " api-key)})
         (when site-url
           {"HTTP-Referer" site-url})
         (when app-name
           {"X-Title" app-name})
         extra-headers))

(defn- chat-url [base-url]
  (provider-common/endpoint base-url "/chat/completions"))

(defn- responses-url [base-url]
  (provider-common/endpoint base-url "/responses"))

(defn- embeddings-url [base-url]
  (provider-common/endpoint base-url "/embeddings"))

(defn- models-url [base-url]
  (provider-common/endpoint base-url "/models"))

(defn- structured-output-format [{:keys [name schema strict?]}]
  {:type "json_schema"
   :json_schema {:name (or name "structured_output")
                 :schema schema
                 :strict (not (false? strict?))}})

(defn- responses-output-format [{:keys [name schema strict?]}]
  {:format {:type "json_schema"
            :name (or name "structured_output")
            :schema schema
            :strict (not (false? strict?))}})

(def ^:private api-aliases
  {nil :chat-completions
   :responses :responses
   :response :responses
   :chat :chat-completions
   :chat-completions :chat-completions
   :chat-completion :chat-completions
   :completions :chat-completions})

(defn- normalize-api [value]
  (let [value* (cond
                 (keyword? value) value
                 (string? value) (keyword (str/lower-case value))
                 :else value)]
    (or (api-aliases value*)
        (throw (ex-info (str "Unsupported OpenAI-compatible API: " value)
                        {:type :unsupported-openai-compatible-api
                         :api value})))))

(defn- responses-api? [config opts]
  (= :responses (normalize-api (or (:api opts)
                                   (:api config)))))

(defn- provider-kind [base-url]
  (let [url (str/lower-case (or base-url ""))]
    (cond
      (str/includes? url "openrouter.ai") :openrouter
      (str/includes? url "api.openai.com") :openai
      :else :openai-compatible)))

(defn- anthropic-model? [model]
  (let [value (str/lower-case (or model ""))]
    (or (str/starts-with? value "anthropic/")
        (str/includes? value "claude"))))

(defn- capability
  [model-cfg key default]
  (if (contains? model-cfg key)
    (true? (get model-cfg key))
    default))

(defn- prompt-cache-enabled? [config opts]
  (not (false? (if (contains? opts :prompt-cache?)
                 (:prompt-cache? opts)
                 (:prompt-cache? config true)))))

(defn- prompt-cache-fields [base-url model config opts]
  (if-not (prompt-cache-enabled? config opts)
    {}
    (let [kind (provider-kind base-url)
          cache-control (or (:cache-control opts)
                            (:cache_control opts)
                            (:cache-control config)
                            (:cache_control config)
                            {:type "ephemeral"})
          retention (or (:prompt-cache-retention opts)
                        (:prompt_cache_retention opts)
                        (:prompt-cache-retention config)
                        (:prompt_cache_retention config)
                        "in_memory")]
      (cond
        (or (:cache-control opts) (:cache_control opts))
        {:cache_control cache-control}

        (and (= :openrouter kind) (anthropic-model? model))
        {:cache_control cache-control}

        (= :openai kind)
        {:prompt_cache_retention retention}

        :else {}))))

(defn- stream-structured-output? [config opts]
  (provider-common/stream-structured-output? config opts))

(defn- request-user [config opts]
  (when-let [value (or (:user opts)
                       (:session-id opts)
                       (:session_id opts)
                       (:user config))]
    (let [value* (str value)]
      (when-not (str/blank? value*)
        value*))))

(defn- completion-body [base-url default-model config messages opts]
  (let [model (or (:model opts) default-model)
        user (request-user config opts)
        extra-body (merge (or (:extra-body config) {})
                          (or (:extra-body opts) {}))]
    (cond-> (merge {:model model
                    :messages (llm-messages/internal->openai-compatible messages)
                    :temperature (or (:temperature opts)
                                     (:temperature config)
                                     defaults/llm-temperature)
                    :max_tokens (or (:max-tokens opts)
                                    (:max_tokens opts)
                                    (:max-tokens config)
                                    (:max_tokens config)
                                    defaults/llm-max-tokens)
                    :stream false}
                   (prompt-cache-fields base-url model config opts)
                   extra-body)
      user (assoc :user user)
      (:tools opts) (assoc :tools (:tools opts))
      (:tool-choice opts) (assoc :tool_choice (:tool-choice opts))
      (:structured-output opts) (assoc :response_format (structured-output-format (:structured-output opts)))
      (:response-format opts) (assoc :response_format (:response-format opts))
      (:cache-control opts) (assoc :cache_control (:cache-control opts))
      (:cache_control opts) (assoc :cache_control (:cache_control opts)))))

(defn- stream-body [base-url default-model config messages opts]
  (cond-> (assoc (completion-body base-url default-model config messages opts) :stream true)
    (or (:structured-output opts) (:include-usage? opts true))
    (assoc :stream_options {:include_usage true})))

(defn- responses-tool [tool]
  (if (and (= "function" (:type tool)) (:function tool))
    (let [function (:function tool)]
      (merge (select-keys tool [:type :strict])
             (select-keys function [:name :description :parameters :strict])))
    tool))

(defn- responses-tool-choice [choice]
  (if (and (map? choice)
           (= "function" (:type choice))
           (:function choice))
    {:type "function"
     :name (get-in choice [:function :name])}
    choice))

(defn- responses-text-format [format]
  (cond
    (nil? format) nil
    (:format format) format
    :else {:format format}))

(defn- responses-body [base-url default-model config messages opts]
  (let [model (or (:model opts) default-model)
        user (request-user config opts)
        extra-body (merge (or (:extra-body config) {})
                          (or (:extra-body opts) {}))]
    (cond-> (merge {:model model
                    :input (llm-messages/internal->openai-responses messages)
                    :temperature (or (:temperature opts)
                                     (:temperature config)
                                     defaults/llm-temperature)
                    :max_output_tokens (or (:max-tokens opts)
                                           (:max_tokens opts)
                                           (:max-tokens config)
                                           (:max_tokens config)
                                           defaults/llm-max-tokens)
                    :stream false}
                   (prompt-cache-fields base-url model config opts)
                   extra-body)
      user (assoc :user user)
      (:tools opts) (assoc :tools (mapv responses-tool (:tools opts)))
      (:tool-choice opts) (assoc :tool_choice (responses-tool-choice (:tool-choice opts)))
      (:structured-output opts) (assoc :text (responses-output-format (:structured-output opts)))
      (:response-format opts) (assoc :text (responses-text-format (:response-format opts))))))

(defn- responses-stream-body [base-url default-model config messages opts]
  (assoc (responses-body base-url default-model config messages opts) :stream true))

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= "[DONE]" payload)
        (json/parse-string payload true)))))

(defn- cached-tokens [usage]
  (or (get-in usage [:prompt_tokens_details :cached_tokens])
      (get-in usage [:input_tokens_details :cached_tokens])
      (get-in usage [:cache_tokens_details :cached_tokens])
      (:cached_tokens usage)
      (:cache_read_input_tokens usage)
      (:prompt_cache_read_tokens usage)
      (:prompt_cache_hit_tokens usage)
      0))

(defn- usage->estimate [response]
  (let [usage (:usage response)]
    {:tokens (or (:total_tokens usage) 0)
     :prompt-tokens (or (:prompt_tokens usage) 0)
     :completion-tokens (or (:completion_tokens usage) 0)
     :cached-tokens (cached-tokens usage)
     :cost-usd nil}))

(defn- responses-usage->estimate [response]
  (let [usage (:usage response)]
    {:tokens (or (:total_tokens usage) 0)
     :prompt-tokens (or (:input_tokens usage) 0)
     :completion-tokens (or (:output_tokens usage) 0)
     :cached-tokens (cached-tokens usage)
     :cost-usd nil}))

(defn- retryable-http-error [response]
  (provider-common/http-error (str "LLM request failed: " (:status response))
                              response))

(defn- blank-content? [content]
  (or (nil? content)
      (and (string? content) (str/blank? content))))

(defn- empty-content-error [details]
  (llm-core/llm-error
   :empty-response
   (if (= "length" (:finish-reason details))
     "LLM response ended before final content (finish_reason=length); increase :max-tokens or disable reasoning"
     "LLM response had no assistant content")
   details))

(defn- throw-empty-content!
  ([content tool-calls finish-reason reasoning?]
   (throw-empty-content! content tool-calls
                         {:finish-reason finish-reason
                          :reasoning-content? (boolean reasoning?)}))
  ([content tool-calls {:keys [finish-reason reasoning-content?] :as details}]
   (when (and (blank-content? content)
              (empty? tool-calls)
              (or (= "length" finish-reason) reasoning-content?))
     (throw (empty-content-error (assoc details
                                        :content-chars (count (or content ""))
                                        :tool-call-count (count tool-calls)))))))

(defn- post-json [url request]
  (provider-common/post-json url request retryable-http-error))

(defn- post-stream [url request]
  (provider-common/post-stream url request retryable-http-error))

(defn- message->turn [body]
  (let [choice (-> body :choices first)
        message (:message choice)
        turn (dsml/recover-tool-calls
              {:role (:role message "assistant")
               :content (:content message)
               :tool-calls (vec (or (:tool_calls message) []))
               :usage (usage->estimate body)
               :raw message})]
    (throw-empty-content! (:content turn)
                          (:tool-calls turn)
                          {:finish-reason (:finish_reason choice)
                           :reasoning-content? (some? (:reasoning_content message))
                           :reasoning-chars (count (or (:reasoning_content message) ""))
                           :usage (usage->estimate body)})
    turn))

(defn- responses-output-text [item]
  (when (= "message" (:type item))
    (apply str
           (keep (fn [part]
                   (case (:type part)
                     "output_text" (:text part)
                     "refusal" (:refusal part)
                     nil))
                 (:content item)))))

(defn- responses-tool-call [item]
  (when (= "function_call" (:type item))
    {:id (:call_id item)
     :type "function"
     :function {:name (:name item)
                :arguments (:arguments item)}
     :raw item}))

(defn- responses->turn [body]
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
        usage (responses-usage->estimate body)
        incomplete? (= "incomplete" (:status body))
        turn (dsml/recover-tool-calls
              {:role "assistant"
               :content content
               :tool-calls tool-calls
               :usage usage
               :raw body})]
    (throw-empty-content! (:content turn)
                          (:tool-calls turn)
                          {:finish-reason (when incomplete? "length")
                           :reasoning-content? false
                           :usage usage})
    turn))

(defn- merge-tool-call-deltas [tool-calls deltas]
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

(defn- stream->turn
  ([body-stream] (stream->turn body-stream nil))
  ([body-stream on-content-delta]
   (with-open [reader (io/reader body-stream)]
     (loop [content []
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
                 reasoning-chunk (:reasoning_content delta)]
             (when (and on-content-delta (string? chunk) (not= "" chunk))
               (on-content-delta chunk))
             (recur (cond-> content
                      chunk (conj chunk))
                    (if-let [tc-deltas (:tool_calls delta)]
                      (merge-tool-call-deltas tool-calls tc-deltas)
                      tool-calls)
                    (or (:usage event) usage)
                    (conj raw event)
                    (or (:finish_reason choice) finish-reason)
                    (+ reasoning-chars (count (or reasoning-chunk "")))
                    (inc event-count)))
           (recur content tool-calls usage raw finish-reason reasoning-chars event-count))
         (let [turn (dsml/recover-tool-calls
                     {:role "assistant"
                      :content (apply str content)
                      :tool-calls (vec (vals tool-calls))
                      :usage (usage->estimate {:usage usage})
                      :raw raw})]
           (throw-empty-content! (:content turn)
                                 (:tool-calls turn)
                                 {:finish-reason finish-reason
                                  :reasoning-content? (pos? reasoning-chars)
                                  :reasoning-chars reasoning-chars
                                  :event-count event-count
                                  :usage (usage->estimate {:usage usage})})
           turn))))))

(defn- responses-stream->turn
  ([body-stream] (responses-stream->turn body-stream nil))
  ([body-stream on-content-delta]
   (with-open [reader (io/reader body-stream)]
     (loop [content []
            output-items (sorted-map)
            final-response nil
            failed-response nil
            raw []
            event-count 0]
       (if-let [line (.readLine reader)]
         (if-let [event (parse-sse-line line)]
           (let [event-type (:type event)
                 chunk (case event-type
                         "response.output_text.delta" (:delta event)
                         "response.refusal.delta" (:delta event)
                         nil)]
             (when (and on-content-delta (string? chunk) (not= "" chunk))
               (on-content-delta chunk))
             (recur (cond-> content chunk (conj chunk))
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
                    (conj raw event)
                    (inc event-count)))
           (recur content output-items final-response failed-response raw event-count))
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
                                                  (keep responses-output-text (:output body))))
                                    (seq (keep responses-tool-call (:output body))))
                 body* (if usable-output?
                         body
                         (assoc body :output [{:type "message"
                                               :role "assistant"
                                               :content [{:type "output_text"
                                                          :text (apply str content)}]}]))
                 turn (responses->turn body*)]
             (assoc turn :stream-events raw))))))))

(defn- post-stream-turn
  ([url request] (post-stream-turn url request nil))
  ([url request on-content-delta]
   (stream->turn
    (:body (post-stream url request))
    on-content-delta)))

(defn- post-responses-stream-turn
  ([url request] (post-responses-stream-turn url request nil))
  ([url request on-content-delta]
   (responses-stream->turn
    (:body (post-stream url request))
    on-content-delta)))

(defn- current-api-key [provider]
  (or (when-let [resolver (:api-key-resolver provider)]
        (resolver provider))
      (:api-key provider)))

(defn- provider-headers [provider]
  (bearer-headers {:api-key (current-api-key provider)
                   :site-url (:site-url provider)
                   :app-name (:app-name provider)
                   :extra-headers (:extra-headers provider)}))

(defrecord OpenAICompatibleProvider [base-url api-key default-model site-url app-name extra-headers config api-key-resolver]
  llm-core/ILLMProvider
  (complete [this messages opts]
    (let [request {:headers (provider-headers this)}
          responses? (responses-api? config opts)]
      (cond
        (and responses? (stream-structured-output? config opts))
        (:content (post-responses-stream-turn
                   (responses-url base-url)
                   (assoc request
                          :body (json/generate-string
                                 (responses-stream-body base-url
                                                        default-model
                                                        config
                                                        messages
                                                        opts)))))

        responses?
        (let [response (post-json (responses-url base-url)
                                  (assoc request
                                         :body (json/generate-string
                                                (responses-body base-url
                                                                default-model
                                                                config
                                                                messages
                                                                opts))
                                         :as :json))]
          (:content (responses->turn (:body response))))

        (stream-structured-output? config opts)
        (:content (post-stream-turn
                   (chat-url base-url)
                   (assoc request
                          :body (json/generate-string
                                 (stream-body base-url
                                              default-model
                                              config
                                              messages
                                              opts)))))

        :else
        (let [response (post-json (chat-url base-url)
                                  (assoc request
                                         :body (json/generate-string
                                                (completion-body base-url
                                                                 default-model
                                                                 config
                                                                 messages
                                                                 opts))
                                         :as :json))]
          (:content (message->turn (:body response)))))))

  (stream [this messages opts]
    (provider-common/stream-channel
     (fn [emit!]
       (if (responses-api? config opts)
         (let [response (post-stream
                         (responses-url base-url)
                         {:headers (provider-headers this)
                          :body (json/generate-string
                                 (responses-stream-body base-url
                                                        default-model
                                                        config
                                                        messages
                                                        opts))})]
           (responses-stream->turn (:body response) emit!))
         (let [response (post-stream
                         (chat-url base-url)
                         {:headers (provider-headers this)
                          :body (json/generate-string
                                 (stream-body base-url
                                              default-model
                                              config
                                              messages
                                              opts))})]
           (with-open [reader (io/reader (:body response))]
             (let [state (atom {:content? false
                                :reasoning? false
                                :finish-reason nil
                                :content-chars 0
                                :reasoning-chars 0
                                :event-count 0})]
               (doseq [line (line-seq reader)]
                 (when-let [event (parse-sse-line line)]
                   (let [choice (-> event :choices first)
                         delta (:delta choice)]
                     (swap! state
                            (fn [s]
                              (cond-> (-> s
                                          (update :event-count inc)
                                          (update :content-chars + (count (or (:content delta) "")))
                                          (update :reasoning-chars + (count (or (:reasoning_content delta) ""))))
                                (:finish_reason choice) (assoc :finish-reason (:finish_reason choice))
                                (some? (:reasoning_content delta)) (assoc :reasoning? true))))
                     (when-let [content (:content delta)]
                       (when-not (str/blank? content)
                         (swap! state assoc :content? true))
                       (emit! content)))))
               (let [{:keys [content? reasoning? finish-reason reasoning-chars content-chars event-count]} @state]
                 (when (and (not content?)
                            (or (= "length" finish-reason) reasoning?))
                   (throw (empty-content-error
                           {:finish-reason finish-reason
                            :reasoning-content? reasoning?
                            :reasoning-chars reasoning-chars
                            :content-chars content-chars
                            :event-count event-count})))))))))))

  (embed [this text opts]
    (let [input (if (string? text) [text] text)
          response (post-json (embeddings-url base-url)
                              {:headers (provider-headers this)
                               :body (json/generate-string {:model (or (:model opts) default-model)
                                                            :input input})
                               :as :json})
          embeddings (mapv :embedding (-> response :body :data))]
      (if (string? text)
        (first embeddings)
        embeddings)))

  (list-models [this]
    (try
      (let [response (http/get (models-url base-url)
                               {:headers (provider-headers this)
                                :as :json})]
        (vec (or (-> response :body :data) [])))
      (catch Exception _
        [])))

  (get-capabilities [_ model]
    (let [model-cfg (get-in config [:models model] {})]
      {:model model
       :supports-streaming (capability model-cfg :supports-streaming true)
       :supports-embedding (capability model-cfg :supports-embedding false)
       :supports-tools (capability model-cfg :supports-tools true)
       :supports-vision (capability model-cfg :supports-vision false)
       :supports-audio (capability model-cfg :supports-audio false)
       :supports-video (capability model-cfg :supports-video false)
       :supports-files (capability model-cfg :supports-files false)}))

  (estimate-cost [_ messages model]
    {:tokens (llm-core/count-tokens-estimate messages)
     :cost-usd nil
     :model model}))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithTools
  (complete-with-tools [this messages tools opts]
    (if (responses-api? (:config this) opts)
      (let [response (post-json (responses-url (:base-url this))
                                {:headers (provider-headers this)
                                 :body (json/generate-string
                                        (responses-body (:base-url this)
                                                        (:default-model this)
                                                        (:config this)
                                                        messages
                                                        (assoc opts :tools tools)))
                                 :as :json})]
        (responses->turn (:body response)))
      (let [response (post-json (chat-url (:base-url this))
                                {:headers (provider-headers this)
                                 :body (json/generate-string
                                        (completion-body (:base-url this)
                                                         (:default-model this)
                                                         (:config this)
                                                         messages
                                                         (assoc opts :tools tools)))
                                 :as :json})]
        (message->turn (:body response))))))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderInvoke
  (invoke [this request]
    (let [opts (llm-core/request->completion-opts request)
          stream-with-delta? (some? (:on-content-delta opts))
          on-content-delta (dsml/guard-content-delta (:on-content-delta opts)
                                                     (:tools opts))
          responses? (responses-api? (:config this) opts)
          request* {:headers (provider-headers this)}
          response (cond
                     (and responses? stream-with-delta?)
                     (post-responses-stream-turn
                      (responses-url (:base-url this))
                      (assoc request*
                             :body (json/generate-string
                                    (responses-stream-body (:base-url this)
                                                           (:default-model this)
                                                           (:config this)
                                                           (:messages request)
                                                           opts)))
                      on-content-delta)

                     (and responses? (stream-structured-output? (:config this) opts))
                     (post-responses-stream-turn
                      (responses-url (:base-url this))
                      (assoc request*
                             :body (json/generate-string
                                    (responses-stream-body (:base-url this)
                                                           (:default-model this)
                                                           (:config this)
                                                           (:messages request)
                                                           opts))))

                     responses?
                     (let [response* (post-json
                                      (responses-url (:base-url this))
                                      (assoc request*
                                             :body (json/generate-string
                                                    (responses-body (:base-url this)
                                                                    (:default-model this)
                                                                    (:config this)
                                                                    (:messages request)
                                                                    opts))
                                             :as :json))]
                       (responses->turn (:body response*)))

                     stream-with-delta?
                     (post-stream-turn
                      (chat-url (:base-url this))
                      (assoc request*
                             :body (json/generate-string
                                    (stream-body (:base-url this)
                                                 (:default-model this)
                                                 (:config this)
                                                 (:messages request)
                                                 opts)))
                      on-content-delta)

                     (stream-structured-output? (:config this) opts)
                     (post-stream-turn
                      (chat-url (:base-url this))
                      (assoc request*
                             :body (json/generate-string
                                    (stream-body (:base-url this)
                                                 (:default-model this)
                                                 (:config this)
                                                 (:messages request)
                                                 opts))))

                     :else
                     (let [response* (post-json
                                      (chat-url (:base-url this))
                                      (assoc request*
                                             :body (json/generate-string
                                                    (completion-body (:base-url this)
                                                                     (:default-model this)
                                                                     (:config this)
                                                                     (:messages request)
                                                                     opts))
                                             :as :json))]
                       (message->turn (:body response*))))]
      (llm-core/normalize-llm-response response
                                       {:usage (:usage response)})))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithCache
  (with-cache-controls [_ request cache-controls]
    (assoc request :cache_control cache-controls)))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithUsage
  (usage [_ response _opts]
    (usage->estimate response)))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithConfig
  (update-config [this new-config]
    (->OpenAICompatibleProvider
     (or (:base-url new-config) (:base-url this))
     (or (:api-key new-config) (:api-key this))
     (or (:default-model new-config) (:default-model this))
     (or (:site-url new-config) (:site-url this))
     (or (:app-name new-config) (:app-name this))
     (or (:extra-headers new-config) (:extra-headers this))
     (merge (:config this) new-config)
     (or (:api-key-resolver new-config) (:api-key-resolver this))))

  (get-config [this]
    {:base-url (:base-url this)
     :default-model (:default-model this)
     :site-url (:site-url this)
     :app-name (:app-name this)
     :api-key (when (:api-key this) "***REDACTED***")
     :api-key-resolver? (boolean (:api-key-resolver this))
     :config (:config this)}))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [response (http/get (models-url (:base-url this))
                               {:headers (provider-headers this)
                                :throw-exceptions false
                                :as :json})]
        {:healthy (= 200 (:status response))
         :details {:status (:status response)}})
      (catch Exception e
        {:healthy false
         :details {:error (.getMessage e)}})))

  (get-metrics [_]
    {:provider :openai-compatible}))

(defn create-openai-compatible-provider
  [{:keys [base-url api-key default-model model site-url app-name extra-headers config api-key-resolver]
    :as opts
    :or {base-url "https://api.openai.com/v1"
         app-name "iris"}}]
  (->OpenAICompatibleProvider base-url
                              api-key
                              (or default-model model "gpt-4o-mini")
                              site-url
                              app-name
                              extra-headers
                              (merge (select-keys opts
                                                  [:api
                                                   :prompt-cache?
                                                   :prompt-cache-retention
                                                   :prompt_cache_retention
                                                   :cache-control
                                                   :cache_control
                                                   :stream-structured-output?
                                                   :temperature
                                                   :max-tokens
                                                   :max_tokens
                                                   :top-p
                                                   :top_p
                                                   :user
                                                   :extra-body])
                                     config)
                              api-key-resolver))

(defn create-openrouter-provider
  [{:keys [api-key base-url model site-url app-name config api-key-resolver]
    :as opts
    :or {base-url "https://openrouter.ai/api/v1"
         app-name "iris"}}]
  (create-openai-compatible-provider
   (merge (select-keys opts
                       [:api
                        :prompt-cache?
                        :prompt-cache-retention
                        :prompt_cache_retention
                        :cache-control
                        :cache_control
                        :stream-structured-output?
                        :temperature
                        :max-tokens
                        :max_tokens
                        :top-p
                        :top_p
                        :user
                        :extra-body])
          {:base-url base-url
           :api-key api-key
           :default-model (or model "openai/gpt-4o-mini")
           :site-url site-url
           :app-name app-name
           :config config
           :api-key-resolver api-key-resolver})))
