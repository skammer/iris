(ns agent.llm.core-test
  "Tests for LLM core protocols and interfaces."
  (:require
   [clojure.test :refer [deftest is run-tests testing]]
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
    (is (some? llm-core/ILLMProviderWithHealth))

    (let [health-methods (keys (:sigs llm-core/ILLMProviderWithHealth))]
      (is (some #{:health-check} health-methods)))))

(deftest test-optional-protocols
  (is (some #{:invoke} (keys (:sigs llm-core/ILLMProviderInvoke))))
  (is (some #{:generate} (keys (:sigs llm-core/ILLMProviderInvoke)))))

(deftest test-default-invoke-fallback
  (let [provider (reify llm-core/ILLMProvider
                   (complete [_ _ _] "ok")
                   (stream [_ _ _] nil)
                   (embed [_ _ _] [])
                   (list-models [_] [])
                   (get-capabilities [_ _] {})
                   (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0}))
        response (llm-core/invoke provider {:messages [{:role "user" :content "hi"}]})]
    (is (= "assistant" (:role response)))
    (is (= "ok" (:content response)))
    (is (= [] (:tool-calls response)))
    (is (= "ok" (:raw response)))))

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

(deftest stream-error-event-redacts-raw-provider-data-test
  (let [event (llm-core/stream-error-event
               (ex-info "rate limited"
                        {:type :http-error
                         :status 429
                         :retry-after "0"
                         :headers {"Authorization" "Bearer secret"}
                         :body "secret response body"}))]
    (is (= {:type :http-error
            :status 429
            :retry-after "0"}
           (:details event)))))

(comment
  ;; Run tests
  (run-tests 'test.agent.llm.core-test))
