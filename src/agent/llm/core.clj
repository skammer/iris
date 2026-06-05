(ns agent.llm.core
  "Core LLM protocols and interfaces for the agent system.
  Provides abstract interfaces for LLM providers with extended capabilities."
  (:require
   [agent.llm.messages :as llm-messages]
   [cheshire.core :as json]
   [clojure.spec.alpha :as s]
   [clojure.string :as str])
  (:import
   [java.time ZonedDateTime]
   [java.time.format DateTimeFormatter]))

;; ======================
;; Extended LLM Protocol
;; ======================

(defprotocol ILLMProvider
  "Protocol for LLM providers with extended capabilities."
  
  (complete [this messages opts]
    "Send messages to LLM and get completion.
    messages: vector of message maps with :role and :content
    opts: map with :model, :temperature, :max-tokens, etc.
    Returns: string completion")
  
  (stream [this messages opts]
    "Stream completion from LLM.
    Returns: core.async channel that will receive streaming chunks")
  
  (embed [this text opts]
    "Get embeddings for text.
    text: string or vector of strings
    opts: map with :model, etc.
    Returns: vector of embeddings")
  
  (list-models [this]
    "List available models from this provider.
    Returns: vector of model information maps")
  
  (get-capabilities [this model]
    "Get capabilities of a specific model.
    model: model identifier string
    Returns: map with :max-tokens, :supports-embedding, etc.")
  
  (estimate-cost [this messages model]
    "Estimate cost for completing messages with model.
    Returns: map with :tokens, :cost-usd, etc."))

(defprotocol ILLMProviderWithTools
  "Deprecated compatibility API. Prefer ILLMProviderInvoke/invoke with :tools."
  (complete-with-tools [this messages tools opts]
    "Complete with provider-native tool definitions.
    Returns structured content/tool-call/usage data."))

(defprotocol ILLMProviderWithCache
  "Optional provider API for prompt-cache controls."
  (with-cache-controls [this request cache-controls]
    "Attach provider-native cache controls to request data."))

(defprotocol ILLMProviderWithUsage
  "Optional provider API for normalized usage extraction."
  (usage [this response opts]
    "Return normalized usage map: prompt/completion/cached tokens and cost."))

(defprotocol ILLMProviderInvoke
  "Normalized expandable LLM request/response API."
  (invoke [this request]
    "Execute normalized request map.
    request keys: :messages, :model, :tools, :tool-choice, :structured-output,
    :cache-control, :modalities, :metadata.
    Returns normalized assistant turn map with :content, :tool-calls, :usage, :raw.")
  (generate [this messages opts]
    "Generate one assistant turn from messages and opts. Returns normalized response map."))

(defprotocol ILLMProviderWithConfig
  "Protocol for providers that support configuration updates."
  
  (update-config [this new-config]
    "Update provider configuration.
    new-config: map with updated configuration values
    Returns: updated provider instance")
  
  (get-config [this]
    "Get current provider configuration.
    Returns: configuration map"))

(defprotocol ILLMProviderWithHealth
  "Protocol for providers that support health checking."
  
  (health-check [this]
    "Check provider health.
    Returns: map with :healthy boolean and :details")
  
  (get-metrics [this]
    "Get provider metrics.
    Returns: map with :requests, :errors, :avg-latency, etc."))

;; ======================
;; Provider Registry
;; ======================

(defprotocol ILLMProviderRegistry
  "Protocol for managing multiple LLM providers."
  
  (register-provider [this name provider]
    "Register a provider with a name.
    name: keyword identifier for the provider
    provider: ILLMProvider instance
    Returns: updated registry")
  
  (get-provider [this name]
    "Get provider by name.
    Returns: provider instance or nil")
  
  (list-providers [this]
    "List all registered providers.
    Returns: map of name->provider-info")
  
  (select-provider [this criteria]
    "Select provider based on criteria.
    criteria: map with :model, :max-cost, :capabilities, etc.
    Returns: provider name and instance")
  
  (remove-provider [this name]
    "Remove provider from registry.
    Returns: updated registry"))

;; ======================
;; Common Types and Specs
;; ======================

(s/def ::role #(contains? #{"system" "user" "assistant" "tool"}
                          (if (keyword? %) (name %) (str %))))
(s/def ::content any?)
(s/def ::message (s/keys :req-un [::role ::content]))
(s/def ::messages (s/coll-of ::message :min-count 1))

(s/def ::model string?)
(s/def ::temperature (s/and double? #(<= 0.0 % 2.0)))
(s/def ::max-tokens pos-int?)
(s/def ::top-p (s/and double? #(<= 0.0 % 1.0)))
(s/def ::frequency-penalty (s/and double? #(<= -2.0 % 2.0)))
(s/def ::presence-penalty (s/and double? #(<= -2.0 % 2.0)))

(s/def ::completion-opts
  (s/keys :opt-un [::model ::temperature ::max-tokens ::top-p
                   ::frequency-penalty ::presence-penalty]))

(s/def ::embedding-opts
  (s/keys :opt-un [::model]))

(s/def ::model-info
  (s/keys :req-un [::model ::name ::description]
          :opt-un [::max-tokens ::supports-embedding ::supports-streaming]))

(s/def ::capabilities
  (s/keys :opt-un [::max-tokens ::supports-embedding ::supports-streaming
                   ::supports-tools ::supports-vision ::supports-audio]))

(s/def ::cost-estimate
  (s/keys :req-un [::tokens ::cost-usd]
          :opt-un [::prompt-tokens ::completion-tokens]))

(s/def ::health-status
  (s/keys :req-un [::healthy]
          :opt-un [::latency-ms ::error-rate ::last-checked]))

(s/def ::provider-metrics
  (s/keys :opt-un [::total-requests ::successful-requests ::failed-requests
                   ::avg-latency-ms ::total-tokens ::total-cost-usd]))

;; ======================
;; Common Utilities
;; ======================

(defn normalize-messages
  "Normalize messages to Iris internal rich format.
   Provider wire conversion happens in provider namespaces."
  [messages]
  (llm-messages/messages->internal messages))

(defn validate-messages?
  [messages]
  (s/valid? ::messages messages))

(def ProviderError :provider-error)
(def ConfigurationError :configuration-error)
(def ConnectionError :connection-error)

(defn count-tokens-estimate
  "Estimate token count for messages (rough approximation).
  Uses 4 chars per token as a rough estimate."
  [messages]
  (let [total-chars (reduce + (map #(count (llm-messages/content-text %)) messages))]
    (int (/ total-chars 4))))

(defn create-completion-request
  "Create standardized completion request."
  [messages {:keys [model temperature max-tokens top-p
                    frequency-penalty presence-penalty]
             :or {temperature 0.7 max-tokens 1000}}]
  {:model model
   :messages (llm-messages/internal->openai-compatible messages)
   :temperature temperature
   :max_tokens max-tokens
   :top_p top-p
   :frequency_penalty frequency-penalty
   :presence_penalty presence-penalty})

(defn create-embedding-request
  "Create standardized embedding request."
  [text {:keys [model] :or {model "text-embedding-ada-002"}}]
  {:model model
   :input (if (string? text) [text] text)})

(defn request->completion-opts
  [request]
  (merge
   (:opts request)
   (select-keys request
                [:model :api :temperature :max-tokens :top-p :frequency-penalty
                 :presence-penalty :tools :tool-choice :structured-output
                 :response-format :cache-control :cache_control :modalities
                 :metadata :extra-body :user :session-id :session_id
                 :stream? :stream :stream-structured-output?
                 :on-content-delta :on-thinking-delta])))

(defn normalize-llm-response
  [response opts]
  (let [response* (cond
                    (map? response) response
                    (string? response) {:content response}
                    :else {:content (str response)})
        turn (llm-messages/provider-response->assistant-turn
              (:provider opts)
              (:model opts)
              (cond-> response*
                (:usage opts) (assoc :usage (:usage opts))))
        tool-calls (llm-messages/tool-call-blocks (:content turn))]
    (-> response*
        (assoc :role "assistant")
        (assoc :content (llm-messages/content-text (:content turn)))
        (assoc :content-blocks (:content turn))
        (assoc :tool-calls tool-calls)
        (assoc :usage (:usage turn))
        (assoc :stop-reason (:stop-reason turn))
        (assoc :assistant-turn turn)
        (assoc :raw (or (:raw response*) response)))))

(declare llm-error)

(defn- parse-tool-arguments [arguments]
  (cond
    (nil? arguments) {}
    (map? arguments) arguments
    (string? arguments) (try
                          (json/parse-string arguments true)
                          (catch Exception _
                            {:arguments arguments}))
    :else {:arguments arguments}))

(defn tool-call->directive
  [tool-call]
  (let [block (llm-messages/provider-tool-call->internal tool-call)
        tool-name (:name block)
        input (parse-tool-arguments (:arguments block))]
    (when-not tool-name
      (throw (llm-error :invalid-tool-call
                        "Provider tool call missing tool name"
                        {:tool-call tool-call})))
    {:type :tool-call
     :payload {:tool-name tool-name
               :input input
               :context (cond-> {:provider-tool-call (or (:raw block) tool-call)}
                          (:id block) (assoc :provider-tool-call-id (:id block)))}}))

(defn tool-calls->directives
  [tool-calls]
  (mapv tool-call->directive (or tool-calls [])))

(defn default-invoke
  [provider {:keys [messages] :as request}]
  (let [opts (request->completion-opts request)
        result (complete provider messages opts)]
    (normalize-llm-response result opts)))

(extend-protocol ILLMProviderInvoke
  Object
  (invoke [this request]
    (default-invoke this request))
  (generate [this messages opts]
    (invoke this (assoc opts :messages messages))))

;; ======================
;; Error Handling
;; ======================

(defn llm-error
  "Create an LLM error."
  ([type message] (llm-error type message {}))
  ([type message details]
   (ex-info message (merge {:type type} details))))

(defn stream-error-event
  [error]
  (cond-> {:type :error
           :error (if (instance? Throwable error)
                    (.getMessage ^Throwable error)
                    (str error))}
    (instance? clojure.lang.ExceptionInfo error)
    (assoc :details (ex-data error))))

(defn- retry-after-ms [headers]
  (when-let [value (or (get headers "Retry-After")
                       (get headers "retry-after"))]
    (or (try
          (* 1000 (Long/parseLong (str/trim value)))
          (catch Exception _ nil))
        (try
          (let [retry-at (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME)
                now (ZonedDateTime/now)]
            (max 0 (.toMillis (java.time.Duration/between now retry-at))))
          (catch Exception _ nil)))))

(defn retryable-status?
  [status]
  (contains? #{429 503} status))

(defn- retryable-exception? [e]
  (retryable-status? (:status (ex-data e))))

(defn retry-with-backoff
  "Retry function with exponential backoff and Retry-After support."
  [f & {:keys [max-retries initial-delay max-delay]
        :or {max-retries 3 initial-delay 1000 max-delay 60000}}]
  (loop [retry 0
         delay initial-delay]
    (let [result (try
                   (f)
                   (catch Exception e
                     (if (or (>= retry max-retries)
                             (not (retryable-exception? e)))
                       (throw e)
                       e)))]
      (cond
        (not (instance? Exception result)) result
        :else (do
                (Thread/sleep (or (retry-after-ms (:headers (ex-data result)))
                                  delay))
                (recur (inc retry)
                       (min (* delay 2) max-delay)))))))

;; ======================
;; Provider Factory
;; ======================

(defmulti create-provider
  "Create LLM provider based on type."
  (fn [type config] type))

(defmethod create-provider :default
  [type config]
  (throw (ex-info (str "Unknown provider type: " type) {:type type :config config})))

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Protocol usage example
  (defprotocol IExampleProvider
    (complete [this messages opts]))
  
  ;; Creating a provider that implements the protocol
  (defrecord ExampleProvider [config]
    ILLMProvider
    (complete [this messages opts]
      "Example implementation"
      (str "Completed: " (count messages) " messages"))
    
    (stream [this messages opts]
      (let [ch (async/chan)]
        (async/go
          (async/>! ch "Streaming not implemented")
          (async/close! ch))
        ch))
    
    (embed [this text opts]
      [0.1 0.2 0.3]) ; Example embedding
    
    (list-models [this]
      [{:model "example-model" :name "Example Model" :max-tokens 1000}])
    
    (get-capabilities [this model]
      {:max-tokens 1000 :supports-embedding true})
    
    (estimate-cost [this messages model]
      {:tokens 100 :cost-usd 0.001}))
  
  ;; Creating and using a provider
  (def example-provider (->ExampleProvider {:api-key "test"}))
  
  (complete example-provider
            [{:role "user" :content "Hello"}]
            {:model "example-model"})
  
  ;; Using specs
  (s/valid? ::messages [{:role "user" :content "Hello"}])
  (s/valid? ::completion-opts {:model "gpt-4" :temperature 0.7})
  
  ;; Error handling
  (try
    (complete example-provider [] {})
    (catch LLMError e
      (println "LLM error:" (.getMessage e))))
  
  ;; Retry with backoff
  (retry-with-backoff
   #(complete example-provider
              [{:role "user" :content "Hello"}]
              {})
   :max-retries 3
   :initial-delay 1000))
