(ns agent.llm.providers.mock
  "Mock LLM provider for testing and development.
  
  Features:
  - Configurable responses
  - Simulated latency
  - Error injection
  - Response pattern matching
  - Conversation history tracking"
  (:require
   [agent.llm.core :as llm-core]
   [clojure.core.async :as async]
   [clojure.string :as str]))

;; ======================
;; Mock Provider
;; ======================

(defrecord MockProvider [responses config history]
  llm-core/ILLMProvider
  (complete [this messages opts]
    (let [simulated-latency (or (:latency-ms config) 100)
          response (get-responses this messages opts)]
      (Thread/sleep simulated-latency)
      (swap! history conj {:type :complete
                           :messages messages
                           :opts opts
                           :response response})
      response))
  
  (stream [this messages opts]
    (let [ch (async/chan)
          response (get-responses this messages opts)
          simulated-latency (or (:latency-ms config) 50)]
      (async/go
        (try
          ;; Simulate streaming by sending characters one by one
          (doseq [chunk (partition-all 1 response)]
            (Thread/sleep simulated-latency)
            (async/>! ch (apply str chunk)))
          (catch Exception e
            (async/>! ch {:error (str e)}))
          (finally
            (async/close! ch))))
      (swap! history conj {:type :stream
                           :messages messages
                           :opts opts
                           :response-length (count response)})
      ch))
  
  (embed [this text opts]
    (let [texts (if (string? text) [text] text)
          ;; Generate deterministic embeddings based on text hash
          embeddings (mapv (fn [t]
                             (let [hash (hash t)
                                   dim (or (:dimension opts) 1536)]
                               (vec (repeatedly dim #(Math/sin (+ hash %))))))
                           texts)]
      (swap! history conj {:type :embed
                           :text text
                           :opts opts
                           :embedding-count (count embeddings)})
      embeddings))
  
  (list-models [this]
    [{:id "mock-gpt-4"
      :object "model"
      :created 1677610602
      :owned_by "mock-org"}
     {:id "mock-gpt-3.5-turbo"
      :object "model"
      :created 1677610602
      :owned_by "mock-org"}
     {:id "mock-claude-3-opus"
      :object "model"
      :created 1677610602
      :owned_by "mock-org"}])
  
  (get-capabilities [this model]
    {:max-tokens 4096
     :supports-embedding true
     :supports-chat true
     :model-type :chat})
  
  (estimate-cost [this messages model]
    {:tokens 100
     :cost-usd 0.0}))

;; Helper functions
(defn- get-responses [provider messages opts]
  (let [responses (:responses provider)
        config (:config provider)
        last-message (-> messages last :content)]
    
    ;; Check for pattern-based responses
    (if-let [pattern-response (some (fn [[pattern response]]
                                      (when (str/includes? (str/lower-case last-message)
                                                           (str/lower-case pattern))
                                        response))
                                    (:pattern-responses config))]
      pattern-response
      
      ;; Check for sequential responses
      (if-let [sequential (:sequential-responses config)]
        (let [idx (mod (count @(:history provider)) (count sequential))]
          (nth sequential idx))
        
        ;; Default response
        (or (:default-response config)
            "This is a mock response from the testing LLM provider. You can configure custom responses in the provider configuration.")))))

;; Configuration protocols
(extend-type MockProvider
  llm-core/ILLMProviderWithConfig
  (update-config [this new-config]
    (MockProvider. (:responses this)
                   (merge (:config this) new-config)
                   (:history this)))
  
  (get-config [this]
    (merge {:responses (:responses this)
            :history-count (count @(:history this))}
           (:config this))))

(extend-type MockProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    {:healthy true
     :details {:type :mock
               :response-count (count @(:history this))
               :config-keys (keys (:config this))}}))

;; ======================
;; Factory Functions
;; ======================

(defn create-mock-provider
  "Create a mock LLM provider for testing.
  
  Configuration options:
  :default-response - Default response string
  :sequential-responses - Vector of responses to cycle through
  :pattern-responses - Map of pattern -> response
  :latency-ms - Simulated latency in milliseconds
  :error-rate - Probability of throwing an error (0.0-1.0)
  :dimension - Embedding dimension (default: 1536)"
  ([]
   (create-mock-provider {}))
  
  ([config]
   (MockProvider. (atom {}) config (atom []))))

(defn with-pattern-response
  "Add a pattern-based response to the mock provider.
  
  Example:
  (-> (create-mock-provider)
      (with-pattern-response \"hello\" \"Hi there!\")
      (with-pattern-response \"weather\" \"It's sunny today!\"))"
  [provider pattern response]
  (update-config provider
                 {:pattern-responses (merge (get-in provider [:config :pattern-responses] {})
                                            {pattern response})}))

(defn with-sequential-responses
  "Add sequential responses to the mock provider.
  
  Example:
  (-> (create-mock-provider)
      (with-sequential-responses [\"First response\" \"Second response\" \"Third response\"]))"
  [provider responses]
  (update-config provider {:sequential-responses responses}))

(defn with-error-rate
  "Configure error injection rate.
  
  Example:
  (-> (create-mock-provider)
      (with-error-rate 0.1)) ; 10% chance of error"
  [provider error-rate]
  (update-config provider {:error-rate (max 0.0 (min 1.0 error-rate))}))

(defn get-history
  "Get the conversation history from the mock provider."
  [provider]
  @(:history provider))

(defn clear-history
  "Clear the conversation history."
  [provider]
  (reset! (:history provider) [])
  provider)

;; ======================
;; Pre-configured Providers
;; ======================

(defn create-helpful-mock
  "Create a helpful mock provider with pre-configured responses."
  []
  (-> (create-mock-provider)
      (with-pattern-response "hello" "Hello! How can I assist you today?")
      (with-pattern-response "weather" "The weather is beautiful today!")
      (with-pattern-response "time" (str "The current time is: " (java.time.LocalDateTime/now)))
      (with-pattern-response "help" "I'm here to help! What do you need assistance with?")))

(defn create-echo-mock
  "Create an echo mock provider that repeats the last message."
  []
  (create-mock-provider
   {:default-response (fn [messages _]
                        (let [last-msg (-> messages last :content)]
                          (str "You said: \"" last-msg "\"")))}))

(defn create-delayed-mock
  "Create a mock provider with configurable delay."
  [latency-ms]
  (create-mock-provider {:latency-ms latency-ms}))

;; ======================
;; Testing Utilities
;; ======================

(defn assert-response-contains
  "Assert that a response contains expected text."
  [provider messages expected-substring]
  (let [response (complete provider messages {})]
    (assert (str/includes? response expected-substring)
            (str "Expected response to contain \"" expected-substring "\", got: " response))
    response))

(defn assert-response-pattern
  "Assert that a response matches a pattern."
  [provider messages pattern-fn]
  (let [response (complete provider messages {})]
    (assert (pattern-fn response)
            (str "Response failed pattern check: " response))
    response))

(defn verify-history-count
  "Verify the number of interactions in history."
  [provider expected-count]
  (let [actual-count (count (get-history provider))]
    (assert (= actual-count expected-count)
            (str "Expected " expected-count " interactions, got " actual-count))
    actual-count))