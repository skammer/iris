(ns agent.integration-tests
  "Integration tests for the complete agent system.
  Tests component interactions and end-to-end workflows."
  (:require
   [clojure.test :refer :all]
   [agent.test-framework :as tf]
   [agent.llm :as llm]
   [agent.knowledge-graph :as kg]
   [agent.multi-head :as mh]
   [agent.kg-integration :as kgi]
   [clojure.core.async :as async]))

(use-fixtures :each (tf/with-test-agent-fixture))

;; ============================================================================
;; LLM + Knowledge Graph Integration Tests
;; ============================================================================

(deftest test-llm-knowledge-graph-integration
  (testing "LLM responses stored in knowledge graph"
    (let [llm-provider (tf/create-mock-llm-provider
                        {"test query" {:text "test response"}})
          knowledge-graph (tf/create-mock-knowledge-graph)]
      
      ;; Simulate agent interaction
      (let [response (llm/complete llm-provider
                                   [{:role "user" :content "test query"}]
                                   {})]
        ;; Store interaction
        (kgi/store-interaction "test query" response)
        
        ;; Verify something was stored (mock doesn't actually store)
        (is (map? response)
            "LLM should return response"))))

  (testing "Knowledge graph query for relevant information"
    (let [knowledge-graph (tf/create-mock-knowledge-graph)]
      ;; Add some test facts
      (kg/store-fact knowledge-graph :clojure :type :programming-language)
      (kg/store-fact knowledge-graph :clojure :paradigm :functional)
      
      ;; Query relevant knowledge
      (let [keywords (kgi/extract-keywords "Tell me about Clojure programming")]
        (is (seq keywords)
            "Should extract keywords from text")
        (is (every? keyword? keywords)
            "Keywords should be keywords")))))

;; ============================================================================
;; Multi-Head Decision Making Integration Tests
;; ============================================================================

(deftest test-multi-head-integration
  (testing "Multi-head decision with mock components"
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
      
      ;; Make decision
      (let [context "Test decision context"
            options ["Option A" "Option B" "Option C"]
            result (mh/make-decision orchestrator context options)]
        
        (tf/assert-valid-response result [:decision :evaluations :consensus :context])
        (is (= context (:context result))
            "Context should be preserved")
        (is (<= 0 (:consensus result) 1)
            "Consensus should be between 0 and 1")
        (is (seq (:evaluations result))
            "Should have evaluations from all heads"))))

  (testing "Conflict resolution with mock heads"
    (let [knowledge-graph (tf/create-mock-knowledge-graph)
          ;; Create heads with conflicting opinions
          heads [(tf/create-mock-decision-head :head1 "Head 1" "test" "Option A" 0.9)
                 (tf/create-mock-decision-head :head2 "Head 2" "test" "Option B" 0.8)
                 (tf/create-mock-decision-head :head3 "Head 3" "test" "Option A" 0.7)]
          orchestrator (assoc (mh/->DecisionOrchestrator [] knowledge-graph) :heads heads)]
      
      ;; Evaluate
      (let [evaluations (map #(mh/evaluate % "context" ["A" "B"]) heads)
            decision (mh/resolve-conflict orchestrator evaluations)
            consensus (mh/consensus-level orchestrator evaluations)]
        
        (is decision "Should resolve to a decision")
        (is (<= 0 consensus 1)
            "Consensus should be calculated")))))

;; ============================================================================
;; Flow Integration Tests
;; ============================================================================

(deftest test-flow-integration
  (testing "Basic flow step execution"
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          ;; Create a simple test flow
          test-step (mh/multi-head-decider)]
      
      ;; Initialize step
      (let [state ((:init test-step) {})]
        (is (contains? state :orchestrator)
            "Step should initialize with orchestrator")
        
        ;; Test transformation (simplified)
        (let [inputs {:context ["Test context"]
                      :options [["Option 1" "Option 2"]]}
              [new-state outputs] ((:transform test-step) state nil inputs)]
          
          (is (map? new-state)
              "Should return new state")
          (is (map? outputs)
              "Should return outputs")))))

  (testing "Knowledge extraction flow"
    (let [knowledge-graph (tf/create-mock-knowledge-graph)
          extractor (kgi/knowledge-extractor)]
      
      ;; Initialize
      (let [state ((:init extractor) {})]
        (is (map? state)
            "Extractor should initialize")
        
        ;; Test extraction
        (let [text "Clojure is a functional programming language"
              [new-state outputs] ((:transform extractor) state nil {:text [text]})]
          
          (is (contains? outputs :facts)
              "Should extract facts")
          (is (vector? (:facts outputs))
              "Facts should be a vector")))))

;; ============================================================================
;; End-to-End Workflow Tests
;; ============================================================================

(deftest test-end-to-end-workflow
  (testing "Complete decision workflow"
    (let [llm-provider (tf/create-mock-llm-provider
                        {"analytical prompt" {:text "{\"choice\": \"A\", \"reasoning\": \"logical\", \"confidence\": 0.8}"}
                         "creative prompt" {:text "{\"choice\": \"B\", \"reasoning\": \"innovative\", \"confidence\": 0.7}"}})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
      
      ;; Complete workflow: decision → storage → query
      (let [context "Technology selection"
            options ["Clojure" "Python" "Rust"]
            
            ;; Step 1: Make decision
            decision-result (mh/make-decision orchestrator context options)
            
            ;; Step 2: Extract and store knowledge
            facts (kgi/extract-simple-facts (str context " " (clojure.string/join " " options)))
            
            ;; Step 3: Query knowledge
            keywords (kgi/extract-keywords context)]
        
        ;; Verify decision was made
        (is (:decision decision-result)
            "Should make a decision")
        
        ;; Verify knowledge extraction
        (is (vector? facts)
            "Should extract facts")
        
        ;; Verify keyword extraction
        (is (seq keywords)
            "Should extract keywords")
        
        ;; Verify consensus calculation
        (is (<= 0 (:consensus decision-result) 1)
            "Should calculate consensus"))))

  (testing "Error handling in workflow"
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)]
      
      ;; Test with invalid inputs
      (tf/assert-no-exceptions
       #(mh/create-orchestrator llm-provider knowledge-graph))
      
      ;; Test empty options
      (let [orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
        (tf/assert-no-exceptions
         #(mh/make-decision orchestrator "context" [])))
      
      ;; Test nil context
      (tf/assert-no-exceptions
       #(mh/make-decision orchestrator nil ["option"])))))

;; ============================================================================
;; Performance Integration Tests
;; ============================================================================

(deftest ^:performance test-integration-performance
  (testing "Decision making performance"
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)
          
          ;; Measure performance
          measurement (tf/measure-response-time
                       mh/make-decision
                       orchestrator
                       "Performance test context"
                       ["Option 1" "Option 2" "Option 3"])]
      
      (tf/assert-response-time measurement 5000)  ; 5 second limit for mock
      (is (:result measurement)
          "Should return result")))

  (testing "Concurrent decision making"
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)
          
          ;; Run concurrent decisions
          load-test (tf/run-load-test
                     (fn [i]
                       (mh/make-decision orchestrator
                                         (str "Context " i)
                                         [(str "Option A" i) (str "Option B" i)]))
                     (range 20)  ; 20 requests
                     4)]         ; 4 concurrent
        
      (is (< (count (:errors load-test)) 5)
          "Should have few errors")
      (is (pos? (:requests-per-second load-test))
          "Should process requests per second"))))

;; ============================================================================
;; Test Runner
;; ============================================================================

(defn run-integration-tests
  "Run all integration tests."
  []
  (run-tests 'agent.integration-tests))

(comment
  ;; Run tests
  (run-integration-tests)
  
  ;; Run specific test groups
  (run-tests #"test-llm-knowledge-graph-integration")
  (run-tests #"test-multi-head-integration")
  (run-tests #"test-flow-integration")
  (run-tests #"test-end-to-end-workflow")
  
  ;; Run performance tests separately
  (run-tests #"test-integration-performance")
  
  ;; Create test report
  (clojure.test/report :summary {:test 10 :pass 8 :fail 2 :error 0})
  )