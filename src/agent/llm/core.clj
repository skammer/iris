(ns agent.llm.core
  "Core LLM protocols and interfaces for the agent system.
  Provides abstract interfaces for LLM providers with extended capabilities."
  (:require
   [clojure.spec.alpha :as s]))

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

(s/def ::role #{"system" "user" "assistant" "tool"})
(s/def ::content string?)
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
  "Normalize messages to provider-specific format."
  [messages]
  (mapv (fn [msg]
          (cond-> msg
            (string? (:role msg)) (update :role #(case %
                                                   "system" "system"
                                                   "user" "user" 
                                                   "assistant" "assistant"
                                                   "tool" "tool"
                                                   "user"))
            (not (string? (:content msg))) (update :content str)))
        messages))

(defn count-tokens-estimate
  "Estimate token count for messages (rough approximation).
  Uses 4 chars per token as a rough estimate."
  [messages]
  (let [total-chars (reduce + (map (comp count :content) messages))]
    (int (/ total-chars 4))))

(defn create-completion-request
  "Create standardized completion request."
  [messages {:keys [model temperature max-tokens top-p
                    frequency-penalty presence-penalty]
             :or {temperature 0.7 max-tokens 1000}}]
  {:model model
   :messages (normalize-messages messages)
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

;; ======================
;; Error Handling
;; ======================

(defn llm-error
  "Create an LLM error."
  ([type message] (llm-error type message {}))
  ([type message details]
   (ex-info message (merge {:type type} details))))

(defn retry-with-backoff
  "Retry function with exponential backoff."
  [f & {:keys [max-retries initial-delay max-delay]
        :or {max-retries 3 initial-delay 1000 max-delay 10000}}]
  (loop [retry 0
         delay initial-delay]
    (let [result (try
                   (f)
                   (catch Exception e
                     (if (>= retry max-retries)
                       (throw e)
                       e)))]
      (cond
        (not (instance? Exception result)) result
        :else (do
                (Thread/sleep delay)
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
