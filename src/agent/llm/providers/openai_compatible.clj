(ns agent.llm.providers.openai-compatible
  "OpenAI-compatible provider, used for OpenRouter first and other compatible APIs."
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.providers.common :as provider-common]
   [agent.llm.providers.openai-compatible.parse :as parse]
   [agent.llm.providers.openai-compatible.request :as request]
   [agent.llm.providers.openai-compatible.stream :as stream]
   [cheshire.core :as json]
   [clj-http.client :as http]))

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

(defn- capability
  [model-cfg key default]
  (if (contains? model-cfg key)
    (true? (get model-cfg key))
    default))

(defn- retryable-http-error [response]
  (provider-common/http-error (str "LLM request failed: " (:status response))
                              response))

(defn- post-json [url request]
  (provider-common/post-json url request retryable-http-error))

(defn- post-stream [url request]
  (provider-common/post-stream url request retryable-http-error))

(defn- post-stream-turn
  [url request on-content-delta on-thinking-delta]
  (stream/stream->turn
   (:body (post-stream url request))
   on-content-delta
   on-thinking-delta))

(defn- post-responses-stream-turn
  [url request on-content-delta on-thinking-delta]
  (stream/responses-stream->turn
   (:body (post-stream url request))
   on-content-delta
   on-thinking-delta))

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
    (llm-core/complete-via-invoke this messages opts))

  (stream [this messages opts]
    (llm-core/stream-via-invoke this messages opts))

  (embed [this text opts]
    (let [input (if (string? text) [text] text)
          response (post-json (embeddings-url base-url)
                              (provider-common/with-transport-options
                               {:headers (provider-headers this)
                                :body (json/generate-string {:model (or (:model opts) default-model)
                                                             :input input})
                                :as :json}
                               config
                               opts))
          embeddings (mapv :embedding (-> response :body :data))]
      (if (string? text)
        (first embeddings)
        embeddings)))

  (list-models [this]
    (try
      (let [request (provider-common/with-transport-options
                     {:headers (provider-headers this)
                      :as :json}
                     config
                     {})
            response (http/get (models-url base-url)
                               (provider-common/http-request-options request))]
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
  llm-core/ILLMProviderInvoke
  (invoke [this request]
    (let [opts (llm-core/request->completion-opts request)
          stream-with-delta? (or (some? (:on-content-delta opts))
                                 (some? (:on-thinking-delta opts)))
          stream-request? (or (request/request-stream? (:config this) opts)
                              (provider-common/stream-structured-output? (:config this) opts))
          on-content-delta (dsml/guard-content-delta (:on-content-delta opts)
                                                     (:tools opts))
          on-thinking-delta (:on-thinking-delta opts)
          responses? (request/responses-api? (:config this) opts)
          request* (provider-common/with-transport-options {:headers (provider-headers this)}
                                                           (:config this)
                                                           opts)
          response (cond
                     (and responses? (or stream-with-delta? stream-request?))
                     (post-responses-stream-turn
                      (responses-url (:base-url this))
                      (assoc request*
                             :body (json/generate-string
                                    (request/responses-stream-body (:base-url this)
                                                                   (:default-model this)
                                                                   (:config this)
                                                                   (:messages request)
                                                                   opts)))
                      on-content-delta
                      on-thinking-delta)

                     responses?
                     (let [response* (post-json
                                      (responses-url (:base-url this))
                                      (assoc request*
                                             :body (json/generate-string
                                                    (request/responses-body (:base-url this)
                                                                           (:default-model this)
                                                                           (:config this)
                                                                           (:messages request)
                                                                           opts))
                                             :as :json))]
                       (parse/responses->turn (:body response*)))

                     (or stream-with-delta? stream-request?)
                     (post-stream-turn
                      (chat-url (:base-url this))
                      (assoc request*
                             :body (json/generate-string
                                    (request/stream-body (:base-url this)
                                                         (:default-model this)
                                                         (:config this)
                                                         (:messages request)
                                                         opts)))
                      on-content-delta
                      on-thinking-delta)

                     :else
                     (let [response* (post-json
                                      (chat-url (:base-url this))
                                      (assoc request*
                                             :body (json/generate-string
                                                    (request/completion-body (:base-url this)
                                                                             (:default-model this)
                                                                             (:config this)
                                                                             (:messages request)
                                                                             opts))
                                             :as :json))]
                       (parse/message->turn (:body response*))))]
      (llm-core/normalize-llm-response response
                                       {:usage (:usage response)})))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [request (provider-common/with-transport-options
                     {:headers (provider-headers this)
                      :throw-exceptions false
                      :as :json}
                     (:config this)
                     {})
            response (http/get (models-url (:base-url this))
                               (provider-common/http-request-options request))]
        {:healthy (= 200 (:status response))
         :details {:status (:status response)}})
      (catch Exception e
        {:healthy false
         :details {:error (.getMessage e)}}))))

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
                                                   :stream?
                                                   :stream-structured-output?
                                                   :temperature
                                                   :max-tokens
                                                   :max_tokens
                                                   :timeout-ms
                                                   :max-retries
                                                   :initial-delay
                                                   :max-delay
                                                   :top-p
                                                   :top_p
                                                   :structured-output-format
                                                   :structured_output_format
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
                        :stream?
                        :stream-structured-output?
                        :temperature
                        :max-tokens
                        :max_tokens
                        :timeout-ms
                        :max-retries
                        :initial-delay
                        :max-delay
                        :top-p
                        :top_p
                        :structured-output-format
                        :structured_output_format
                        :user
                        :extra-headers
                        :extra-body])
          {:base-url base-url
           :api-key api-key
           :default-model (or model "openai/gpt-4o-mini")
           :site-url site-url
           :app-name app-name
           :config config
           :api-key-resolver api-key-resolver})))

(defn create-deepseek-provider
  [{:keys [base-url] :as opts}]
  (create-openai-compatible-provider
   (merge {:base-url (or base-url "https://api.deepseek.com/v1")
           :structured-output-format :json-object}
          opts)))
