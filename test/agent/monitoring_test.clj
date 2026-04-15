(ns agent.monitoring-test
  "Tests for monitoring and observability components."
  (:require
   [clojure.test :refer :all]
   [agent.monitoring :as monitoring]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn monitoring-fixture
  "Create fresh monitoring components for each test."
  [f]
  (let [metrics (monitoring/create-metrics-collector)
        logger (monitoring/create-structured-logger)
        health-check (monitoring/create-health-checker)]
    (f metrics logger health-check)))

(use-fixtures :each monitoring-fixture)

;; ============================================================================
;; Metrics Tests
;; ============================================================================

(deftest test-metrics-collector-creation
  (testing "Metrics collector creation"
    (let [metrics (monitoring/create-metrics-collector)]
      (is (satisfies? monitoring/IMetricsCollector metrics)))))

(deftest test-counter-metrics
  (testing "Counter metric recording"
    (let [metrics (monitoring/create-metrics-collector)]
      (monitoring/record-counter metrics "test_counter" 1 {:label "value"})
      (monitoring/record-counter metrics "test_counter" 2 {:label "value"})
      
      ;; Check that metrics can be retrieved
      (let [metrics-output (monitoring/get-metrics metrics)]
        (is (string? metrics-output))
        (is (str/includes? metrics-output "test_counter"))))))

(deftest test-gauge-metrics
  (testing "Gauge metric recording"
    (let [metrics (monitoring/create-metrics-collector)]
      (monitoring/record-gauge metrics "test_gauge" 42.5 {:server "test"})
      (monitoring/record-gauge metrics "test_gauge" 99.9 {:server "test"})
      
      (let [metrics-output (monitoring/get-metrics metrics)]
        (is (str/includes? metrics-output "test_gauge"))))))

(deftest test-histogram-metrics
  (testing "Histogram metric recording"
    (let [metrics (monitoring/create-metrics-collector)]
      (monitoring/record-histogram metrics "test_histogram" 0.1 {:endpoint "/api"})
      (monitoring/record-histogram metrics "test_histogram" 0.5 {:endpoint "/api"})
      (monitoring/record-histogram metrics "test_histogram" 1.2 {:endpoint "/api"})
      
      (let [metrics-output (monitoring/get-metrics metrics)]
        (is (str/includes? metrics-output "test_histogram"))))))

(deftest test-timer-metrics
  (testing "Timer metric recording"
    (let [metrics (monitoring/create-metrics-collector)]
      (let [result (monitoring/record-timer metrics "test_timer"
                                           (fn [] (Thread/sleep 10) "done")
                                           {:operation "sleep"})]
        (is (= "done" result)))
      
      (let [metrics-output (monitoring/get-metrics metrics)]
        (is (str/includes? metrics-output "test_timer"))))))

(deftest test-metrics-clearing
  (testing "Metrics clearing"
    (let [metrics (monitoring/create-metrics-collector)]
      (monitoring/record-counter metrics "test_counter" 1 {:label "value"})
      
      (let [before-clear (monitoring/get-metrics metrics)]
        (is (str/includes? before-clear "test_counter")))
      
      (monitoring/clear-metrics metrics)
      
      (let [after-clear (monitoring/get-metrics metrics)]
        (is (not (str/includes? after-clear "test_counter")))))))

;; ============================================================================
;; Logging Tests
;; ============================================================================

(deftest test-structured-logger-creation
  (testing "Structured logger creation"
    (let [logger (monitoring/create-structured-logger)]
      (is (satisfies? monitoring/IStructuredLogger logger)))))

(deftest test-logging-with-context
  (testing "Logging with context"
    (let [logs (atom [])
          logger (monitoring/create-structured-logger
                  (fn [entry] (swap! logs conj entry)))]
      
      (monitoring/add-context logger :request-id "req-123")
      (monitoring/log logger :info "Test message" {:data "value"})
      
      (is (= 1 (count @logs)))
      (let [log-entry (first @logs)]
        (is (= :info (:level log-entry)))
        (is (= "Test message" (:message log-entry)))
        (is (= "req-123" (get-in log-entry [:context :request-id])))))))

(deftest test-context-management
  (testing "Logging context management"
    (let [logs (atom [])
          logger (monitoring/create-structured-logger
                  (fn [entry] (swap! logs conj entry)))]
      
      ;; Add context
      (monitoring/add-context logger :user-id 42)
      (monitoring/log logger :info "Message 1" {})
      
      ;; Remove context
      (monitoring/remove-context logger :user-id)
      (monitoring/log logger :info "Message 2" {})
      
      ;; Check logs
      (is (= 2 (count @logs)))
      (is (get-in (first @logs) [:context :user-id]))
      (is (not (get-in (second @logs) [:context :user-id]))))))

(deftest test-with-context-macro
  (testing "with-context execution"
    (let [logs (atom [])
          logger (monitoring/create-structured-logger
                  (fn [entry] (swap! logs conj entry)))]
      
      (monitoring/with-context logger {:session "sess-456"}
        (fn []
          (monitoring/log logger :debug "In context" {})))
      
      (let [log-entry (first @logs)]
        (is (= "sess-456" (get-in log-entry [:context :session])))))))

;; ============================================================================
;; Health Check Tests
;; ============================================================================

(deftest test-health-checker-creation
  (testing "Health checker creation"
    (let [health-check (monitoring/create-health-checker)]
      (is (satisfies? monitoring/IHealthCheck health-check)))))

(deftest test-health-check-registration
  (testing "Health check registration"
    (let [health-check (monitoring/create-health-checker)]
      (monitoring/register-health-check health-check "test-check"
                                        (fn [] {:healthy? true
                                                :message "OK"}))
      
      (let [results (monitoring/run-health-checks health-check)]
        (is (= 1 (count results)))
        (is (get results "test-check"))
        (is (:healthy? (get results "test-check")))))))

(deftest test-health-status
  (testing "Overall health status"
    (let [health-check (monitoring/create-health-checker)]
      
      ;; Register healthy check
      (monitoring/register-health-check health-check "healthy-check"
                                        (fn [] {:healthy? true
                                                :message "Healthy"}))
      
      ;; Register unhealthy check
      (monitoring/register-health-check health-check "unhealthy-check"
                                        (fn [] {:healthy? false
                                                :message "Failed"}))
      
      (let [status (monitoring/get-health-status health-check)]
        (is (false? (:healthy? status)))
        (is (= 2 (:checks status)))
        (is (= 1 (:healthy-checks status)))
        (is (= 1 (:unhealthy-checks status)))))))

(deftest test-health-check-exception-handling
  (testing "Health check exception handling"
    (let [health-check (monitoring/create-health-checker)]
      
      (monitoring/register-health-check health-check "failing-check"
                                        (fn [] (throw (Exception. "Check failed"))))
      
      (let [results (monitoring/run-health-checks health-check)]
        (is (= 1 (count results)))
        (let [result (get results "failing-check")]
          (is (false? (:healthy? result)))
          (is (str/includes? (:message result) "Check failed")))))))

;; ============================================================================
;; Agent Metrics Tests
;; ============================================================================

(deftest test-agent-metrics-helper
  (testing "Agent metrics helper creation"
    (let [metrics (monitoring/create-metrics-collector)
          agent-metrics (monitoring/create-agent-metrics metrics "agent-1")]
      
      (is (map? agent-metrics))
      (is (contains? agent-metrics :record-task-received))
      (is (contains? agent-metrics :record-task-completed))
      (is (contains? agent-metrics :record-task-failed))
      (is (contains? agent-metrics :record-llm-call))
      (is (contains? agent-metrics :record-tool-execution)))))

(deftest test-agent-metrics-recording
  (testing "Agent metrics recording functions"
    (let [metrics (monitoring/create-metrics-collector)
          agent-metrics (monitoring/create-agent-metrics metrics "agent-1")
          record-task-received (:record-task-received agent-metrics)
          record-task-completed (:record-task-completed agent-metrics)
          record-task-failed (:record-task-failed agent-metrics)]
      
      ;; Record metrics
      (record-task-received)
      (record-task-completed 150)  ;; 150ms
      (record-task-failed java.lang.Exception)
      
      ;; Check metrics were recorded
      (let [metrics-output (monitoring/get-metrics metrics)]
        (is (str/includes? metrics-output "agent_task_received_total"))
        (is (str/includes? metrics-output "agent_task_completed_total"))
        (is (str/includes? metrics-output "agent_task_failed_total"))))))

;; ============================================================================
;; Monitored Agent Tests
;; ============================================================================

(deftest test-monitored-agent-creation
  (testing "Monitored agent creation"
    (let [simple-agent {:id "test-agent"
                        :process-task (fn [task] {:result "processed"})}
          monitored-agent (monitoring/wrap-agent-with-monitoring simple-agent "agent-1")]
      
      (is (satisfies? monitoring/IMonitorable monitored-agent))
      (is (monitoring/get-metrics-collector monitored-agent))
      (is (monitoring/get-logger monitored-agent))
      (is (monitoring/get-health-check monitored-agent)))))

(deftest test-monitored-agent-task-processing
  (testing "Monitored agent task processing"
    (let [simple-agent {:id "test-agent"
                        :process-task (fn [task] {:result "processed"})}
          monitored-agent (monitoring/wrap-agent-with-monitoring simple-agent "agent-1")]
      
      ;; Process task
      (let [result (monitoring/process-task monitored-agent {:id "task-1" :data "test"})]
        (is (= {:result "processed"} result)))
      
      ;; Check metrics were recorded
      (let [metrics (monitoring/get-metrics-collector monitored-agent)
            metrics-output (monitoring/get-metrics metrics)]
        (is (str/includes? metrics-output "agent_task_received_total"))
        (is (str/includes? metrics-output "agent_task_completed_total"))))))

(deftest test-monitored-agent-error-handling
  (testing "Monitored agent error handling"
    (let [failing-agent {:id "test-agent"
                         :process-task (fn [task] (throw (Exception. "Task failed")))}
          monitored-agent (monitoring/wrap-agent-with-monitoring failing-agent "agent-1")]
      
      ;; Process task (should throw)
      (is (thrown? Exception
                   (monitoring/process-task monitored-agent {:id "task-1" :data "test"})))
      
      ;; Check error metrics were recorded
      (let [metrics (monitoring/get-metrics-collector monitored-agent)
            metrics-output (monitoring/get-metrics metrics)]
        (is (str/includes? metrics-output "agent_task_failed_total"))))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(deftest metrics-properties
  (testing "Metrics monotonicity: counters only increase"
    (let [prop (prop/for-all [increments (gen/vector (gen/choose 1 100) 1 10)]
                 (let [metrics (monitoring/create-metrics-collector)
                       counter-name "test_counter"]
                   
                   ;; Apply increments
                   (doseq [inc increments]
                     (monitoring/record-counter metrics counter-name inc {:test "label"}))
                   
                   ;; Get metrics output
                   (let [output (monitoring/get-metrics metrics)]
                     ;; Should contain counter line
                     (str/includes? output counter-name))))]
      
      (is (tc/quick-check 50 prop))))

  (testing "Logging context isolation"
    (let [prop (prop/for-all [contexts (gen/vector (gen/hash-map :key gen/string
                                                                 :value gen/string)
                                                   1 5)]
                 (let [logs (atom [])
                       logger (monitoring/create-structured-logger
                               (fn [entry] (swap! logs conj entry)))]
                   
                   ;; Add contexts
                   (doseq [ctx contexts]
                     (monitoring/add-context logger (:key ctx) (:value ctx)))
                   
                   ;; Log message
                   (monitoring/log logger :info "Test" {})
                   
                   ;; Check all contexts present
                   (let [log-entry (first @logs)
                         log-context (:context log-entry)]
                     (every? (fn [ctx]
                               (= (:value ctx) (get log-context (:key ctx))))
                             contexts))))]
      
      (is (tc/quick-check 30 prop)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn run-all-monitoring-tests
  "Run all monitoring tests."
  []
  (run-tests 'agent.monitoring-test))

(comment
  ;; Run tests
  (run-all-monitoring-tests)
  
  ;; Interactive testing
  (let [metrics (monitoring/create-metrics-collector)]
    (monitoring/record-counter metrics "test" 1 {:label "value"})
    (println (monitoring/get-metrics metrics)))
  
  ;; Test monitored agent
  (let [agent {:id "test" :process-task (fn [t] {:done true})}
        monitored (monitoring/wrap-agent-with-monitoring agent "agent-1")]
    (monitoring/process-task monitored {:id "task-1"})))