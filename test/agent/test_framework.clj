(ns agent.test-framework
  "Testing framework utilities for AI agent testing.
  Provides mocks, fixtures, and helpers for testing agent components."
  (:require
   [clojure.test :refer :all]
   [clojure.core.async :as async]
   [agent.llm :as llm]
   [agent.knowledge-graph :as kg]
   [agent.multi-head :as mh]))

;; ============================================================================
;; Mock Implementations
;; ============================================================================

(defrecord MockLLMProvider [responses]
  llm/ILLMProvider
  (complete [_ messages _]
    (let [prompt (-> messages first :content)]
      (get responses prompt
           {:text "Default mock response" :tokens 10})))
  
  (stream [_ messages _]
    (let [ch (async/chan)]
      (async/go
        (async/>! ch "Mock streaming response")
        (async/close! ch))
      ch))
  
  (embed [_ text _]
    (vec (take 10 (repeat 0.1)))))

(defn create-mock-llm-provider
  "Create mock LLM provider with predefined responses.
  responses: map of prompt -> response"
  [responses]
  (->MockLLMProvider responses))

(defrecord MockKnowledgeGraph [data]
  kg/IKnowledgeGraph
  (store-fact [_ subject predicate object]
    (swap! data update :facts conj [subject predicate object]))
  
  (query [_ pattern]
    (let [db @data]
      (filter
       (fn [fact]
         (matches-pattern? fact pattern))
       (:facts db))))
  
  (find-entities [_ type]
    (->> @data :facts
         (filter #(= (second %) :type))
         (filter #(= (nth % 2) type))
         (map first)))
  
  (get-facts [_ subject]
    (->> @data :facts
         (filter #(= (first %) subject))
         (map (fn [[_ p o]] [p o]))))
  
  (infer [_ rules]
    ;; Simple mock inference - just returns existing data
    @data))

(defn matches-pattern?
  "Check if fact matches query pattern."
  [fact pattern]
  ;; Simplified pattern matching for mocks
  true)

(defn create-mock-knowledge-graph
  "Create mock knowledge graph."
  []
  (->MockKnowledgeGraph (atom {:facts []})))

(defrecord MockDecisionHead [id name specialty choice confidence]
  mh/IDecisionHead
  (evaluate [_ context options]
    {:head-id id
     :head-name name
     :choice choice
     :reasoning (str name " evaluation of " (count options) " options")
     :confidence confidence
     :risks []
     :benefits []})
  
  (specialty [_] specialty)
  
  (confidence [_ evaluation] (:confidence evaluation))
  
  (explain [_ evaluation] (:reasoning evaluation)))

(defn create-mock-decision-head
  "Create mock decision head with predefined behavior."
  [id name specialty choice confidence]
  (->MockDecisionHead id name specialty choice confidence))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn with-mock-llm-fixture
  "Fixture that provides mock LLM provider."
  [responses]
  (fn [f]
    (let [mock-provider (create-mock-llm-provider responses)]
      (with-redefs [llm/create-openai-provider (constantly mock-provider)]
        (f)))))

(defn with-mock-knowledge-graph-fixture
  "Fixture that provides mock knowledge graph."
  []
  (fn [f]
    (let [mock-kg (create-mock-knowledge-graph)]
      (with-redefs [kg/create-in-memory-graph (constantly mock-kg)]
        (f)))))

(defn with-test-agent-fixture
  "Fixture that provides complete test agent environment."
  []
  (fn [f]
    (let [llm-responses {"test prompt" {:text "test response" :tokens 5}}
          mock-llm (create-mock-llm-provider llm-responses)
          mock-kg (create-mock-knowledge-graph)]
      (with-redefs [llm/create-openai-provider (constantly mock-llm)
                    kg/create-in-memory-graph (constantly mock-kg)]
        (f)))))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn assert-protocol-implemented
  "Assert that a record implements a protocol."
  [record protocol]
  (is (satisfies? protocol record)
      (str "Record does not implement protocol: " protocol)))

(defn assert-valid-response
  "Assert that a response has required keys and types."
  [response required-keys]
  (doseq [key required-keys]
    (is (contains? response key)
        (str "Response missing key: " key)))
  
  (when (contains? response :confidence)
    (is (number? (:confidence response))
        "Confidence should be a number")
    (is (<= 0 (:confidence response) 1)
        "Confidence should be between 0 and 1")))

(defn assert-no-exceptions
  "Assert that a function doesn't throw exceptions."
  [f & args]
  (try
    (apply f args)
    true
    (catch Exception e
      (is false (str "Function threw exception: " (.getMessage e)))
      false)))

(defn with-timeout
  "Execute function with timeout."
  [timeout-ms f]
  (let [result (promise)
        thread (Thread. #(deliver result (try (f) (catch Exception e e))))]
    (.start thread)
    (.join thread timeout-ms)
    (if (.isAlive thread)
      (do
        (.interrupt thread)
        (throw (ex-info "Timeout exceeded" {:timeout-ms timeout-ms})))
      @result)))

;; ============================================================================
;; Property-Based Testing Helpers
;; ============================================================================

(defn generate-test-context
  "Generate random test context."
  []
  (str "Test context " (rand-int 1000)))

(defn generate-test-options
  "Generate random test options."
  [n]
  (vec (repeatedly n #(str "Option " (rand-int 1000))))

(defn valid-decision-head?
  "Check if decision head is valid."
  [head]
  (and (satisfies? mh/IDecisionHead head)
       (:id head)
       (:name head)
       (:specialty head)))

(defn valid-evaluation?
  "Check if evaluation is valid."
  [evaluation]
  (and (map? evaluation)
       (:head-id evaluation)
       (:head-name evaluation)
       (or (:choice evaluation) (:error evaluation))))

;; ============================================================================
;; Integration Test Helpers
;; ============================================================================

(defn create-integration-test-agent
  "Create agent for integration testing."
  []
  (let [llm-provider (create-mock-llm-provider {})
        knowledge-graph (create-mock-knowledge-graph)]
    {:llm-provider llm-provider
     :knowledge-graph knowledge-graph
     :multi-head-orchestrator (mh/create-orchestrator llm-provider knowledge-graph)}))

(defn test-agent-pipeline
  "Test complete agent pipeline."
  [agent prompt]
  (let [llm-provider (:llm-provider agent)
        response (llm/complete llm-provider [{:role "user" :content prompt}] {})]
    {:prompt prompt
     :response response
     :timestamp (System/currentTimeMillis)}))

(defn assert-pipeline-success
  "Assert that agent pipeline succeeded."
  [result]
  (is (contains? result :response)
      "Pipeline should return response")
  (is (contains? result :timestamp)
      "Pipeline should include timestamp")
  (when-let [response (:response result)]
    (is (map? response)
        "Response should be a map")))

;; ============================================================================
;; Performance Testing Helpers
;; ============================================================================

(defn measure-response-time
  "Measure response time for a function."
  [f & args]
  (let [start-time (System/nanoTime)
        result (apply f args)
        end-time (System/nanoTime)]
    {:result result
     :time-ns (- end-time start-time)
     :time-ms (/ (- end-time start-time) 1000000.0)}))

(defn assert-response-time
  "Assert that response time is within limits."
  [measurement max-time-ms]
  (let [time-ms (:time-ms measurement)]
    (is (<= time-ms max-time-ms)
        (str "Response time " time-ms "ms exceeds limit " max-time-ms "ms")))

(defn run-load-test
  "Run load test with multiple concurrent requests."
  [f requests concurrency]
  (let [start-time (System/currentTimeMillis)
        results (->> requests
                     (partition-all concurrency)
                     (mapcat (fn [batch]
                               (pmap #(try (f %) (catch Exception e e)) batch)))
                     doall)
        end-time (System/currentTimeMillis)
        total-time (- end-time start-time)]
    {:results results
     :total-time-ms total-time
     :requests-per-second (if (zero? total-time)
                            Float/POSITIVE_INFINITY
                            (/ (* 1000 (count requests)) total-time))
     :errors (filter #(instance? Exception %) results)}))

;; ============================================================================
;; Example Tests Using Framework
;; ============================================================================

(deftest ^:framework test-framework-utilities
  (testing "Mock LLM provider"
    (let [responses {"test" {:text "response"}}
          provider (create-mock-llm-provider responses)]
      (assert-protocol-implemented provider llm/ILLMProvider)
      
      (let [response (llm/complete provider [{:role "user" :content "test"}] {})]
        (is (= {:text "response"} response)))))
  
  (testing "Mock knowledge graph"
    (let [kg (create-mock-knowledge-graph)]
      (assert-protocol-implemented kg kg/IKnowledgeGraph)
      
      (kg/store-fact kg :test :property :value)
      (let [facts (kg/get-facts kg :test)]
        (is (seq facts))
        (is (= [:property :value] (first facts))))))
  
  (testing "Test helpers"
    (let [valid-response {:answer "test" :confidence 0.8}
          invalid-response {:answer "test"}]
      
      (assert-no-exceptions #(assert-valid-response valid-response [:answer :confidence]))
      (is (thrown? AssertionError
                   (assert-valid-response invalid-response [:answer :confidence]))))))

(comment
  ;; Example usage
  (use-fixtures :once (with-test-agent-fixture))
  
  ;; Run framework tests
  (run-tests 'agent.test-framework)
  
  ;; Create test agent
  (def test-agent (create-integration-test-agent))
  
  ;; Test pipeline
  (def result (test-agent-pipeline test-agent "Test prompt"))
  (assert-pipeline-success result)
  
  ;; Performance test
  (def measurement (measure-response-time
                    test-agent-pipeline test-agent "Performance test"))
  (assert-response-time measurement 1000)  ; 1 second limit
  
  ;; Load test
  (def load-test-result
    (run-load-test #(test-agent-pipeline test-agent (str "Load test " %))
                   (range 100)
                   10))
  
  (println "Requests per second:" (:requests-per-second load-test-result))
  (println "Errors:" (count (:errors load-test-result)))
  )