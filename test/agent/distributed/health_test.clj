(ns agent.distributed.health-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures async]]
   [manifold.deferred :as d]
   [clojure.core.async :as async]
   [agent.distributed.health :as health]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn with-health-monitor
  "Fixture that creates a health monitor for testing."
  [f]
  (let [monitor (health/start-health-monitor
                 :heartbeat-interval-ms 100   ;; Fast for testing
                 :failure-threshold 2         ;; Fail after 2 missed heartbeats
                 :check-interval-ms 50)]      ;; Check frequently
    (try
      (binding [*test-monitor* monitor]
        (f))
      (finally
        ;; Monitor doesn't have stop method, just let it be garbage collected
        ))))

(defn with-load-balancer
  "Fixture that creates a load balancer for testing."
  [f]
  (let [balancer (health/start-round-robin-load-balancer)]
    (try
      (binding [*test-balancer* balancer]
        (f))
      (finally
        ;; No cleanup needed
        ))))

(use-fixtures :each with-health-monitor with-load-balancer)

;; ============================================================================
;; Test Variables
;; ============================================================================

(def ^:dynamic *test-monitor* nil)
(def ^:dynamic *test-balancer* nil)

;; ============================================================================
;; Health Monitor Tests
;; ============================================================================

(deftest health-monitor-start-stop-test
  (testing "Starting and stopping health monitoring"
    (let [monitor *test-monitor*
          agent-id "test-agent-1"]
      
      ;; Start monitoring
      (let [result @(health/start-monitoring monitor agent-id)]
        (is (= agent-id (:agent-id result)))
        (is (= :healthy (:status result)))
        (is (contains? result :last-heartbeat))
        (is (contains? result :monitoring-since)))
      
      ;; Get health status
      (let [status @(health/get-health-status monitor agent-id)]
        (is (= agent-id (:agent-id status)))
        (is (= :healthy (:status status))))
      
      ;; Stop monitoring
      (let [result @(health/stop-monitoring monitor agent-id)]
        (is (= agent-id (:agent-id result)))
        (is (:stopped result)))
      
      ;; Get status after stopping (should return error)
      (let [status @(health/get-health-status monitor agent-id)]
        (is (= :not-monitored (:error status)))))))

(deftest health-monitor-heartbeat-test
  (testing "Heartbeat registration and health status updates"
    (let [monitor *test-monitor*
          agent-id "test-heartbeat-agent"]
      
      ;; Start monitoring
      @(health/start-monitoring monitor agent-id)
      
      ;; Register heartbeat
      (let [result @(health/register-heartbeat monitor agent-id)]
        (is (= agent-id (:agent-id result)))
        (is (= :healthy (:status result)))
        (is (= 0 (:missed-heartbeats result))))
      
      ;; Wait a bit (but less than failure threshold)
      (async/<!! (async/timeout 80))
      
      ;; Should still be healthy
      (let [status @(health/get-health-status monitor agent-id)]
        (is (= :healthy (:status status)))
        (is (<= (:missed-heartbeats status) 1))))))

(deftest health-monitor-failure-detection-test
  (testing "Failure detection when heartbeats are missed"
    (let [monitor *test-monitor*
          agent-id "test-failure-agent"]
      
      ;; Start monitoring with fast failure settings
      @(health/start-monitoring monitor agent-id)
      
      ;; Initial status should be healthy
      (let [initial-status @(health/get-health-status monitor agent-id)]
        (is (= :healthy (:status initial-status))))
      
      ;; Wait long enough for failure (2 missed heartbeats * 100ms interval + buffer)
      (async/<!! (async/timeout 250))
      
      ;; Should now be marked as failed
      (let [failed-status @(health/get-health-status monitor agent-id)]
        (is (= :failed (:status failed-status)))
        (is (>= (:missed-heartbeats failed-status) 2)))
      
      ;; Get failed agents list
      (let [failed-agents @(health/get-failed-agents monitor)]
        (is (contains? failed-agents agent-id))))))

(deftest health-monitor-listener-test
  (testing "Health status change listeners"
    (let [monitor *test-monitor*
          agent-id "test-listener-agent"
          status-changes (atom [])
          listener-fn (fn [agent-id old-status new-status]
                        (swap! status-changes conj [agent-id old-status new-status]))]
      
      ;; Add listener
      (let [listener-id @(health/add-health-listener monitor listener-fn)]
        (is (string? listener-id))
        
        ;; Start monitoring
        @(health/start-monitoring monitor agent-id)
        
        ;; Wait for failure
        (async/<!! (async/timeout 250))
        
        ;; Should have received status change notifications
        (is (seq @status-changes))
        (let [[first-change] @status-changes]
          (is (= agent-id (first first-change)))
          (is (= :healthy (second first-change)))
          (is (= :failed (nth first-change 2))))
        
        ;; Remove listener
        (let [result @(health/remove-health-listener monitor listener-id)]
          (is (= listener-id (:removed result))))))))

;; ============================================================================
;; Load Balancer Tests
;; ============================================================================

(deftest load-balancer-registration-test
  (testing "Agent registration and deregistration with load balancer"
    (let [balancer *test-balancer*
          agent-id "test-load-agent"
          capabilities #{:llm :reasoning}]
      
      ;; Register agent
      (let [result @(health/register-agent-with-balancer balancer agent-id capabilities)]
        (is (= agent-id (:agent-id result)))
        (is (:registered result)))
      
      ;; Select agent for matching capabilities
      (let [selected @(health/select-agent balancer #{:llm})]
        (is (= agent-id selected)))
      
      ;; Select agent for non-matching capabilities (should return nil)
      (let [selected @(health/select-agent balancer #{:web-search})]
        (is (nil? selected)))
      
      ;; Deregister agent
      (let [result @(health/deregister-agent-from-balancer balancer agent-id)]
        (is (= agent-id (:agent-id result)))
        (is (:deregistered result)))
      
      ;; Should not be selected after deregistration
      (let [selected @(health/select-agent balancer #{:llm})]
        (is (nil? selected))))))

(deftest load-balancer-round-robin-test
  (testing "Round-robin agent selection"
    (let [balancer *test-balancer*]
      
      ;; Register multiple agents with same capabilities
      @(health/register-agent-with-balancer balancer "agent-1" #{:llm})
      @(health/register-agent-with-balancer balancer "agent-2" #{:llm})
      @(health/register-agent-with-balancer balancer "agent-3" #{:llm})
      
      ;; First selection should be agent-1
      (is (= "agent-1" @(health/select-agent balancer #{:llm})))
      
      ;; Second selection should be agent-2 (queue rotated)
      (is (= "agent-2" @(health/select-agent balancer #{:llm})))
      
      ;; Third selection should be agent-3
      (is (= "agent-3" @(health/select-agent balancer #{:llm})))
      
      ;; Fourth selection should cycle back to agent-1
      (is (= "agent-1" @(health/select-agent balancer #{:llm}))))))

(deftest load-balancer-load-tracking-test
  (testing "Load tracking and statistics"
    (let [balancer *test-balancer*
          agent-id "test-load-tracking-agent"]
      
      ;; Register agent
      @(health/register-agent-with-balancer balancer agent-id #{:test})
      
      ;; Initial load should be 0
      (is (= 0 @(health/get-agent-load balancer agent-id)))
      
      ;; Update load
      (is (= 5 @(health/update-agent-load balancer agent-id 5)))
      (is (= 5 @(health/get-agent-load balancer agent-id)))
      
      ;; Update load again
      (is (= 3 @(health/update-agent-load balancer agent-id -2)))
      (is (= 3 @(health/get-agent-load balancer agent-id)))
      
      ;; Get system load statistics
      (let [stats @(health/get-system-load balancer)]
        (is (contains? stats :total-load))
        (is (contains? stats :average-load))
        (is (contains? stats :max-load))
        (is (contains? stats :min-load))
        (is (contains? stats :agent-count))
        (is (= 1 (:agent-count stats)))
        (is (= 3 (:total-load stats)))))))

(deftest load-balancer-capability-matching-test
  (testing "Capability-based agent selection"
    (let [balancer *test-balancer*]
      
      ;; Register agents with different capabilities
      @(health/register-agent-with-balancer balancer "llm-agent" #{:llm :reasoning})
      @(health/register-agent-with-balancer balancer "web-agent" #{:web-search :scraping})
      @(health/register-agent-with-balancer balancer "multi-agent" #{:llm :web-search :data-processing})
      
      ;; Select agent with LLM capability
      (let [selected @(health/select-agent balancer #{:llm})]
        (is (contains? #{"llm-agent" "multi-agent"} selected)))
      
      ;; Select agent with web-search capability
      (let [selected @(health/select-agent balancer #{:web-search})]
        (is (contains? #{"web-agent" "multi-agent"} selected))
      
      ;; Select agent requiring both LLM and web-search
      (let [selected @(health/select-agent balancer #{:llm :web-search})]
        (is (= "multi-agent" selected)))
      
      ;; Select agent with non-existent capability (should return nil)
      (let [selected @(health/select-agent balancer #{:non-existent})]
        (is (nil? selected)))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest health-load-balancer-integration-test
  (testing "Integration between health monitoring and load balancing"
    (let [orchestrator (health/start-enhanced-orchestrator
                        :heartbeat-interval-ms 100
                        :failure-threshold 2
                        :check-interval-ms 50)]
      
      ;; Use health monitoring features through orchestrator
      @(health/start-monitoring orchestrator "integ-agent-1")
      @(health/register-heartbeat orchestrator "integ-agent-1")
      
      ;; Use load balancing features through orchestrator
      @(health/register-agent-with-balancer (:load-balancer orchestrator) "integ-agent-1" #{:llm})
      @(health/register-agent-with-balancer (:load-balancer orchestrator) "integ-agent-2" #{:web-search})
      
      ;; Test selection
      (let [selected @(health/select-agent orchestrator #{:llm})]
        (is (= "integ-agent-1" selected)))
      
      ;; Test load tracking
      @(health/update-agent-load orchestrator "integ-agent-1" 3)
      (is (= 3 @(health/get-agent-load orchestrator "integ-agent-1")))
      
      ;; Test system load
      (let [stats @(health/get-system-load orchestrator)]
        (is (= 2 (:agent-count stats)))
        (is (= 3 (:total-load stats))))
      
      ;; Test health status
      (let [status @(health/get-health-status orchestrator "integ-agent-1")]
        (is (= :healthy (:status status)))))))

;; ============================================================================
;; Performance Tests
;; ============================================================================

(deftest health-monitor-performance-test
  (testing "Health monitor performance with many agents"
    (let [monitor (health/start-health-monitor
                   :heartbeat-interval-ms 1000
                   :failure-threshold 3
                   :check-interval-ms 100)
          agent-count 50]
      
      ;; Register many agents
      (doseq [i (range agent-count)]
        (let [agent-id (str "perf-agent-" i)]
          @(health/start-monitoring monitor agent-id)
          @(health/register-heartbeat monitor agent-id)))
      
      ;; Check that all are being monitored
      ;; (Note: we can't directly count monitored agents, but we can check a sample)
      (let [sample-status @(health/get-health-status monitor "perf-agent-0")]
        (is (= :healthy (:status sample-status))))
      
      ;; Wait a bit and check for heartbeats
      (async/<!! (async/timeout 150))
      
      ;; Register more heartbeats
      (doseq [i (range agent-count)]
        @(health/register-heartbeat monitor (str "perf-agent-" i))))))

(deftest load-balancer-performance-test
  (testing "Load balancer performance with many agents"
    (let [balancer (health/start-round-robin-load-balancer)
          agent-count 100]
      
      ;; Register many agents
      (doseq [i (range agent-count)]
        (let [agent-id (str "lb-perf-agent-" i)
              capabilities (if (even? i) #{:llm} #{:web-search})]
          @(health/register-agent-with-balancer balancer agent-id capabilities)))
      
      ;; Perform many selections
      (dotimes [i 1000]
        @(health/select-agent balancer #{:llm}))
      
      ;; Check system load
      (let [stats @(health/get-system-load balancer)]
        (is (= agent-count (:agent-count stats)))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest health-monitor-error-handling-test
  (testing "Error handling in health monitor"
    (let [monitor *test-monitor*]
      
      ;; Try to get status of non-existent agent
      (let [status @(health/get-health-status monitor "non-existent")]
        (is (= :not-monitored (:error status))))
      
      ;; Try to stop monitoring non-existent agent (should not error)
      (let [result @(health/stop-monitoring monitor "non-existent")]
        (is (= "non-existent" (:agent-id result)))
        (is (:stopped result)))
      
      ;; Try to register heartbeat for non-monitored agent (should return nil)
      (let [result @(health/register-heartbeat monitor "non-existent")]
        (is (nil? result))))))

(deftest load-balancer-error-handling-test
  (testing "Error handling in load balancer"
    (let [balancer *test-balancer*]
      
      ;; Try to get load of non-existent agent (should return 0)
      (is (= 0 @(health/get-agent-load balancer "non-existent")))
      
      ;; Try to update load of non-existent agent (should still work)
      (is (= 5 @(health/update-agent-load balancer "non-existent" 5)))
      
      ;; Try to select agent with no matching capabilities (should return nil)
      (is (nil? @(health/select-agent balancer #{:non-existent}))))))

;; ============================================================================
;; Run Tests
;; ============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'agent.distributed.health-test)
  
  ;; Run specific test suite
  (clojure.test/test-vars [#'health-monitor-start-stop-test])
  
  ;; Quick demo
  (let [monitor (health/start-health-monitor
                 :heartbeat-interval-ms 500
                 :failure-threshold 2
                 :check-interval-ms 100)]
    
    @(health/start-monitoring monitor "demo-agent")
    @(health/register-heartbeat monitor "demo-agent")
    
    (println "Initial health:" @(health/get-health-status monitor "demo-agent"))
    
    (async/<!! (async/timeout 600))
    
    (println "After timeout:" @(health/get-health-status monitor "demo-agent"))
    
    @(health/stop-monitoring monitor "demo-agent")))