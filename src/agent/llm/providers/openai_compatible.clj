(ns agent.llm.providers.openai-compatible
  "OpenAI-compatible provider, used for OpenRouter first and other compatible APIs."
  (:require
   [agent.llm.core :as llm-core]
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

(defn- completion-body [default-model messages opts]
  (cond-> (merge {:model (or (:model opts) default-model)
                  :messages (llm-core/normalize-messages messages)
                  :temperature (or (:temperature opts) 0.2)
                  :max_tokens (or (:max-tokens opts) 1024)
                  :stream false}
                 (:extra-body opts))
    (:tools opts) (assoc :tools (:tools opts))
    (:tool-choice opts) (assoc :tool_choice (:tool-choice opts))
    (:structured-output opts) (assoc :response_format (structured-output-format (:structured-output opts)))
    (:response-format opts) (assoc :response_format (:response-format opts))
    (:cache-control opts) (assoc :cache_control (:cache-control opts))
    (:cache_control opts) (assoc :cache_control (:cache_control opts))))

(defn- stream-body [default-model messages opts]
  (assoc (completion-body default-model messages opts) :stream true))

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

(defn- post-json [url request]
  (llm-core/retry-with-backoff
   #(checked-response (http/post url (assoc request :throw-exceptions false)))))

(defn- message->turn [body]
  (let [message (-> body :choices first :message)]
    {:role (:role message "assistant")
     :content (:content message)
     :tool-calls (vec (or (:tool_calls message) []))
     :usage (usage->estimate body)
     :raw message}))

(defrecord OpenAICompatibleProvider [base-url api-key default-model site-url app-name extra-headers config]
  llm-core/ILLMProvider
  (complete [_ messages opts]
    (let [response (post-json (chat-url base-url)
                              {:headers (bearer-headers {:api-key api-key
                                                         :site-url site-url
                                                         :app-name app-name
                                                         :extra-headers extra-headers})
                               :body (json/generate-string (completion-body default-model messages opts))
                               :as :json})]
      (-> response :body :choices first :message :content)))

  (stream [_ messages opts]
    (let [ch (async/chan)]
      (async/thread
        (try
          (let [response (checked-response
                          (http/post (chat-url base-url)
                                     {:headers (bearer-headers {:api-key api-key
                                                                :site-url site-url
                                                                :app-name app-name
                                                                :extra-headers extra-headers})
                                      :body (json/generate-string (stream-body default-model messages opts))
                                      :throw-exceptions false
                                      :as :stream}))]
            (with-open [reader (io/reader (:body response))]
              (doseq [line (line-seq reader)]
                (when-let [event (parse-sse-line line)]
                  (when-let [content (-> event :choices first :delta :content)]
                    (async/>!! ch content))))))
          (catch Exception e
            (async/>!! ch (llm-core/stream-error-event e)))
          (finally
            (async/close! ch))))
      ch))

  (embed [_ text opts]
    (let [input (if (string? text) [text] text)
          response (post-json (embeddings-url base-url)
                              {:headers (bearer-headers {:api-key api-key
                                                         :site-url site-url
                                                         :app-name app-name
                                                         :extra-headers extra-headers})
                               :body (json/generate-string {:model (or (:model opts) default-model)
                                                            :input input})
                               :as :json})
          embeddings (mapv :embedding (-> response :body :data))]
      (if (string? text)
        (first embeddings)
        embeddings)))

  (list-models [_]
    (try
      (let [response (http/get (models-url base-url)
                               {:headers (bearer-headers {:api-key api-key
                                                          :site-url site-url
                                                          :app-name app-name
                                                          :extra-headers extra-headers})
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
                              {:headers (bearer-headers {:api-key (:api-key this)
                                                         :site-url (:site-url this)
                                                         :app-name (:app-name this)
                                                         :extra-headers (:extra-headers this)})
                               :body (json/generate-string
                                      (completion-body (:default-model this)
                                                       messages
                                                       (assoc opts :tools tools)))
                               :as :json})]
      (message->turn (:body response)))))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderInvoke
  (invoke [this request]
    (let [response (post-json (chat-url (:base-url this))
                              {:headers (bearer-headers {:api-key (:api-key this)
                                                         :site-url (:site-url this)
                                                         :app-name (:app-name this)
                                                         :extra-headers (:extra-headers this)})
                               :body (json/generate-string
                                      (completion-body (:default-model this)
                                                       (:messages request)
                                                       (llm-core/request->completion-opts request)))
                               :as :json})]
      (llm-core/normalize-llm-response (message->turn (:body response))
                                       {:usage (usage->estimate (:body response))})))
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
     (merge (:config this) new-config)))

  (get-config [this]
    {:base-url (:base-url this)
     :default-model (:default-model this)
     :site-url (:site-url this)
     :app-name (:app-name this)
     :api-key (when (:api-key this) "***REDACTED***")
     :config (:config this)}))

(extend-type OpenAICompatibleProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [response (http/get (models-url (:base-url this))
                               {:headers (bearer-headers {:api-key (:api-key this)
                                                          :site-url (:site-url this)
                                                          :app-name (:app-name this)
                                                          :extra-headers (:extra-headers this)})
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
  [{:keys [base-url api-key default-model model site-url app-name extra-headers]
    :or {base-url "https://api.openai.com/v1"
         app-name "clj-agent"}}]
  (->OpenAICompatibleProvider base-url
                              api-key
                              (or default-model model "gpt-4o-mini")
                              site-url
                              app-name
                              extra-headers
                              {}))

(defn create-openrouter-provider
  [{:keys [api-key base-url model site-url app-name]
    :or {base-url "https://openrouter.ai/api/v1"
         app-name "clj-agent"}}]
  (create-openai-compatible-provider
   {:base-url base-url
    :api-key api-key
    :default-model (or model "openai/gpt-4o-mini")
    :site-url site-url
    :app-name app-name}))
