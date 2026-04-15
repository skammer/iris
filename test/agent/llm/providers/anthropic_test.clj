(ns test.agent.llm.providers.anthropic-test
  "Tests for Anthropic provider with HTTP mocking."
  (:require
   [clojure.test :refer :all]
   [agent.llm.providers.anthropic :as anthropic]
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
    {"https://api.anthropic.com/v1/messages"
     (fn [request]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body (json/generate-string
               {:content [{:text "Mocked Anthropic response"}]})})}
    
    (f)))

(defn create-test-provider
  "Create Anthropic provider for testing."
  []
  (anthropic/create-anthropic-provider
   {:api-key "test-api-key"
    :version "2023-06-01"}))

;; ======================
;; Provider Creation Tests
;; ======================

(deftest test-provider-creation
  (testing "Create Anthropic provider with API key"
    (let [provider (anthropic/create-anthropic-provider {:api-key "test-key"})]
      (is (some? provider))
      (is (satisfies? agent.llm.core/ILLMProvider provider))))
  
  (testing "Provider creation with custom configuration"
    (let [provider (anthropic/create-anthropic-provider
                    {:api-key "test-key"
                     :base-url "https://custom.anthropic.com"
                     :version "2024-01-01"})]
      (is (some? provider)))))

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
                                                {:model "claude-3-opus-20240229"})]
          (is (string? response))
          (is (= "Mocked Anthropic response" response))))
      
      (testing "Completion with system message"
        (let [provider (create-test-provider)
              response (agent.llm.core/complete provider
                                                [{:role "system" :content "Be helpful"}
                                                 {:role "user" :content "Hello"}]
                                                {:model "claude-3-sonnet-20240229"})]
          (is (string? response)))))))

;; ======================
;; Streaming Tests
;; ======================

(deftest test-streaming
  (with-mocked-http
    (fn []
      (testing "Stream completion returns channel"
        (let [provider (create-test-provider)
              ch (agent.llm.core/stream provider
                                        [{:role "user" :content "Test"}]
                                        {:model "claude-3-opus-20240229"})]
          (is (some? ch))
          (is (instance? clojure.core.async.impl.channels.ManyToManyChannel ch)))))))

;; ======================
;; Embeddings Tests
;; ======================

(deftest test-embeddings
  (testing "Embeddings functionality"
    (let [provider (create-test-provider)]
      ;; Note: Anthropic may not support embeddings directly
      ;; This test verifies the interface exists
      (is (fn? (get (methods agent.llm.core/ILLMProvider) 'embed))))))

;; ======================
;; Model Listing Tests
;; ======================

(deftest test-model-listing
  (testing "List models"
    (let [provider (create-test-provider)
          models (agent.llm.core/list-models provider)]
      (is (vector? models))
      (is (pos? (count models)))
      (is (every? #(contains? % :id) models)))))

;; ======================
;; Capabilities Tests
;; ======================

(deftest test-capabilities
  (testing "Get model capabilities"
    (let [provider (create-test-provider)
          capabilities (agent.llm.core/get-capabilities provider "claude-3-opus-20240229")]
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
          estimate (agent.llm.core/estimate-cost provider messages "claude-3-opus-20240229")]
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
      (is (contains? config :version))))

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
      (testing "Health check"
        (let [provider (create-test-provider)
              health (agent.llm.core/health-check provider)]
          (is (map? health))
          (is (contains? health :healthy)))))))

;; ======================
;; Error Handling Tests
;; ======================

(deftest test-error-handling
  (fake/with-fake-routes
    {"https://api.anthropic.com/v1/messages"
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
          (let [capabilities (agent.llm.core/get-capabilities provider "claude-3-opus-20240229")]
            (is (map? capabilities)))

          ;; Complete
          (let [response (agent.llm.core/complete provider
                                                  [{:role "user" :content "Hello"}]
                                                  {})]
            (is (string? response)))

          ;; Health check
          (let [health (agent.llm.core/health-check provider)]
            (is (map? health))))))))

;; ======================
;; Factory Function Tests
;; ======================

(deftest test-factory-functions
  (testing "Create provider via factory"
    (let [provider (anthropic/create-provider :anthropic {:api-key "test-key"})]
      (is (some? provider))
      (is (satisfies? agent.llm.core/ILLMProvider provider))))

  (testing "Create provider with custom factory"
    (let [provider (anthropic/create-anthropic-provider
                    {:api-key "test-key"
                     :config {:max-retries 3}})]
      (is (some? provider))))

  (testing "Provider factory with invalid type"
    (is (thrown? Exception (anthropic/create-provider :invalid {:api-key "test"})))))

(comment
  ;; Run tests
  (run-tests 'test.agent.llm.providers.anthropic-test)

  ;; Manual testing
  (require '[agent.llm.providers.anthropic :as anthropic]
           '[clj-http.fake :as fake])

  ;; Create test provider
  (def test-provider (anthropic/create-anthropic-provider {:api-key "test-key"}))

  ;; Test with mocked HTTP
  (fake/with-fake-routes
    {"https://api.anthropic.com/v1/messages"
     (fn [request]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body "{\"content\":[{\"text\":\"Test response\"}]}"})}

    (agent.llm.core/complete test-provider
                             [{:role "user" :content "Hello"}]
                             {})))