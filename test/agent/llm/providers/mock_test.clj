(ns test.agent.llm.providers.mock-test
  "Tests for mock LLM provider."
  (:require
   [clojure.test :refer :all]
   [agent.llm.providers.mock :as mock]
   [clojure.core.async :as async]))

(deftest test-mock-provider-creation
  (testing "Create basic mock provider"
    (let [provider (mock/create-mock-provider)]
      (is (some? provider))
      (is (satisfies? agent.llm.core/ILLMProvider provider))))
  
  (testing "Create mock provider with configuration"
    (let [provider (mock/create-mock-provider {:default-response "Custom response"})]
      (is (some? provider))
      (is (= "Custom response" (agent.llm.core/complete provider [{:role "user" :content "Hello"}] {}))))))

(deftest test-mock-completion
  (testing "Basic completion"
    (let [provider (mock/create-mock-provider)
          response (agent.llm.core/complete provider [{:role "user" :content "Hello"}] {})]
      (is (string? response))
      (is (pos? (count response)))))
  
  (testing "Pattern-based responses"
    (let [provider (-> (mock/create-mock-provider)
                       (mock/with-pattern-response "hello" "Hi there!"))]
      (is (= "Hi there!" (agent.llm.core/complete provider [{:role "user" :content "Say hello"}] {})))))
  
  (testing "Sequential responses"
    (let [provider (-> (mock/create-mock-provider)
                       (mock/with-sequential-responses ["First" "Second" "Third"]))]
      (is (= "First" (agent.llm.core/complete provider [{:role "user" :content "Test"}] {})))
      (is (= "Second" (agent.llm.core/complete provider [{:role "user" :content "Test"}] {})))
      (is (= "Third" (agent.llm.core/complete provider [{:role "user" :content "Test"}] {})))
      (is (= "First" (agent.llm.core/complete provider [{:role "user" :content "Test"}] {}))))))

(deftest test-mock-streaming
  (testing "Stream completion"
    (let [provider (mock/create-mock-provider)
          ch (agent.llm.core/stream provider [{:role "user" :content "Hello"}] {})]
      (is (some? ch))
      (is (instance? clojure.core.async.impl.channels.ManyToManyChannel ch))))
  
  (testing "Stream content collection"
    (let [provider (mock/create-mock-provider)
          ch (agent.llm.core/stream provider [{:role "user" :content "Test"}] {})
          results (async/<!! (async/into [] ch))]
      (is (vector? results))
      (is (every? string? results))
      (is (pos? (count (apply str results)))))))

(deftest test-mock-embeddings
  (testing "Single text embedding"
    (let [provider (mock/create-mock-provider)
          embedding (agent.llm.core/embed provider "Hello world" {})]
      (is (vector? embedding))
      (is (pos? (count embedding)))
      (is (every? number? embedding))))
  
  (testing "Multiple text embeddings"
    (let [provider (mock/create-mock-provider)
          embeddings (agent.llm.core/embed provider ["Hello" "World"] {})]
      (is (vector? embeddings))
      (is (= 2 (count embeddings)))
      (is (every? vector? embeddings)))))

(deftest test-mock-model-listing
  (testing "List models"
    (let [provider (mock/create-mock-provider)
          models (agent.llm.core/list-models provider)]
      (is (vector? models))
      (is (pos? (count models)))
      (is (every? #(contains? % :id) models))))
  
  (testing "Get capabilities"
    (let [provider (mock/create-mock-provider)
          capabilities (agent.llm.core/get-capabilities provider "mock-gpt-4")]
      (is (map? capabilities))
      (is (contains? capabilities :max-tokens))
      (is (contains? capabilities :supports-chat)))))

(deftest test-mock-history
  (testing "History tracking"
    (let [provider (mock/create-mock-provider)]
      (agent.llm.core/complete provider [{:role "user" :content "First"}] {})
      (agent.llm.core/complete provider [{:role "user" :content "Second"}] {})
      
      (let [history (mock/get-history provider)]
        (is (vector? history))
        (is (= 2 (count history)))
        (is (every? #(contains? % :type) history)))))
  
  (testing "Clear history"
    (let [provider (mock/create-mock-provider)]
      (agent.llm.core/complete provider [{:role "user" :content "Test"}] {})
      (is (= 1 (count (mock/get-history provider))))
      
      (mock/clear-history provider)
      (is (= 0 (count (mock/get-history provider)))))))

(deftest test-mock-configuration
  (testing "Update configuration"
    (let [provider (mock/create-mock-provider {:latency-ms 100})
          config (agent.llm.core/get-config provider)]
      (is (contains? config :latency-ms))
      (is (= 100 (:latency-ms config)))))
  
  (testing "Health check"
    (let [provider (mock/create-mock-provider)
          health (agent.llm.core/health-check provider)]
      (is (map? health))
      (is (contains? health :healthy))
      (is (true? (:healthy health))))))

(deftest test-preconfigured-mocks
  (testing "Helpful mock"
    (let [provider (mock/create-helpful-mock)]
      (is (= "Hello! How can I assist you today?"
             (agent.llm.core/complete provider [{:role "user" :content "hello"}] {})))))
  
  (testing "Echo mock"
    (let [provider (mock/create-echo-mock)]
      (is (re-matches #"You said: \".*\"" 
             (agent.llm.core/complete provider [{:role "user" :content "Test message"}] {})))))
  
  (testing "Delayed mock"
    (let [provider (mock/create-delayed-mock 50)
          start (System/currentTimeMillis)
          _ (agent.llm.core/complete provider [{:role "user" :content "Test"}] {})
          elapsed (- (System/currentTimeMillis) start)]
      (is (>= elapsed 50)))))

(deftest test-testing-utilities
  (testing "Assert response contains"
    (let [provider (-> (mock/create-mock-provider)
                       (mock/with-pattern-response "test" "This is a test response"))]
      (is (= "This is a test response"
             (mock/assert-response-contains provider 
                                            [{:role "user" :content "test message"}]
                                            "test response")))))
  
  (testing "Verify history count"
    (let [provider (mock/create-mock-provider)]
      (agent.llm.core/complete provider [{:role "user" :content "First"}] {})
      (is (= 1 (mock/verify-history-count provider 1))))))

(comment
  ;; Run tests
  (run-tests 'test.agent.llm.providers.mock-test)
  
  ;; Manual testing
  (require '[agent.llm.providers.mock :as mock])
  
  ;; Create and test provider
  (def test-provider (mock/create-helpful-mock))
  (agent.llm.core/complete test-provider [{:role "user" :content "hello"}] {})
  
  ;; Check history
  (mock/get-history test-provider)
  
  ;; Test streaming
  (let [ch (agent.llm.core/stream test-provider [{:role "user" :content "test"}] {})]
    (async/<!! (async/into [] ch))))