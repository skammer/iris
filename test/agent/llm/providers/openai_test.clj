(ns test.agent.llm.providers.openai-test
  "Tests for OpenAI provider with HTTP mocking."
  (:require
   [clojure.test :refer :all]
   [agent.llm.providers.openai :as openai]
   [clojure.core.async :as async]
   [clj-http.fake :as fake]
   [cheshire.core :as json]))

;; ======================
;; Test Fixtures
;; ======================

(defn with-mocked-http
  "Wrap tests with HTTP mocking."
  [f]
  (fake/with-fake-routes
    {"https://api.openai.com/v1/chat/completions"
     (fn [request]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string
               {:choices [{:message {:content "Mocked OpenAI response"}}]})})}
    
    {"https://api.openai.com/v1/embeddings"
     (fn [request]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string
               {:data [{:embedding [0.1 0.2 0.3 0.4 0.5]}]})})}
    
    {"https://api.openai.com/v1/models"
     (fn [request]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string
               {:data [{:id "gpt-3.5-turbo"
                        :object "model"
                        :created 1677610602
                        :owned_by "openai"}]})})}
    
    (f)))

(defn create-test-provider
  "Create OpenAI provider for testing."
  []
  (openai/create-openai-provider
   {:api-key "test-api-key"
    :base-url "https://api.openai.com/v1"}))

;; ======================
;; Provider Creation Tests
;; ======================

(deftest test-provider-creation
  (testing "Create OpenAI provider with API key"
    (let [provider (openai/create-openai-provider {:api-key "test-key"})]
      (is (some? provider))
      (is (satisfies? agent.llm.core/ILLMProvider provider))))
  
  (testing "Provider creation fails without API key"
    (is (thrown? Exception (openai/create-openai-provider {})))))

;; ======================
;; Completion Tests
;; ======================

(deftest test-completion
  (with-mocked-http
    (fn []
      (testing "Basic completion"
        (let [provider (create-test-provider)
              response (agent.llm.core/complete provider
                                                [{:role "user" :content "Hello"}]
                                                {:model "gpt-3.5-turbo"})]
          (is (string? response))
          (is (= "Mocked OpenAI response" response)))))))

(deftest test-streaming
  (with-mocked-http
    (fn []
      (testing "Stream completion returns channel"
        (let [provider (create-test-provider)
              ch (agent.llm.core/stream provider
                                        [{:role "user" :content "Test"}]
                                        {:model "gpt-3.5-turbo"})]
          (is (some? ch))
          (is (instance? clojure.core.async.impl.channels.ManyToManyChannel ch)))))))

;; ======================
;; Embeddings Tests
;; ======================

(deftest test-embeddings
  (with-mocked-http
    (fn []
      (testing "Single text embedding"
        (let [provider (create-test-provider)
              embedding (agent.llm.core/embed provider "Hello world" {})]
          (is (vector? embedding))
          (is (= 5 (count embedding)))
          (is (every? number? embedding))))
      
      (testing "Multiple text embeddings"
        (let [provider (create-test-provider)
              embeddings (agent.llm.core/embed provider ["Hello" "World"] {})]
          (is (vector? embeddings))
          (is (= 2 (count embeddings)))
          (is (every? vector? embeddings)))))))

;; ======================
;; Model Listing Tests
;; ======================

(deftest test-model-listing
  (with-mocked-http
    (fn []
      (testing "List models"
        (let [provider (create-test-provider)
              models (agent.llm.core/list-models provider)]
          (is (vector? models))
          (is (pos? (count models)))
          (is (every? #(contains? % :id) models)))))))

;; ======================
;; Capabilities Tests
;; ======================

(deftest test-capabilities
  (testing "Get model capabilities"
    (let [provider (create-test-provider)
          capabilities (agent.llm.core/get-capabilities provider "gpt-3.5-turbo")]
      (is (map? capabilities))
      (is (contains? capabilities :max-tokens))
      (is (contains? capabilities :supports-chat))
      (is (contains? capabilities :model-type))))
  
  (testing "Unknown model capabilities"
    (let [provider (create-test-provider)
          capabilities (agent.llm.core/get-capabilities provider "unknown-model")]
      (is (nil? capabilities)))))

;; ======================
;; Cost Estimation Tests
;; ======================

(deftest test-cost-estimation
  (testing "Estimate cost for messages"
    (let [provider (create-test-provider)
          messages [{:role "user" :content "Hello, how are you?"}
                    {:role "assistant" :content "I'm doing well, thank you!"}]
          estimate (agent.llm.core/estimate-cost provider messages "gpt-3.5-turbo")]
      (is (map? estimate))
      (is (contains? estimate :tokens))
      (is (contains? estimate :cost-usd))
      (is (number? (:tokens estimate)))
      (is (number? (:cost-usd estimate))))))

;; ======================
;; Configuration Tests
;; ======================

(deftest test-configuration
  (testing "Get provider configuration"
    (let [provider (create-test-provider)
          config (agent.llm.core/get-config provider)]
      (is (map? config))
      (is (contains? config :api-key))
      (is (contains? config :base-url))))
  
  (testing "Update provider configuration"
    (let [provider (create-test-provider)
          updated (agent.llm.core/update-config provider {:timeout-ms 30000})
          config (agent.llm.core/get-config updated)]
      (is (contains? config :timeout-ms))
      (is (= 30000 (:timeout-ms config))))))

;; ======================
;; Health Check Tests
;; ======================

(deftest test-health-check
  (with-mocked-http
    (fn []
      (testing "Health check with successful API call"
        (let [provider (create-test-provider)
              health (agent.llm.core/health-check provider)]
          (is (map? health))
          (is (contains? health :healthy))
          (is (true? (:healthy health))))))))

;; ======================
;; Factory Function Tests
;; ======================

(deftest test-factory-functions
  (testing "Create provider by type"
    (let [provider (openai/create-provider :openai {:api-key "test-key"})]
      (is (some? provider))
      (is (satisfies? agent.llm.core/ILLMProvider provider))))
  
  (testing "Unsupported provider type"
    (is (thrown? Exception (openai/create-provider :unsupported {:api-key "test"})))))

;; ======================
;; Error Handling Tests
;; ======================

(deftest test-error-handling
  (fake/with-fake-routes
    {"https://api.openai.com/v1/chat/completions"
     (fn [request]
       {:status 429
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string
               {:error {:message "Rate limit exceeded"
                        :type "rate_limit_error"}})})}
    
    (testing "Rate limit error handling"
      (let [provider (create-test-provider)]
        (is (thrown? Exception
                     (agent.llm.core/complete provider
                                              [{:role "user" :content "Test"}]
                                              {})))))))

;; ======================
;; Integration Tests
;; ======================

(deftest test-provider-integration
  (with-mocked-http
    (fn []
      (testing "Complete workflow"
        (let [provider (create-test-provider)]
          ;; List models
          (let [models (agent.llm.core/list-models provider)]
            (is (vector? models)))
          
          ;; Get capabilities
          (let [capabilities (agent.llm.core/get-capabilities provider "gpt-3.5-turbo")]
            (is (map? capabilities)))
          
          ;; Complete
          (let [response (agent.llm.core/complete provider
                                                  [{:role "user" :content "Hello"}]
                                                  {})]
            (is (string? response)))
          
          ;; Health check
          (let [health (agent.llm.core/health-check provider)]
            (is (map? health))))))))

(comment
  ;; Run tests
  (run-tests 'test.agent.llm.providers.openai-test)
  
  ;; Manual testing
  (require '[agent.llm.providers.openai :as openai]
           '[clj-http.fake :as fake])
  
  ;; Create test provider
  (def test-provider (openai/create-openai-provider {:api-key "test-key"}))
  
  ;; Test with mocked HTTP
  (fake/with-fake-routes
    {"https://api.openai.com/v1/chat/completions"
     (fn [request]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body "{\"choices\":[{\"message\":{\"content\":\"Test response\"}}]}"})}
    
    (agent.llm.core/complete test-provider
                             [{:role "user" :content "Hello"}]
                             {})))