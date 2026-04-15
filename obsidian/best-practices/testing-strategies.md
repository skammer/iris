# Testing Strategies for AI Agents

## Overview
Comprehensive testing strategies for AI agents, covering unit tests, integration tests, property-based tests, and agent-specific testing challenges.

## Testing Challenges for AI Agents

### 1. Non-Deterministic Behavior
- LLM outputs vary between runs
- Stochastic sampling introduces randomness
- External dependencies (APIs, tools) may fail

### 2. Stateful Interactions
- Agents maintain conversation history
- Memory systems accumulate knowledge
- Tool execution has side effects

### 3. Complex Dependencies
- Multiple LLM providers
- External tools and APIs
- Knowledge graphs and vector stores

## Testing Pyramid for Agents

### 1. Unit Tests (Foundation)
- **Component isolation**: Test individual agent components
- **Mock dependencies**: Simulate LLMs, tools, external services
- **Deterministic behavior**: Control randomness with seeds

### 2. Integration Tests (Middle Layer)
- **Component interaction**: Test how components work together
- **Real dependencies**: Use test versions of external services
- **State management**: Verify state transitions and persistence

### 3. End-to-End Tests (Top Layer)
- **Complete agent**: Test full agent pipeline
- **User scenarios**: Realistic user interactions
- **Performance validation**: Response times, resource usage

## Unit Testing Patterns

### 1. Mocking LLM Responses
```clojure
(defn mock-llm-provider
  "Create mock LLM provider for testing"
  [responses]
  (reify LLMProvider
    (complete [_ prompt _]
      (get responses prompt
           {:text "Mock response" :tokens 10}))
    
    (stream [_ prompt _]
      (let [ch (async/chan)]
        (async/go
          (async/>! ch {:text "Streaming response"})
          (async/close! ch))
        ch))))

(deftest reasoning-component-test
  (let [llm (mock-llm-provider {"test prompt" {:text "test response"}})
        component (create-reasoning-component llm)]
    (is (= "test response"
           (:answer (reason component "test prompt"))))))
```

### 2. Testing State Transitions
```clojure
(deftest agent-state-transitions
  (testing "Initial state"
    (let [agent (create-agent)]
      (is (= :idle (:state agent)))
      (is (empty? (:memory agent)))))
  
  (testing "State after processing"
    (let [agent (create-agent)
          updated (process-message agent "Hello")]
      (is (= :processing (:state updated)))
      (is (contains? (:memory updated) :conversation)))))
```

### 3. Testing Error Conditions
```clojure
(deftest error-handling-test
  (testing "LLM failure"
    (let [llm (failing-llm-provider)
          agent (create-agent llm)]
      (is (thrown-with-msg?
           Exception
           #"LLM error"
           (ask-agent agent "test")))))
  
  (testing "Tool execution failure"
    (let [tool (failing-tool)
          agent (create-agent-with-tool tool)]
      (is (= :error
             (:status (execute-tool agent "failing-tool" {})))))))
```

## Integration Testing Patterns

### 1. Test Doubles for External Services
```clojure
(defrecord TestVectorStore []
  VectorStore
  (store [_ embeddings]
    (atom embeddings))
  
  (search [_ query]
    [{:id 1 :score 0.9 :text "test result"}]))

(defrecord TestKnowledgeGraph []
  KnowledgeGraph
  (query [_ sparql]
    {:results [{:entity "test" :property "value"}]})
  
  (add-fact [_ fact]
    (println "Adding fact:" fact)))

(deftest memory-integration-test
  (let [vector-store (->TestVectorStore)
        knowledge-graph (->TestKnowledgeGraph)
        memory (create-memory-system vector-store knowledge-graph)]
    (testing "Memory storage and retrieval"
      (store-memory memory "test key" "test value")
      (is (= "test value" (retrieve-memory memory "test key"))))))
```

### 2. Testing Component Interactions
```clojure
(deftest agent-pipeline-test
  (let [agent (create-test-agent)]
    (testing "Complete message processing"
      (let [response (ask-agent agent "What is 2+2?")]
        (is (contains? response :answer))
        (is (string? (:answer response)))
        (is (contains? response :confidence))
        (is (number? (:confidence response)))))))
```

### 3. Testing with Fixtures
```clojure
(defn agent-fixture
  "Fixture for agent testing"
  [f]
  (let [agent (create-test-agent)]
    (try
      (f agent)
      (finally
        (cleanup-agent agent)))))

(use-fixtures :each agent-fixture)

(deftest agent-conversation-test
  (testing "Multi-turn conversation"
    (let [agent *1] ; From fixture
      (ask-agent agent "Hello")
      (let [response (ask-agent agent "How are you?")]
        (is (contains? response :context))
        (is (> (count (:context response)) 1))))))
```

## Property-Based Testing

### 1. Testing Invariants
```clojure
(defspec agent-invariants
  (prop/for-all [message gen/string]
    (let [agent (create-test-agent)
          response (ask-agent agent message)]
      (and (contains? response :answer)
           (string? (:answer response))
           (<= 0 (:confidence response) 1)
           (contains? response :timestamp)
           (inst? (:timestamp response))))))
```

### 2. Testing State Consistency
```clojure
(defspec state-consistency
  (prop/for-all [messages (gen/vector gen/string 1 10)]
    (let [agent (create-test-agent)]
      (reduce (fn [agent message]
                (let [response (ask-agent agent message)]
                  (and (valid-response? response)
                       (consistent-state? agent))))
              agent
              messages))))
```

### 3. Testing Tool Execution
```clojure
(defspec tool-execution-properties
  (prop/for-all [tool-name gen/keyword
                 args (gen/map gen/keyword gen/any)]
    (let [agent (create-test-agent)]
      (if-let [tool (get-tool agent tool-name)]
        (let [result (execute-tool agent tool-name args)]
          (contains? result :status))
        true)))) ; Tool doesn't exist is valid
```

## End-to-End Testing

### 1. Scenario Testing
```clojure
(deftest weather-agent-scenario
  (let [agent (create-weather-agent)]
    (testing "Complete weather inquiry"
      (let [response1 (ask-agent agent "What's the weather in London?")
            response2 (ask-agent agent "What about tomorrow?")]
        
        (is (contains? response1 :weather))
        (is (contains? response2 :forecast))
        
        ; Verify conversation continuity
        (is (contains? (:context response2) :previous-question))))))
```

### 2. Performance Testing
```clojure
(deftest agent-performance
  (let [agent (create-test-agent)
        queries (repeat 100 "test query")]
    
    (testing "Response time"
      (let [start-time (System/currentTimeMillis)
            responses (doall (map #(ask-agent agent %) queries))
            end-time (System/currentTimeMillis)
            total-time (- end-time start-time)
            avg-time (/ total-time 100.0)]
        
        (is (< avg-time 1000) ; Less than 1 second average
            (str "Average response time too high: " avg-time "ms"))))
    
    (testing "Memory usage"
      (let [memory-before (memory-usage)
            _ (doall (map #(ask-agent agent %) queries))
            memory-after (memory-usage)]
        
        (is (< (- memory-after memory-before) (* 1024 1024)) ; Less than 1MB increase
            "Memory leak detected")))))
```

### 3. Reliability Testing
```clojure
(deftest agent-reliability
  (let [agent (create-test-agent)]
    (testing "Handling malformed input"
      (doseq [input [nil "" "   " "!@#$%^&*()" (repeat 10000 "x")]]
        (let [response (ask-agent agent input)]
          (is (contains? response :status))
          (is (#{:success :error} (:status response))))))
    
    (testing "Recovery from errors"
      (let [failing-agent (create-agent-with-failing-tool)
            response (ask-agent failing-agent "use failing tool")]
        (is (= :error (:status response)))
        
        ; Agent should recover
        (let [recovery-response (ask-agent failing-agent "normal query")]
          (is (= :success (:status recovery-response))))))))
```

## Mocking Strategies

### 1. LLM Mocking
```clojure
(defn create-llm-mock
  "Create configurable LLM mock"
  [& {:keys [responses latency errors]
      :or {responses {} latency 0 errors {}}}]
  (reify LLMProvider
    (complete [_ prompt opts]
      (Thread/sleep latency)
      (if-let [error (get errors prompt)]
        (throw (ex-info "Mock LLM error" {:prompt prompt}))
        (get responses prompt {:text "Default mock response"})))
    
    (stream [_ prompt opts]
      (let [ch (async/chan)]
        (async/go
          (Thread/sleep latency)
          (if-let [error (get errors prompt)]
            (async/>! ch {:error error})
            (async/>! ch (get responses prompt {:text "Streaming mock"})))
          (async/close! ch))
        ch))))
```

### 2. Tool Mocking
```clojure
(defrecord MockTool [name behavior]
  Tool
  (execute [_ args]
    (case behavior
      :success {:result "success" :status :success}
      :failure (throw (ex-info "Tool failed" {:args args}))
      :slow (do (Thread/sleep 1000) {:result "slow"})
      :custom (if-let [custom (:custom behavior)]
                (custom args)
                {:result "custom"})))
  
  (describe [_]
    {:name name :description "Mock tool for testing"}))
```

### 3. External Service Mocking
```clojure
(defn with-mocked-services
  "Test with mocked external services"
  [f]
  (let [original-http http-request]
    (with-redefs [http-request (fn [url opts]
                                 (case url
                                   "https://api.weather.com" {:temp 20 :conditions "sunny"}
                                   "https://api.tools.com" {:result "tool success"}
                                   (original-http url opts)))]
      (f))))
```

## Test Data Management

### 1. Test Data Generation
```clojure
(defn generate-test-conversations
  "Generate realistic test conversations"
  [n]
  (gen/sample
   (gen/vector
    (gen/tuple
     (gen/elements ["user" "agent"])
     gen/string)
    n)))

(defn create-test-knowledge-graph
  "Create test knowledge graph with sample data"
  []
  (let [graph (create-knowledge-graph)]
    (doseq [[entity properties] test-entities]
      (add-entity graph entity properties))
    graph))
```

### 2. Golden Master Testing
```clojure
(defn golden-master-test
  "Compare against golden master outputs"
  [agent input]
  (let [response (ask-agent agent input)
        golden (load-golden-master input)]
    (if golden
      (is (= golden response)
          (str "Response differs from golden master for input: " input))
      (save-golden-master input response))))
```

## Continuous Testing

### 1. CI/CD Pipeline Integration
```clojure
(defn run-test-suite
  "Run complete test suite"
  []
  {:unit-tests (run-unit-tests)
   :integration-tests (run-integration-tests)
   :e2e-tests (run-e2e-tests)
   :property-tests (run-property-tests)})

(defn test-report
  "Generate test report"
  [results]
  (merge results
         {:timestamp (java.util.Date.)
          :success? (every? :passed (vals results))}))
```

### 2. Flaky Test Detection
```clojure
(defn detect-flaky-tests
  "Run tests multiple times to detect flakiness"
  [test-fn & {:keys [runs] :or {runs 10}}]
  (let [results (repeatedly runs #(try (test-fn) :pass (catch Exception e :fail)))]
    {:passes (count (filter #{:pass} results))
     :failures (count (filter #{:fail} results))
     :flaky? (and (some #{:pass} results) (some #{:fail} results))}))
```

## Best Practices

### 1. Test Organization
- **Separate test namespaces**: `agent.core-test`, `agent.memory-test`
- **Test fixtures**: Reusable setup/teardown
- **Test data**: Realistic but controlled test data

### 2. Test Maintainability
- **Descriptive test names**: Explain what's being tested
- **Minimal test dependencies**: Isolate tests as much as possible
- **Regular test cleanup**: Remove obsolete tests

### 3. Test Performance
- **Fast unit tests**: < 100ms per test
- **Parallel execution**: Run independent tests in parallel
- **Selective test runs**: Run only relevant tests during development

### 4. Test Documentation
- **Test purpose**: Why the test exists
- **Test assumptions**: Preconditions and constraints
- **Expected behavior**: What the test verifies

## References
- Clojure test framework documentation
- Property-based testing with test.check
- Mocking and testing best practices
- CI/CD integration patterns