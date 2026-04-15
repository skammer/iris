(ns agent.llm.providers.anthropic
  "Anthropic Claude provider implementation."
  (:require
   [agent.llm.core :as llm-core]
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.net URLEncoder)))

;; ======================
;; Anthropic Provider
;; ======================

(defrecord AnthropicProvider [api-key base-url version config]
  llm-core/ILLMProvider
  (complete [this messages opts]
    (let [url (str (or base-url "https://api.anthropic.com") "/v1/messages")
          body {:model (or (:model opts) "claude-3-opus-20240229")
                :messages (llm-core/normalize-messages messages)
                :max_tokens (or (:max-tokens opts) 1000)
                :temperature (or (:temperature opts) 0.7)
                :system (get-in opts [:system :content])}
          response (http/post url
                              {:headers {"x-api-key" api-key
                                         "anthropic-version" (or version "2023-06-01")
                                         "Content-Type" "application/json"}
                               :body (json/generate-string body)
                               :as :json})]
      (-> response :body :content first :text)))
  
  (stream [this messages opts]
    (let [ch (async/chan)
          url (str (or base-url "https://api.anthropic.com") "/v1/messages")
          body {:model (or (:model opts) "claude-3-opus-20240229")
                :messages (llm-core/normalize-messages messages)
                :max_tokens (or (:max-tokens opts) 1000)
                :temperature (or (:temperature opts) 0.7)
                :system (get-in opts [:system :content])
                :stream true}]
      (async/go
        (try
          (let [response (http/post url
                                    {:headers {"x-api-key" api-key
                                               "anthropic-version" (or version "2023-06-01")
                                               "Content-Type" "application/json"}
                                     :body (json/generate-string body)
                                     :as :stream})]
            ;; Simplified streaming - would parse SSE in real implementation
            (async/>! ch (str "Streaming Claude response for: " (count messages) " messages"))
            (async/close! ch))
          (catch Exception e
            (async/>! ch (str "Error: " (.getMessage e)))
            (async/close! ch))))
      ch))
  
  (embed [this text opts]
    ;; Anthropic doesn't have a separate embedding API as of 2024
    ;; Use completion-based embedding or return nil
    (throw (llm-core/llm-error :not-supported
                               "Anthropic doesn't provide separate embedding API"
                               {:provider :anthropic})))
  
  (list-models [this]
    [{:model "claude-3-opus-20240229"
      :name "Claude 3 Opus"
      :description "Most powerful Claude model"
      :max-tokens 200000
      :supports-embedding false
      :supports-streaming true}
     {:model "claude-3-sonnet-20240229"
      :name "Claude 3 Sonnet"
      :description "Balanced Claude model"
      :max-tokens 200000
      :supports-embedding false
      :supports-streaming true}
     {:model "claude-3-haiku-20240307"
      :name "Claude 3 Haiku"
      :description "Fastest Claude model"
      :max-tokens 200000
      :supports-embedding false
      :supports-streaming true}])
  
  (get-capabilities [this model]
    (let [model-info (first (filter #(= (:model %) model) (list-models this)))]
      (if model-info
        {:max-tokens (:max-tokens model-info)
         :supports-embedding (:supports-embedding model-info)
         :supports-streaming (:supports-streaming model-info)
         :supports-tools true
         :supports-vision true
         :supports-audio false}
        {:max-tokens 200000
         :supports-embedding false
         :supports-streaming true
         :supports-tools true
         :supports-vision true
         :supports-audio false})))
  
  (estimate-cost [this messages model]
    (let [tokens (llm-core/count-tokens-estimate messages)
          ;; Rough cost estimates per 1K tokens (as of 2024)
          cost-per-1k (case model
                        "claude-3-opus-20240229" 0.075
                        "claude-3-sonnet-20240229" 0.003
                        "claude-3-haiku-20240307" 0.00025
                        0.003) ; Default to Sonnet pricing
          cost-usd (* (/ tokens 1000.0) cost-per-1k)]
      {:tokens tokens
       :cost-usd cost-usd
       :prompt-tokens tokens
       :completion-tokens 0}))

  llm-core/ILLMProviderWithConfig
  (update-config [this new-config]
    (->AnthropicProvider
     (or (:api-key new-config) api-key)
     (or (:base-url new-config) base-url)
     (or (:version new-config) version)
     (merge config new-config)))
  
  (get-config [this]
    {:api-key (if api-key "***REDACTED***" nil)
     :base-url base-url
     :version version
     :config config})

  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [start-time (System/currentTimeMillis)
            response (http/get (str (or base-url "https://api.anthropic.com") "/v1/models")
                               {:headers {"x-api-key" api-key
                                          "anthropic-version" (or version "2023-06-01")}
                                :throw-exceptions false})
            latency (- (System/currentTimeMillis) start-time)]
        {:healthy (= 200 (:status response))
         :latency-ms latency
         :status (:status response)
         :last-checked (System/currentTimeMillis)})
      (catch Exception e
        {:healthy false
         :error (.getMessage e)
         :last-checked (System/currentTimeMillis)})))
  
  (get-metrics [this]
    ;; In real implementation, would track metrics over time
    {:total-requests 0
     :successful-requests 0
     :failed-requests 0
     :avg-latency-ms 0
     :total-tokens 0
     :total-cost-usd 0.0}))

;; ======================
;; Factory Functions
;; ======================

(defn create-anthropic-provider
  "Create an Anthropic Claude provider instance.
  Options:
  - :api-key (required, or ANTHROPIC_API_KEY env var)
  - :base-url (optional, for custom endpoints)
  - :version (optional, Anthropic API version)
  - :config (optional, additional configuration)"
  [opts]
  (let [api-key (or (:api-key opts)
                    (System/getenv "ANTHROPIC_API_KEY")
                    (throw (ex-info "Anthropic API key required" {})))]
    (->AnthropicProvider api-key
                         (:base-url opts)
                         (:version opts "2023-06-01")
                         (dissoc opts :api-key :base-url :version))))

;; ======================
;; Utility Functions
;; ======================

(defn anthropic-messages->openai
  "Convert Anthropic message format to OpenAI format."
  [messages]
  (mapv (fn [msg]
          (case (:role msg)
            "user" {:role "user" :content (:content msg)}
            "assistant" {:role "assistant" :content (:content msg)}
            {:role "user" :content (:content msg)}))
        messages))

(defn openai-messages->anthropic
  "Convert OpenAI message format to Anthropic format."
  [messages]
  (mapv (fn [msg]
          (case (:role msg)
            "system" {:role "user" :content (str "System: " (:content msg))}
            "user" {:role "user" :content (:content msg)}
            "assistant" {:role "assistant" :content (:content msg)}
            "tool" {:role "user" :content (str "Tool result: " (:content msg))}
            {:role "user" :content (:content msg)}))
        messages))

(defn with-tools
  "Add tool definitions to Anthropic request.
  Anthropic uses a different tool format than OpenAI."
  [request tools]
  (if (seq tools)
    (assoc request :tools (mapv (fn [tool]
                                  {:name (:name tool)
                                   :description (:description tool)
                                   :input_schema (:parameters tool)})
                                tools))
    request))

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Create provider
  (def anthropic (create-anthropic-provider
                  {:api-key "sk-ant-..."
                   :base-url "https://api.anthropic.com"
                   :version "2023-06-01"}))
  
  ;; Simple completion
  (llm-core/complete anthropic
                     [{:role "user" :content "Hello, Claude!"}]
                     {:model "claude-3-haiku-20240307"
                      :temperature 0.7})
  
  ;; With system message
  (llm-core/complete anthropic
                     [{:role "system" :content "You are a helpful assistant."}
                      {:role "user" :content "What is Clojure?"}]
                     {:model "claude-3-sonnet-20240229"})
  
  ;; Streaming
  (let [ch (llm-core/stream anthropic
                            [{:role "user" :content "Tell me a story"}]
                            {:model "claude-3-opus-20240229"})]
    (async/<!! ch))
  
  ;; List models
  (llm-core/list-models anthropic)
  
  ;; Get capabilities
  (llm-core/get-capabilities anthropic "claude-3-opus-20240229")
  
  ;; Estimate cost
  (llm-core/estimate-cost anthropic
                          [{:role "user" :content "Hello"}]
                          "claude-3-sonnet-20240229")
  
  ;; Health check
  (llm-core/health-check anthropic)
  
  ;; Update configuration
  (def updated-anthropic (llm-core/update-config anthropic
                                                 {:base-url "https://custom.anthropic.com"}))
  
  ;; Get configuration
  (llm-core/get-config anthropic)
  
  ;; Error handling
  (try
    (llm-core/embed anthropic "Hello world" {})
    (catch agent.llm.core.LLMError e
      (println "Expected error:" (.getMessage e))))
  
  ;; Using retry
  (llm-core/retry-with-backoff
   #(llm-core/complete anthropic
                       [{:role "user" :content "Hello"}]
                       {})
   :max-retries 3
   :initial-delay 1000))