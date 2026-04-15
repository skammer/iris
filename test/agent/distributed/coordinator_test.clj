(ns agent.distributed.coordinator-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [manifold.deferred :as d]
   [agent.distributed.coordinator :as coord]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn with-cleanup
  "Fixture that cleans up test resources."
  [f]
  (let [orchestrator (coord/start-orchestrator)
        agent1 (coord/start-agent-node "test-agent-1" #{:llm :reasoning})
        agent2 (coord/start-agent-node "test-agent-2" #{:web-search :data-processing})]
    (try
      (binding [*test-orchestrator* orchestrator
                *test-agent-1* agent1
                *test-agent-2* agent2]
        (f))
      (finally
        @(coord/disconnect agent1)
        @(coord/disconnect agent2)))))

(use-fixtures :each with-cleanup)

;; ============================================================================
;; Test Variables
;; ============================================================================

(def ^:dynamic *test-orchestrator* nil)
(def ^:dynamic *test-agent-1* nil)
(def ^:dynamic *test-agent-2* nil)

(def sample-task
  {:task-id "test-task-123"
   :type :test-reasoning
   :payload {:question "Test question"
             :context {:domain :test}}
   :required-capabilities #{:llm}})

(def sample-proposal
  {:proposal-id "test-proposal-456"
   :content {:action :test-action
             :details "Test details"}})

;; ============================================================================
;; Orchestrator Tests
;; ============================================================================

(deftest orchestrator-registration-test
  (testing "Agent registration and deregistration"
    (let [orchestrator *test-orchestrator*
          agent-id "test-reg-agent"
          capabilities #{:llm :reasoning}]
      
      ;; Register agent
      (let [result @(coord/register-agent orchestrator agent-id capabilities)]
        (is (= agent-id (:agent-id result)))
        (is (= capabilities (:capabilities result)))
        (is (= :available (:status result))))
      
      ;; Find agent by capabilities
      (let [agents @(coord/find-agent orchestrator #{:llm})]
        (is (some #(= agent-id %) agents)))
      
      ;; Deregister agent
      (let [result @(coord/deregister-agent orchestrator agent-id)]
        (is (= agent-id (:agent-id result)))
        (is (= :deregistered (:status result)))))))

(deftest orchestrator-task-assignment-test
  (testing "Task assignment"
    (let [orchestrator *test-orchestrator*
          agent-id "test-task-agent"
          capabilities #{:llm}]
      
      ;; Register agent first
      @(coord/register-agent orchestrator agent-id capabilities)
      
      ;; Assign task
      (let [assignment @(coord/assign-task orchestrator sample-task agent-id)]
        (is (= agent-id (:agent-id assignment)))
        (is (= sample-task (:task assignment)))
        (is (= :task-assignment (:type assignment)))))))

(deftest orchestrator-broadcast-test
  (testing "Message broadcasting"
    (let [orchestrator *test-orchestrator*
          agent1-id "test-broadcast-1"
          agent2-id "test-broadcast-2"
          capabilities-llm #{:llm}
          capabilities-other #{:web-search}]
      
      ;; Register two agents with different capabilities
      @(coord/register-agent orchestrator agent1-id capabilities-llm)
      @(coord/register-agent orchestrator agent2-id capabilities-other)
      
      ;; Broadcast to LLM agents only
      (let [message {:type :test-update :content "Test message"}
            filter-fn (fn [agent-info]
                        (contains? (:capabilities agent-info) :llm))
            result @(coord/broadcast orchestrator message filter-fn)]
        
        (is (= 1 (:recipients result)))
        (is (= message (:message result)))))))

(deftest orchestrator-consensus-test
  (testing "Consensus mechanism"
    (let [orchestrator *test-orchestrator*
          agent1-id "test-consensus-1"
          agent2-id "test-consensus-2"
          agent3-id "test-consensus-3"
          capabilities #{:voting}]
      
      ;; Register three agents
      @(coord/register-agent orchestrator agent1-id capabilities)
      @(coord/register-agent orchestrator agent2-id capabilities)
      @(coord/register-agent orchestrator agent3-id capabilities)
      
      ;; Test consensus with all voters
      (let [voters #{agent1-id agent2-id agent3-id}
            result @(coord/consensus orchestrator sample-proposal voters)]
        
        (is (= (:proposal-id sample-proposal) (:proposal-id result)))
        (is (contains? result :votes))
        (is (contains? result :approved?))
        (is (contains? result :approval-ratio))
        
        ;; Should be approved (all auto-approve in basic implementation)
        (is (:approved? result))
        (is (>= (:approval-ratio result) 0.5))))))

;; ============================================================================
;; Agent Node Tests
;; ============================================================================

(deftest agent-node-connection-test
  (testing "Agent connection and disconnection"
    (let [agent *test-agent-1*
          coordinator-url "test://localhost:8080"]
      
      ;; Connect agent
      (let [result @(coord/connect agent coordinator-url)]
        (is (= "test-agent-1" (:agent-id result)))
        (is (= :connected (:status result)))
        (is (= coordinator-url (:coordinator-url result))))
      
      ;; Disconnect agent
      (let [result @(coord/disconnect agent)]
        (is (= "test-agent-1" (:agent-id result)))
        (is (= :disconnected (:status result)))))))

(deftest agent-node-task-processing-test
  (testing "Agent task processing"
    (let [agent *test-agent-1*]
      
      ;; Process a task
      (let [result @(coord/process-task agent sample-task)]
        (is (= "test-task-123" (:task-id result)))
        (is (= "test-agent-1" (:agent-id result)))
        (is (= :completed (:status result)))
        (is (contains? (:result result) :answer))
        (is (contains? (:result result) :processed-at))))))

(deftest agent-node-message-reception-test
  (testing "Agent message reception"
    (let [agent *test-agent-1*
          message {:type :test-message :content "Test content"}]
      
      ;; Receive message
      (let [result @(coord/receive-message agent message)]
        (is (= "test-agent-1" (:agent-id result)))
        (is (:message-received result))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest orchestrator-agent-integration-test
  (testing "Integration between orchestrator and agent"
    (let [orchestrator (coord/start-orchestrator)
          agent (coord/start-agent-node "integration-agent" #{:llm :integration})
          coordinator-url "integration://localhost:9090"]
      
      (try
        ;; Connect agent
        @(coord/connect agent coordinator-url)
        
        ;; Register agent with orchestrator
        @(coord/register-agent orchestrator "integration-agent" #{:llm :integration})
        
        ;; Verify agent can be found
        (let [agents @(coord/find-agent orchestrator #{:llm})]
          (is (some #(= "integration-agent" %) agents)))
        
        ;; Test task assignment flow
        (let [task {:task-id "integration-task"
                    :type :integration-test
                    :payload {:test "integration"}}
              assignment @(coord/assign-task orchestrator task "integration-agent")]
          (is (= "integration-agent" (:agent-id assignment)))
          (is (= task (:task assignment))))
        
        (finally
          @(coord/disconnect agent)
          ;; Note: orchestrator doesn't have stop method in basic implementation
          )))))

;; ============================================================================
;; Performance Tests
;; ============================================================================

(deftest orchestrator-performance-test
  (testing "Orchestrator performance with multiple agents"
    (let [orchestrator (coord/start-orchestrator)
          agent-count 10]
      
      (try
        ;; Register multiple agents quickly
        (let [registrations
              (doall
               (for [i (range agent-count)]
                 (let [agent-id (str "perf-agent-" i)
                       capabilities #{:llm :perf-test}]
                   @(coord/register-agent orchestrator agent-id capabilities))))]
          
          (is (= agent-count (count registrations)))
          
          ;; Find all agents
          (let [agents @(coord/find-agent orchestrator #{:llm})]
            (is (= agent-count (count agents)))))
        
        (finally
          ;; Cleanup
          )))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest orchestrator-error-handling-test
  (testing "Error handling in orchestrator"
    (let [orchestrator *test-orchestrator*]
      
      ;; Try to find agent that doesn't exist (should return empty list, not error)
      (let [agents @(coord/find-agent orchestrator #{:non-existent-capability})]
        (is (vector? agents))
        (is (empty? agents)))
      
      ;; Try to assign task to non-existent agent (should still return assignment structure)
      (let [assignment @(coord/assign-task orchestrator sample-task "non-existent-agent")]
        (is (contains? assignment :agent-id))
        (is (contains? assignment :task))
        (is (= "non-existent-agent" (:agent-id assignment)))))))

;; ============================================================================
;; Run Tests
;; ============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'agent.distributed.coordinator-test)
  
  ;; Run specific test
  (clojure.test/test-vars [#'orchestrator-registration-test])
  
  ;; Example test execution
  (let [orchestrator (coord/start-orchestrator)
        agent (coord/start-agent-node "demo-agent" #{:demo})]
    @(coord/connect agent "demo://localhost")
    @(coord/register-agent orchestrator "demo-agent" #{:demo})
    (println "Demo complete")
    @(coord/disconnect agent)))