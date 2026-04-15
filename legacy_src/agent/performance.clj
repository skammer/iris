(ns agent.performance
  "Performance optimization utilities for agent system.
   
   Implements:
   - Caching strategies (LRU, TTL)
   - Connection pooling
   - Parallel execution
   - Batch processing
   - Memory optimization
   
   Protocols:
   - ICache: Generic caching interface
   - IConnectionPool: Connection pooling interface
   - IParallelExecutor: Parallel execution interface
   - IBatchProcessor: Batch processing interface"
  (:require
   [clojure.core.async :as async :refer [go chan >! <! timeout]]
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [clojure.set :as set])
  (:import
   (java.time Instant Duration)
   (java.util.concurrent ConcurrentHashMap Executors TimeUnit)
   (java.util LinkedHashMap Collections)))

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol ICache
  "Generic caching interface."
  
  (get-cached [this key]
    "Get value from cache by key.")
  
  (put-cached [this key value]
    "Put value in cache with key.")
  
  (invalidate [this key]
    "Invalidate cache entry by key.")
  
  (clear-cache [this]
    "Clear all cache entries.")
  
  (cache-stats [this]
    "Get cache statistics."))

(defprotocol IConnectionPool
  "Connection pooling interface."
  
  (get-connection [this]
    "Get connection from pool.")
  
  (return-connection [this connection]
    "Return connection to pool.")
  
  (with-connection [this f]
    "Execute function with pooled connection.")
  
  (pool-stats [this]
    "Get pool statistics."))

(defprotocol IParallelExecutor
  "Parallel execution interface."
  
  (execute-parallel [this tasks]
    "Execute tasks in parallel.")
  
  (execute-with-dependencies [this tasks dependency-graph]
    "Execute tasks respecting dependencies.")
  
  (executor-stats [this]
    "Get executor statistics."))

(defprotocol IBatchProcessor
  "Batch processing interface."
  
  (add-to-batch [this item]
    "Add item to current batch.")
  
  (process-batch [this]
    "Process current batch.")
  
  (batch-stats [this]
    "Get batch processor statistics."))

;; ============================================================================
;; Cache Implementations
;; ============================================================================

(defrecord LRUCache [max-size cache access-order]
  ICache
  
  (get-cached [this key]
    (locking cache
      (when-let [value (.get cache key)]
        ;; Update access order
        (.remove cache key)
        (.put cache key value)
        value)))
  
  (put-cached [this key value]
    (locking cache
      (when (>= (.size cache) max-size)
        ;; Remove least recently used
        (let [eldest (.entrySet cache)
              first-entry (first eldest)]
          (when first-entry
            (.remove cache (.getKey first-entry)))))
      (.put cache key value)))
  
  (invalidate [this key]
    (locking cache
      (.remove cache key)))
  
  (clear-cache [this]
    (locking cache
      (.clear cache)))
  
  (cache-stats [this]
    (locking cache
      {:size (.size cache)
       :max-size max-size
       :hit-rate 0.0  ;; Would track in real implementation
       :evictions 0})))

(defn create-lru-cache
  "Create LRU cache with maximum size."
  [max-size]
  (let [cache (Collections/synchronizedMap
               (LinkedHashMap. max-size 0.75 true))]
    (->LRUCache max-size cache (atom []))))

(defrecord TTLCache [cache ttl-ms cleanup-interval-ms]
  ICache
  
  (get-cached [this key]
    (let [entry (.get cache key)]
      (when entry
        (let [{:keys [value expires-at]} entry
              now (System/currentTimeMillis)]
          (if (> now expires-at)
            (do
              (.remove cache key)
              nil)
            value)))))
  
  (put-cached [this key value]
    (let [expires-at (+ (System/currentTimeMillis) ttl-ms)]
      (.put cache key {:value value :expires-at expires-at})))
  
  (invalidate [this key]
    (.remove cache key))
  
  (clear-cache [this]
    (.clear cache))
  
  (cache-stats [this]
    (let [now (System/currentTimeMillis)
          entries (.entrySet cache)
          expired (count (filter (fn [entry]
                                   (> now (:expires-at (.getValue entry))))
                                 entries))]
      {:size (.size cache)
       :expired-entries expired
       :ttl-ms ttl-ms})))

(defn create-ttl-cache
  "Create TTL cache with expiration time."
  [ttl-ms]
  (->TTLCache (ConcurrentHashMap.) ttl-ms (* ttl-ms 2)))

;; ============================================================================
;; Connection Pool Implementation
;; ============================================================================

(defrecord ConnectionPool [factory max-connections idle-timeout-ms connections]
  IConnectionPool
  
  (get-connection [this]
    (locking connections
      (if (seq @connections)
        (let [conn (first @connections)]
          (swap! connections rest)
          conn)
        (if (< (count @connections) max-connections)
          (factory)
          (throw (ex-info "Connection pool exhausted"
                          {:max-connections max-connections}))))))
  
  (return-connection [this connection]
    (locking connections
      (swap! connections conj connection)))
  
  (with-connection [this f]
    (let [conn (get-connection this)]
      (try
        (f conn)
        (finally
          (return-connection this conn)))))
  
  (pool-stats [this]
    (locking connections
      {:total-connections (count @connections)
       :max-connections max-connections
       :idle-timeout-ms idle-timeout-ms
       :available (count @connections)})))

(defn create-connection-pool
  "Create connection pool."
  [factory max-connections idle-timeout-ms]
  (->ConnectionPool factory max-connections idle-timeout-ms (atom [])))

;; ============================================================================
;; Parallel Execution Implementation
;; ============================================================================

(defrecord ParallelExecutor [executor max-concurrent]
  IParallelExecutor
  
  (execute-parallel [this tasks]
    (let [futures (map (fn [task]
                         (.submit executor ^Callable task))
                       tasks)
          results (map (fn [future]
                         (try
                           (.get future)
                           (catch Exception e
                             {:error e
                              :success? false})))
                       futures)]
      results))
  
  (execute-with-dependencies [this tasks dependency-graph]
    (let [task-map (zipmap (map :id tasks) tasks)
          ready-tasks (filter (fn [task]
                                (empty? (get dependency-graph (:id task) [])))
                              tasks)
          remaining-tasks (remove (fn [task]
                                    (contains? (set (map :id ready-tasks))
                                               (:id task)))
                                  tasks)]
      
      ;; Execute ready tasks in parallel
      (let [results (execute-parallel this (map :execute ready-tasks))]
        ;; Recursively execute remaining tasks as dependencies are satisfied
        (if (empty? remaining-tasks)
          results
          (let [completed-ids (set (map :id ready-tasks))
                next-ready (filter (fn [task]
                                     (every? completed-ids
                                             (get dependency-graph (:id task))))
                                   remaining-tasks)]
            (concat results
                    (execute-with-dependencies this next-ready dependency-graph))))))
  
  (executor-stats [this]
    {:max-concurrent max-concurrent
     :active-tasks (.getActiveCount executor)
     :completed-tasks (.getCompletedTaskCount executor)
     :pool-size (.getPoolSize executor)}))

(defn create-parallel-executor
  "Create parallel executor with fixed thread pool."
  [max-concurrent]
  (let [executor (Executors/newFixedThreadPool max-concurrent)]
    (->ParallelExecutor executor max-concurrent)))

;; ============================================================================
;; Batch Processor Implementation
;; ============================================================================

(defrecord BatchProcessor [batch-size process-fn current-batch]
  IBatchProcessor
  
  (add-to-batch [this item]
    (swap! current-batch conj item)
    (when (>= (count @current-batch) batch-size)
      (process-batch this)))
  
  (process-batch [this]
    (let [batch (deref current-batch)]
      (reset! current-batch [])
      (when (seq batch)
        (process-fn batch))))
  
  (batch-stats [this]
    {:batch-size batch-size
     :current-batch-size (count @current-batch)
     :processed-batches 0}))  ;; Would track in real implementation

(defn create-batch-processor
  "Create batch processor."
  [batch-size process-fn]
  (->BatchProcessor batch-size process-fn (atom [])))

;; ============================================================================
;; LLM-Specific Optimizations
;; ============================================================================

(defrecord LLMResponseCache [cache]
  (get-llm-response [this prompt model parameters]
    (let [cache-key (hash-map :prompt prompt
                              :model model
                              :parameters parameters)]
      (get-cached cache cache-key)))
  
  (cache-llm-response [this prompt model parameters response]
    (let [cache-key (hash-map :prompt prompt
                              :model model
                              :parameters parameters)]
      (put-cached cache cache-key response))))

(defn create-llm-cache
  "Create LLM response cache."
  [cache-type & args]
  (let [cache (case cache-type
                :lru (apply create-lru-cache args)
                :ttl (apply create-ttl-cache args))]
    (->LLMResponseCache cache)))

;; ============================================================================
;; Performance Monitoring
;; ============================================================================

(defrecord PerformanceMonitor [metrics]
  (record-latency [this operation start-time]
    (let [duration (- (System/nanoTime) start-time)]
      ;; Record to metrics system
      duration))
  
  (record-cache-hit [this cache-name]
    ;; Record cache hit
    )
  
  (record-cache-miss [this cache-name]
    ;; Record cache miss
    )
  
  (record-parallel-task [this task-count duration]
    ;; Record parallel execution
    )
  
  (record-batch-processing [this batch-size duration]
    ;; Record batch processing
    ))

(defn create-performance-monitor
  "Create performance monitor."
  []
  (->PerformanceMonitor (atom {})))

;; ============================================================================
;; Optimized Agent Wrapper
;; ============================================================================

(defrecord OptimizedAgent [agent llm-cache connection-pool parallel-executor]
  (process-task-optimized [this task]
    (let [start-time (System/nanoTime)]
      
      ;; Check LLM cache first
      (if-let [cached-response (get-llm-response llm-cache
                                                 (:prompt task)
                                                 (:model task)
                                                 (:parameters task))]
        (do
          (log/debug "LLM cache hit for task" (:id task))
          cached-response)
        
        ;; Execute with optimizations
        (let [result (with-connection connection-pool
                       (fn [conn]
                         ;; Execute tools in parallel if independent
                         (if (:parallel? task)
                           (let [tools (:tools task)
                                 results (execute-parallel parallel-executor
                                                           (map #(fn [] (execute-tool % conn)) tools))]
                             {:tools-results results})
                           
                           ;; Sequential execution
                           (agent/process-task agent task))))]
          
          ;; Cache LLM response if applicable
          (when (:cache-llm? task)
            (cache-llm-response llm-cache
                                (:prompt task)
                                (:model task)
                                (:parameters task)
                                result))
          
          result)))))

(defn wrap-agent-with-optimizations
  "Wrap agent with performance optimizations."
  [agent & {:keys [cache-type cache-size ttl-ms max-connections max-concurrent]
            :or {cache-type :lru
                 cache-size 1000
                 ttl-ms 300000  ;; 5 minutes
                 max-connections 10
                 max-concurrent 4}}]
  
  (let [llm-cache (create-llm-cache cache-type cache-size)
        connection-pool (create-connection-pool
                         (fn [] (create-http-client))  ;; Example factory
                         max-connections
                         30000)  ;; 30 second idle timeout
        parallel-executor (create-parallel-executor max-concurrent)]
    
    (->OptimizedAgent agent llm-cache connection-pool parallel-executor)))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Create caches
  (def lru-cache (create-lru-cache 100))
  (def ttl-cache (create-ttl-cache 300000))  ;; 5 minutes
  
  ;; Use caches
  (put-cached lru-cache "key1" "value1")
  (println (get-cached lru-cache "key1"))
  (println (cache-stats lru-cache))
  
  ;; Connection pool
  (def http-pool (create-connection-pool
                  (fn [] (create-http-client))
                  10
                  30000))
  
  (with-connection http-pool
    (fn [client]
      (make-request client "https://api.example.com")))
  
  ;; Parallel execution
  (def executor (create-parallel-executor 4))
  
  (execute-parallel executor
                    [(fn [] (Thread/sleep 100) "task1")
                     (fn [] (Thread/sleep 200) "task2")
                     (fn [] (Thread/sleep 150) "task3")])
  
  ;; Batch processing
  (def batch-processor (create-batch-processor
                        10
                        (fn [batch]
                          (println "Processing batch of size:" (count batch)))))
  
  (doseq [i (range 25)]
    (add-to-batch batch-processor i))
  
  ;; Optimized agent
  (def simple-agent
    {:id "agent-1"
     :process-task (fn [task] {:result "processed"})})
  
  (def optimized-agent
    (wrap-agent-with-optimizations simple-agent
                                   :cache-size 500
                                   :max-concurrent 8))
  
  (process-task-optimized optimized-agent
                          {:id "task-1"
                           :prompt "Test prompt"
                           :model "gpt-4"
                           :parameters {:temperature 0.7}
                           :cache-llm? true}))