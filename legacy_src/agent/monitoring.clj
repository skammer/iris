(ns agent.monitoring
  "Monitoring and observability for agent system.
   
   Implements:
   - Metrics collection (Prometheus-style)
   - Structured logging
   - Health checks
   - Performance monitoring
   
   Protocols:
   - IMetricsCollector: Metric collection interface
   - IStructuredLogger: Structured logging interface
   - IHealthCheck: Health monitoring interface
   - IMonitorable: Interface for monitorable components"
  (:require
   [clojure.core.async :as async :refer [go chan >! <! timeout]]
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [clojure.string :as str])
  (:import
   (java.time Instant)
   (java.util.concurrent ConcurrentHashMap)
   (io.prometheus.client Counter Gauge Histogram Summary CollectorRegistry)))

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol IMetricsCollector
  "Metrics collection interface."
  
  (record-counter [this name value labels]
    "Record a counter metric increment.")
  
  (record-gauge [this name value labels]
    "Record a gauge metric value.")
  
  (record-histogram [this name value labels]
    "Record a histogram metric value.")
  
  (record-summary [this name value labels]
    "Record a summary metric value.")
  
  (record-timer [this name fn-to-time labels]
    "Time execution of a function.")
  
  (get-metrics [this]
    "Get all collected metrics in Prometheus format.")
  
  (clear-metrics [this]
    "Clear all collected metrics."))

(defprotocol IStructuredLogger
  "Structured logging interface."
  
  (log [this level message data]
    "Log a structured message with data.")
  
  (with-context [this context fn-to-execute]
    "Execute function with logging context.")
  
  (add-context [this key value]
    "Add context to all subsequent logs.")
  
  (remove-context [this key]
    "Remove context from logs."))

(defprotocol IHealthCheck
  "Health monitoring interface."
  
  (register-health-check [this name check-fn]
    "Register a health check function.")
  
  (run-health-checks [this]
    "Run all health checks and return results.")
  
  (get-health-status [this]
    "Get overall health status."))

(defprotocol IMonitorable
  "Interface for monitorable components."
  
  (get-metrics-collector [this]
    "Get metrics collector for this component.")
  
  (get-logger [this]
    "Get structured logger for this component.")
  
  (get-health-check [this]
    "Get health check for this component."))

;; ============================================================================
;; Specs
;; ============================================================================

(s/def ::metric-name string?)
(s/def ::metric-value number?)
(s/def ::metric-labels (s/map-of string? string?))
(s/def ::log-level #{:trace :debug :info :warn :error :fatal})
(s/def ::log-message string?)
(s/def ::log-data map?)
(s/def ::health-check-name string?)
(s/def ::health-check-result (s/keys :req-un [::healthy? ::message ::details]))

;; ============================================================================
;; Metrics Implementation
;; ============================================================================

(defrecord PrometheusMetricsCollector [registry counters gauges histograms summaries]
  IMetricsCollector
  
  (record-counter [this name value labels]
    (let [counter (.orCreate counters name
                             (fn []
                               (Counter/build
                                name
                                (str "Counter for " name)
                                (into-array String (keys labels)))
                               (.register registry)))]
      (.labels counter (into-array String (vals labels)))
      (.inc counter value)))
  
  (record-gauge [this name value labels]
    (let [gauge (.orCreate gauges name
                           (fn []
                             (Gauge/build
                              name
                              (str "Gauge for " name)
                              (into-array String (keys labels)))
                             (.register registry)))]
      (.labels gauge (into-array String (vals labels)))
      (.set gauge value)))
  
  (record-histogram [this name value labels]
    (let [histogram (.orCreate histograms name
                               (fn []
                                 (Histogram/build
                                  name
                                  (str "Histogram for " name)
                                  (into-array String (keys labels)))
                                 (.register registry)))]
      (.labels histogram (into-array String (vals labels)))
      (.observe histogram value)))
  
  (record-summary [this name value labels]
    (let [summary (.orCreate summaries name
                             (fn []
                               (Summary/build
                                name
                                (str "Summary for " name)
                                (into-array String (keys labels)))
                               (.register registry)))]
      (.labels summary (into-array String (vals labels)))
      (.observe summary value)))
  
  (record-timer [this name fn-to-time labels]
    (let [start-time (System/nanoTime)]
      (try
        (let [result (fn-to-time)]
          (record-histogram this name (/ (- (System/nanoTime) start-time) 1e9) labels)
          result)
        (catch Exception e
          (record-histogram this name (/ (- (System/nanoTime) start-time) 1e9) labels)
          (throw e)))))
  
  (get-metrics [this]
    (let [writer (java.io.StringWriter.)]
      (io.prometheus.client.exporter.common.TextFormat/write004
       writer
       (.metricFamilySamples registry))
      (.toString writer)))
  
  (clear-metrics [this]
    (.clear registry)))

(defn create-metrics-collector
  "Create a new Prometheus metrics collector."
  []
  (->PrometheusMetricsCollector
   (CollectorRegistry.)
   (ConcurrentHashMap.)
   (ConcurrentHashMap.)
   (ConcurrentHashMap.)
   (ConcurrentHashMap.)))

;; ============================================================================
;; Structured Logging Implementation
;; ============================================================================

(defrecord StructuredLogger [context appender]
  IStructuredLogger
  
  (log [this level message data]
    (let [log-entry {:timestamp (Instant/now)
                     :level level
                     :message message
                     :context @context
                     :data data
                     :thread (.getName (Thread/currentThread))
                     :hostname (.. java.net.InetAddress getLocalHost getHostName)}]
      
      ;; Call appender function
      (appender log-entry)
      
      ;; Also log to standard logger for compatibility
      (case level
        :trace (log/trace message data)
        :debug (log/debug message data)
        :info (log/info message data)
        :warn (log/warn message data)
        :error (log/error message data)
        :fatal (log/error message data))))
  
  (with-context [this context-map fn-to-execute]
    (swap! context merge context-map)
    (try
      (fn-to-execute)
      (finally
        (doseq [key (keys context-map)]
          (swap! context dissoc key)))))
  
  (add-context [this key value]
    (swap! context assoc key value))
  
  (remove-context [this key]
    (swap! context dissoc key)))

(defn create-structured-logger
  "Create a new structured logger."
  ([]
   (create-structured-logger (fn [entry] (println (pr-str entry)))))
  ([appender]
   (->StructuredLogger (atom {}) appender)))

;; ============================================================================
;; Health Check Implementation
;; ============================================================================

(defrecord HealthChecker [checks]
  IHealthCheck
  
  (register-health-check [this name check-fn]
    (swap! checks assoc name check-fn)
    {:registered true :name name})
  
  (run-health-checks [this]
    (let [results (atom {})]
      (doseq [[name check-fn] @checks]
        (try
          (let [result (check-fn)]
            (swap! results assoc name result))
          (catch Exception e
            (swap! results assoc name
                   {:healthy? false
                    :message (str "Health check failed: " (.getMessage e))
                    :details {:exception (.getClass e)
                              :stack-trace (.getStackTrace e)}}))))
      @results))
  
  (get-health-status [this]
    (let [results (run-health-checks this)
          all-healthy? (every? :healthy? (vals results))]
      {:healthy? all-healthy?
       :checks (count results)
       :healthy-checks (count (filter :healthy? (vals results)))
       :unhealthy-checks (count (remove :healthy? (vals results)))
       :details results})))

(defn create-health-checker
  "Create a new health checker."
  []
  (->HealthChecker (atom {})))

;; ============================================================================
;; Agent-Specific Metrics
;; ============================================================================

(defn create-agent-metrics
  "Create agent-specific metrics helper."
  [metrics-collector agent-id]
  {:metrics metrics-collector
   :agent-id agent-id
   
   :record-task-received (fn []
                           (record-counter metrics-collector
                                           "agent_task_received_total"
                                           1
                                           {:agent_id agent-id}))
   
   :record-task-completed (fn [duration-ms]
                            (record-counter metrics-collector
                                            "agent_task_completed_total"
                                            1
                                            {:agent_id agent-id})
                            (record-histogram metrics-collector
                                              "agent_task_duration_seconds"
                                              (/ duration-ms 1000.0)
                                              {:agent_id agent-id}))
   
   :record-task-failed (fn [error-type]
                         (record-counter metrics-collector
                                         "agent_task_failed_total"
                                         1
                                         {:agent_id agent-id
                                          :error_type (name error-type)}))
   
   :record-llm-call (fn [model tokens duration-ms]
                      (record-counter metrics-collector
                                      "agent_llm_calls_total"
                                      1
                                      {:agent_id agent-id
                                       :model model})
                      (record-histogram metrics-collector
                                        "agent_llm_tokens_total"
                                        tokens
                                        {:agent_id agent-id
                                         :model model})
                      (record-histogram metrics-collector
                                        "agent_llm_duration_seconds"
                                        (/ duration-ms 1000.0)
                                        {:agent_id agent-id
                                         :model model}))
   
   :record-tool-execution (fn [tool-name duration-ms success?]
                            (record-counter metrics-collector
                                            "agent_tool_executions_total"
                                            1
                                            {:agent_id agent-id
                                             :tool tool-name
                                             :success (str success?)})
                            (record-histogram metrics-collector
                                              "agent_tool_duration_seconds"
                                              (/ duration-ms 1000.0)
                                              {:agent_id agent-id
                                               :tool tool-name}))})

;; ============================================================================
;; Monitoring-Enabled Agent
;; ============================================================================

(defrecord MonitoredAgent [agent metrics logger health-check]
  IMonitorable
  
  (get-metrics-collector [this]
    (:metrics metrics))
  
  (get-logger [this]
    logger)
  
  (get-health-check [this]
    health-check)
  
  ;; Agent methods with monitoring
  (process-task [this task]
    (let [start-time (System/currentTimeMillis)]
      ((:record-task-received metrics))
      
      ((:with-context logger) {:task-id (:id task)
                               :agent-id (:id agent)}
       (fn []
         ((:log logger) :info "Processing task" {:task task})
         
         (try
           (let [result (agent/process-task agent task)
                 duration (- (System/currentTimeMillis) start-time)]
             ((:record-task-completed metrics) duration)
             ((:log logger) :info "Task completed"
              {:task-id (:id task)
               :duration-ms duration
               :result result})
             result)
           
           (catch Exception e
             ((:record-task-failed metrics) (.getClass e))
             ((:log logger) :error "Task failed"
              {:task-id (:id task)
               :error (.getMessage e)
               :exception-class (.getClass e)})
             (throw e))))))))

(defn wrap-agent-with-monitoring
  "Wrap an agent with monitoring capabilities."
  [agent agent-id]
  (let [metrics-collector (create-metrics-collector)
        agent-metrics (create-agent-metrics metrics-collector agent-id)
        logger (create-structured-logger)
        health-check (create-health-checker)]
    
    ;; Register basic health checks
    (register-health-check health-check "agent-alive"
                           (fn []
                             {:healthy? true
                              :message "Agent is alive"
                              :details {:agent-id agent-id
                                        :timestamp (Instant/now)}}))
    
    (->MonitoredAgent agent agent-metrics logger health-check)))

;; ============================================================================
;; HTTP Metrics Endpoint
;; ============================================================================

(defn create-metrics-handler
  "Create HTTP handler for metrics endpoint."
  [metrics-collector]
  (fn [request]
    {:status 200
     :headers {"Content-Type" "text/plain; version=0.0.4"}
     :body (get-metrics metrics-collector)}))

(defn create-health-handler
  "Create HTTP handler for health checks."
  [health-checker]
  (fn [request]
    (let [status (get-health-status health-checker)]
      {:status (if (:healthy? status) 200 503)
       :headers {"Content-Type" "application/json"}
       :body (cheshire.core/generate-string status)})))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Create monitoring components
  (def metrics (create-metrics-collector))
  (def logger (create-structured-logger))
  (def health-check (create-health-checker))
  
  ;; Record some metrics
  (record-counter metrics "requests_total" 1 {:method "GET" :path "/api"})
  (record-gauge metrics "active_connections" 42 {:server "web-1"})
  (record-histogram metrics "request_duration_seconds" 0.123 {:endpoint "/api/users"})
  
  ;; Time a function
  (record-timer metrics "expensive_operation_seconds"
                (fn [] (Thread/sleep 1000))
                {:operation "data-processing"})
  
  ;; Structured logging
  (add-context logger :request-id "req-123")
  (log logger :info "Processing request" {:user-id 42 :action "login"})
  
  ;; Health checks
  (register-health-check health-check "database"
                         (fn []
                           {:healthy? true
                            :message "Database connection OK"
                            :details {:connection-time-ms 12}}))
  
  (run-health-checks health-check)
  
  ;; Get metrics in Prometheus format
  (println (get-metrics metrics))
  
  ;; Create monitored agent
  (def simple-agent {:id "agent-1"
                     :process-task (fn [task] {:result "processed"})})
  
  (def monitored-agent (wrap-agent-with-monitoring simple-agent "agent-1"))
  
  ;; Process task with monitoring
  (process-task monitored-agent {:id "task-1" :data "test"})
  
  ;; Get agent metrics
  (get-metrics-collector monitored-agent))