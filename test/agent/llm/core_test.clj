(ns agent.llm.core-test
  "Tests for LLM core protocols and interfaces."
  (:require
   [clojure.test :refer :all]
   [agent.llm.core :as llm-core]))

(deftest test-llm-protocol-definitions
  (testing "ILLMProvider protocol exists"
    (is (some? llm-core/ILLMProvider))
    (is (map? llm-core/ILLMProvider))))

(deftest test-protocol-methods
  (testing "Protocol has required methods"
    (let [protocol-methods (keys (:sigs llm-core/ILLMProvider))]
      (is (some #{:complete} protocol-methods))
      (is (some #{:stream} protocol-methods))
      (is (some #{:embed} protocol-methods))
      (is (some #{:list-models} protocol-methods))
      (is (some #{:get-capabilities} protocol-methods))
      (is (some #{:estimate-cost} protocol-methods)))))

(deftest test-configuration-protocols
  (testing "Configuration protocols exist"
    (is (some? llm-core/ILLMProviderWithConfig))
    (is (some? llm-core/ILLMProviderWithHealth))
    
    (let [config-methods (keys (:sigs llm-core/ILLMProviderWithConfig))]
      (is (some #{:update-config} config-methods))
      (is (some #{:get-config} config-methods)))
    
    (let [health-methods (keys (:sigs llm-core/ILLMProviderWithHealth))]
      (is (some #{:health-check} health-methods)))))

(deftest test-optional-protocols
  (is (some #{:complete-with-tools} (keys (:sigs llm-core/ILLMProviderWithTools))))
  (is (some #{:with-cache-controls} (keys (:sigs llm-core/ILLMProviderWithCache))))
  (is (some #{:usage} (keys (:sigs llm-core/ILLMProviderWithUsage))))
  (is (some #{:invoke} (keys (:sigs llm-core/ILLMProviderInvoke))))
  (is (some #{:generate} (keys (:sigs llm-core/ILLMProviderInvoke)))))

(deftest test-default-invoke-fallback
  (let [provider (reify llm-core/ILLMProvider
                   (complete [_ _ _] "ok")
                   (stream [_ _ _] nil)
                   (embed [_ _ _] [])
                   (list-models [_] [])
                   (get-capabilities [_ _] {})
                   (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0}))]
    (is (= {:role "assistant"
            :content "ok"
            :tool-calls []
            :usage nil
            :raw "ok"}
           (llm-core/invoke provider {:messages [{:role "user" :content "hi"}]})))))

(deftest test-tool-call-directive-normalization
  (is (= [{:type :tool-call
           :payload {:tool-name "search"
                     :input {:q "clojure"}
                     :context {:provider-tool-call-id "call-1"
                               :provider-tool-call {:id "call-1"
                                                    :type "function"
                                                    :function {:name "search"
                                                               :arguments "{\"q\":\"clojure\"}"}}}}}]
         (llm-core/tool-calls->directives
          [{:id "call-1"
            :type "function"
            :function {:name "search"
                       :arguments "{\"q\":\"clojure\"}"}}]))))

(deftest test-helper-functions
  (testing "Normalize messages function"
    (let [messages [{:role "user" :content "Hello"}
                    {:role :assistant :content "Hi there"}
                    {:role "system" :content "Be helpful"}]]
      (is (vector? (llm-core/normalize-messages messages)))
      (is (= 3 (count (llm-core/normalize-messages messages))))))
  
  (testing "Message validation"
    (let [valid-messages [{:role "user" :content "Hello"}]
          invalid-messages [{:wrong-key "user" :content "Hello"}]]
      (is (llm-core/validate-messages? valid-messages))
      (is (not (llm-core/validate-messages? invalid-messages))))))

(deftest test-error-handling
  (testing "Error types exist"
    (is (some? llm-core/ProviderError))
    (is (some? llm-core/ConfigurationError))
    (is (some? llm-core/ConnectionError))))

(comment
  ;; Run tests
  (run-tests 'test.agent.llm.core-test)
  
  ;; Manual testing
  (require '[agent.llm.core :as llm])
  
  ;; Check protocol methods
  (keys (methods llm/ILLMProvider))
  
  ;; Test message normalization
  (llm/normalize-messages [{:role "user" :content "Test"}])
  
  ;; Test validation
  (llm/validate-messages? [{:role "user" :content "Test"}]))
