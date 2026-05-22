(ns agent.llm.providers.openai-compatible
  "OpenAI-compatible provider, used for OpenRouter first and other compatible APIs."
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.messages :as llm-messages]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- trim-trailing-slash [value]
  (str/replace (or value "") #"/+$" ""))

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
  (str (trim-trailing-slash base-url) "/chat/completions"))

(defn- embeddings-url [base-url]
  (str (trim-trailing-slash base-url) "/embeddings"))

(defn- models-url [base-url]
  (str (trim-trailing-slash base-url) "/models"))

(defn- structured-output-format [{:keys [name schema strict?]}]
  {:type "json_schema"
   :json_schema {:name (or name "structured_output")
                 :schema schema
                 :strict (not (false? strict?))}})

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
  (and (:structured-output opts)
       (not (false? (if (contains? opts :stream-structured-output?)
                      (:stream-structured-output? opts)
                      (:stream-structured-output? config true))))))

(defn- completion-body [base-url default-model config messages opts]
  (let [model (or (:model opts) default-model)
        extra-body (merge (or (:extra-body config) {})
                          (or (:extra-body opts) {}))]
    (cond-> (merge {:model model
                    :messages (llm-messages/internal->openai-compatible messages)
                    :temperature (or (:temperature opts)
                                     (:temperature config)
                                     0.2)
                    :max_tokens (or (:max-tokens opts)
                                    (:max_tokens opts)
                                    (:max-tokens config)
                                    (:max_tokens config)
                                    1024)
                    :stream false}
                   (prompt-cache-fields base-url model config opts)
                   extra-body)
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

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= "[DONE]" payload)
        (json/parse-string payload true)))))

(defn- usage->estimate [response]
  (let [usage (:usage response)]
    {:tokens (or (:total_tokens usage) 0)
     :prompt-tokens (or (:prompt_tokens usage) 0)
     :completion-tokens (or (:completion_tokens usage) 0)
     :cached-tokens (or (get-in usage [:prompt_tokens_details :cached_tokens]) 0)
     :cost-usd nil}))

(defn- retryable-http-error [response]
  (llm-core/llm-error :http-error
                      (str "LLM request failed: " (:status response))
                      {:status (:status response)
                       :headers (:headers response)
                       :body (:body response)}))

(defn- checked-response [response]
  (if (<= 200 (:status response 0) 299)
    response
    (throw (retryable-http-error response))))

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
  (llm-core/retry-with-backoff
   #(checked-response (http/post url (assoc request :throw-exceptions false)))))

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

(defn- merge-tool-call-deltas [tool-calls deltas]
  ;; OpenAI streams tool_calls as partial deltas keyed by :index. Each delta may
  ;; carry id/type/function.name once and successive function.arguments fragments
  ;; that must be string-concatenated into a complete JSON payload.
  (reduce (fn [acc tc]
            (let [idx (or (:index tc) (count acc))
                  tc-name (get-in tc [:function :name])
                  tc-args (get-in tc [:function :arguments])]
              (update acc idx
                      (fn [existing]
                        (cond-> (or existing {})
                          (:id tc) (assoc :id (:id tc))
                          (:type tc) (assoc :type (:type tc))
                          tc-name (assoc-in [:function :name] tc-name)
                          tc-args (update-in [:function :arguments]
                                             (fnil str "") tc-args))))))
          tool-calls
          deltas))

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

(defn- post-stream-turn
  ([url request] (post-stream-turn url request nil))
  ([url request on-content-delta]
   (stream->turn
    (:body (checked-response
            (http/post url (assoc request
                                  :throw-exceptions false
                                  :as :stream))))
    on-content-delta)))

(def ^:private tool-markup-openers
  ["<｜DSML｜tool_calls>" "<tool_call>"])

(defn- starts-with-tool-markup? [text]
  (let [trimmed (str/triml (str text))]
    (some #(str/starts-with? trimmed %) tool-markup-openers)))

(defn- possible-tool-markup-prefix? [text]
  (let [trimmed (str/triml (str text))]
    (or (str/blank? trimmed)
        (some #(or (str/starts-with? % trimmed)
                   (str/starts-with? trimmed %))
              tool-markup-openers))))

(defn- guarded-content-delta
  [on-content-delta tools]
  (cond
    (nil? on-content-delta) nil
    (empty? tools) on-content-delta
    :else
    (let [mode (atom :undecided)
          buffered (atom "")]
      (fn [chunk]
        (case @mode
          :streaming
          (on-content-delta chunk)

          :suppressing
          nil

          :undecided
          (let [text (swap! buffered str chunk)]
            (cond
              (starts-with-tool-markup? text)
              (reset! mode :suppressing)

              (possible-tool-markup-prefix? text)
              nil

              :else
              (do
                (reset! mode :streaming)
                (on-content-delta text)))))))))

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
    (let [request {:headers (provider-headers this)}]
      (if (stream-structured-output? config opts)
        (:content (post-stream-turn
                   (chat-url base-url)
                   (assoc request
                          :body (json/generate-string
                                 (stream-body base-url
                                              default-model
                                              config
                                              messages
                                              opts)))))
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
    (let [ch (async/chan)]
      (async/thread
        (try
          (let [response (checked-response
                          (http/post (chat-url base-url)
                                     {:headers (provider-headers this)
                                      :body (json/generate-string
                                             (stream-body base-url
                                                          default-model
                                                          config
                                                          messages
                                                          opts))
                                      :throw-exceptions false
                                      :as :stream}))]
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
                        (async/>!! ch content)))))
                (let [{:keys [content? reasoning? finish-reason reasoning-chars content-chars event-count]} @state]
                  (when (and (not content?)
                             (or (= "length" finish-reason) reasoning?))
                    (throw (empty-content-error
                            {:finish-reason finish-reason
                             :reasoning-content? reasoning?
                             :reasoning-chars reasoning-chars
                             :content-chars content-chars
                             :event-count event-count})))))))
          (catch Exception e
            (async/>!! ch (llm-core/stream-error-event e)))
          (finally
            (async/close! ch))))
      ch))

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
    {:model model
     :supports-streaming true
     :supports-embedding true
     :supports-tools true})

  (estimate-cost [_ messages model]
    {:tokens (llm-core/count-tokens-estimate messages)
     :cost-usd nil
     :model model}))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithTools
  (complete-with-tools [this messages tools opts]
    (let [response (post-json (chat-url (:base-url this))
                              {:headers (provider-headers this)
                               :body (json/generate-string
                                      (completion-body (:base-url this)
                                                       (:default-model this)
                                                       (:config this)
                                                       messages
                                                       (assoc opts :tools tools)))
                               :as :json})]
      (message->turn (:body response)))))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderInvoke
  (invoke [this request]
    (let [opts (llm-core/request->completion-opts request)
          stream-with-delta? (some? (:on-content-delta opts))
          on-content-delta (guarded-content-delta (:on-content-delta opts)
                                                  (:tools opts))
          request* {:headers (provider-headers this)}
          response (cond
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
                                                  [:prompt-cache?
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
                       [:prompt-cache?
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
                        :extra-body])
          {:base-url base-url
           :api-key api-key
           :default-model (or model "openai/gpt-4o-mini")
           :site-url site-url
           :app-name app-name
           :config config
           :api-key-resolver api-key-resolver})))
