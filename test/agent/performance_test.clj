(ns agent.performance-test
  "Tests for performance optimization components."
  (:require
   [clojure.test :refer :all]
   [agent.performance :as perf]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn performance-fixture
  "Create fresh performance components for each test."
  [f]
  (let [lru-cache (perf/create-lru-cache 10)
        ttl-cache (perf/create-ttl-cache 1000)  ;; 1 second TTL
        executor (perf/create-parallel-executor 4)]
    (f lru-cache ttl-cache executor)))

(use-fixtures :each performance-fixture)

;; ============================================================================
;; Cache Tests
;; ============================================================================

(deftest test-lru-cache-creation
  (testing "LRU cache creation"
    (let [cache (perf/create-lru-cache 5)]
      (is (satisfies? perf/ICache cache)))))

(deftest test-lru-cache-basic-operations
  (testing "LRU cache basic operations"
    (let [cache (perf/create-lru-cache 3)]
      
      ;; Put values
      (perf/put-cached cache "key1" "value1")
      (perf/put-cached cache "key2" "value2")
      (perf/put-cached cache "key3" "value3")
      
      ;; Get values
      (is (= "value1" (perf/get-cached cache "key1")))
      (is (= "value2" (perf/get-cached cache "key2")))
      (is (= "value3" (perf/get-cached cache "key3")))
      
      ;; Check stats
      (let [stats (perf/cache-stats cache)]
        (is (= 3 (:size stats)))
        (is (= 3 (:max-size stats)))))))

(deftest test-lru-cache-eviction
  (testing "LRU cache eviction policy"
    (let [cache (perf/create-lru-cache 3)]
      
      ;; Fill cache
      (perf/put-cached cache "key1" "value1")
      (perf/put-cached cache "key2" "value2")
      (perf/put-cached cache "key3" "value3")
      
      ;; Access key1 to make it recently used
      (perf/get-cached cache "key1")
      
      ;; Add fourth item - should evict key2 (least recently used)
      (perf/put-cached cache "key4" "value4")
      
      ;; key2 should be evicted
      (is (nil? (perf/get-cached cache "key2")))
      
      ;; Other keys should still be present
      (is (= "value1" (perf/get-cached cache "key1")))
      (is (= "value3" (perf/get-cached cache "key3")))
      (is (= "value4" (perf/get-cached cache "key4"))))))

(deftest test-lru-cache-invalidation
  (testing "LRU cache invalidation"
    (let [cache (perf/create-lru-cache 5)]
      
      (perf/put-cached cache "key1" "value1")
      (perf/put-cached cache "key2" "value2")
      
      (is (= "value1" (perf/get-cached cache "key1")))
      
      ;; Invalidate key1
      (perf/invalidate cache "key1")
      (is (nil? (perf/get-cached cache "key1")))
      
      ;; key2 should still be present
      (is (= "value2" (perf/get-cached cache "key2"))))))

(deftest test-lru-cache-clear
  (testing "LRU cache clear"
    (let [cache (perf/create-lru-cache 5)]
      
      (perf/put-cached cache "key1" "value1")
      (perf/put-cached cache "key2" "value2")
      
      (is (= 2 (:size (perf/cache-stats cache))))
      
      ;; Clear cache
      (perf/clear-cache cache)
      
      (is (= 0 (:size (perf/cache-stats cache))))
      (is (nil? (perf/get-cached cache "key1")))
      (is (nil? (perf/get-cached cache "key2"))))))

(deftest test-ttl-cache-expiration
  (testing "TTL cache expiration"
    (let [cache (perf/create-ttl-cache 100)]  ;; 100ms TTL
      
      (perf/put-cached cache "key1" "value1")
      
      ;; Immediate read should work
      (is (= "value1" (perf/get-cached cache "key1")))
      
      ;; Wait for expiration
      (Thread/sleep 150)
      
      ;; Should be expired now
      (is (nil? (perf/get-cached cache "key1"))))))

;; ============================================================================
;; Parallel Executor Tests
;; ============================================================================

(deftest test-parallel-executor-creation
  (testing "Parallel executor creation"
    (let [executor (perf/create-parallel-executor 4)]
      (is (satisfies? perf/IParallelExecutor executor)))))

(deftest test-parallel-execution
  (testing "Parallel task execution"
    (let [executor (perf/create-parallel-executor 4)
          tasks [(fn [] (Thread/sleep 50) "task1")
                 (fn [] (Thread/sleep 30) "task2")
                 (fn [] (Thread/sleep 70) "task3")
                 (fn [] (Thread/sleep 20) "task4")]
          start-time (System/currentTimeMillis)
          results (perf/execute-parallel executor tasks)
          duration (- (System/currentTimeMillis) start-time)]
      
      ;; All tasks should complete
      (is (= 4 (count results)))
      (is (= #{"task1" "task2" "task3" "task4"}
             (set results)))
      
      ;; Should complete faster than sequential (70ms) due to parallelism
      ;; Allow some overhead for thread management
      (is (< duration 100)))))

(deftest test-parallel-execution-with-errors
  (testing "Parallel execution with errors"
    (let [executor (perf/create-parallel-executor 2)
          tasks [(fn [] "success1")
                 (fn [] (throw (Exception. "Task failed")))
                 (fn [] "success2")]
          results (perf/execute-parallel executor tasks)]
      
      (is (= 3 (count results)))
      
      ;; Check results structure
      (let [success-count (count (filter string? results))
            error-count (count (filter map? results))]
        (is (= 2 success-count))
        (is (= 1 error-count))
        
        ;; Check error result
        (let [error-result (first (filter map? results))]
          (is (false? (:success? error-result)))
          (is (instance? Exception (:error error-result))))))))

;; ============================================================================
;; Connection Pool Tests
;; ============================================================================

(deftest test-connection-pool-creation
  (testing "Connection pool creation"
    (let [pool (perf/create-connection-pool
                (fn [] "connection-obj")
                5
                30000)]
      (is (satisfies? perf/IConnectionPool pool)))))

(deftest test-connection-pool-basic-operations
  (testing "Connection pool basic operations"
    (let [connections-created (atom 0)
          pool (perf/create-connection-pool
                (fn []
                  (swap! connections-created inc)
                  (str "conn-" @connections-created))
                3
                30000)]
      
      ;; Get connections
      (let [conn1 (perf/get-connection pool)
            conn2 (perf/get-connection pool)
            conn3 (perf/get-connection pool)]
        
        (is (= "conn-1" conn1))
        (is (= "conn-2" conn2))
        (is (= "conn-3" conn3))
        
        ;; Return connections
        (perf/return-connection pool conn1)
        (perf/return-connection pool conn2)
        (perf/return-connection pool conn3)
        
        ;; Check stats
        (let [stats (perf/pool-stats pool)]
          (is (= 3 (:total-connections stats)))
          (is (= 3 (:max-connections stats)))
          (is (= 3 (:available stats))))))))

(deftest test-connection-pool-exhaustion
  (testing "Connection pool exhaustion"
    (let [pool (perf/create-connection-pool
                (fn [] "connection")
                2
                30000)]
      
      ;; Use all connections
      (perf/get-connection pool)
      (perf/get-connection pool)
      
      ;; Third attempt should fail
      (is (thrown? Exception (perf/get-connection pool))))))

(deftest test-with-connection-macro
  (testing "with-connection macro"
    (let [connections-used (atom [])
          pool (perf/create-connection-pool
                (fn [] "connection")
                2
                30000)]
      
      ;; Use connection with macro
      (let [result (perf/with-connection pool
                    (fn [conn]
                      (swap! connections-used conj conn)
                      "operation-result"))]
        
        (is (= "operation-result" result))
        (is (= 1 (count @connections-used)))
        
        ;; Connection should be returned to pool
        (let [stats (perf/pool-stats pool)]
          (is (= 1 (:available stats))))))))

;; ============================================================================
;; Batch Processor Tests
;; ============================================================================

(deftest test-batch-processor-creation
  (testing "Batch processor creation"
    (let [processor (perf/create-batch-processor
                     5
                     (fn [batch] (println "Processing:" batch)))]
      (is (satisfies? perf/IBatchProcessor processor)))))

(deftest test-batch-processing
  (testing "Batch processing"
    (let [processed-batches (atom [])
          processor (perf/create-batch-processor
                     3
                     (fn [batch]
                       (swap! processed-batches conj batch)))]
      
      ;; Add items
      (perf/add-to-batch processor "item1")
      (perf/add-to-batch processor "item2")
      (perf/add-to-batch processor "item3")  ;; Should trigger processing
      
      ;; Check batch was processed
      (is (= 1 (count @processed-batches)))
      (let [batch (first @processed-batches)]
        (is (= ["item1" "item2" "item3"] batch)))
      
      ;; Add more items
      (perf/add-to-batch processor "item4")
      (perf/add-to-batch processor "item5")
      
      ;; Manually process remaining
      (perf/process-batch processor)
      
      (is (= 2 (count @processed-batches)))
      (let [second-batch (second @processed-batches)]
        (is (= ["item4" "item5"] second-batch))))))

;; ============================================================================
;; LLM Cache Tests
;; ============================================================================

(deftest test-llm-cache-creation
  (testing "LLM cache creation"
    (let [llm-cache (perf/create-llm-cache :lru 10)]
      (is (instance? perf/LLMResponseCache llm-cache)))))

(deftest test-llm-cache-operations
  (testing "LLM cache operations"
    (let [llm-cache (perf/create-llm-cache :lru 5)]
      
      ;; Cache LLM response
      (perf/cache-llm-response llm-cache
                               "Test prompt"
                               "gpt-4"
                               {:temperature 0.7}
                               "Test response")
      
      ;; Retrieve cached response
      (let [cached (perf/get-llm-response llm-cache
                                          "Test prompt"
                                          "gpt-4"
                                          {:temperature 0.7})]
        (is (= "Test response" cached)))
      
      ;; Different parameters should not match
      (let [not-cached (perf/get-llm-response llm-cache
                                              "Test prompt"
                                              "gpt-4"
                                              {:temperature 0.9})]
        (is (nil? not-cached))))))

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(deftest cache-properties
  (testing "Cache property: get after put returns same value"
    (let [prop (prop/for-all [key gen/string
                              value gen/string]
                 (let [cache (perf/create-lru-cache 10)]
                   (perf/put-cached cache key value)
                   (= value (perf/get-cached cache key))))]
      
      (is (tc/quick-check 100 prop))))

  (testing "Cache property: size never exceeds max size"
    (let [prop (prop/for-all [entries (gen/vector (gen/tuple gen/string gen/string) 1 20)]
                 (let [max-size 5
                       cache (perf/create-lru-cache max-size)]
                   
                   ;; Add all entries
                   (doseq [[key value] entries]
                     (perf/put-cached cache key value))
                   
                   ;; Check size
                   (<= (:size (perf/cache-stats cache)) max-size)))]
      
      (is (tc/quick-check 50 prop)))))

(deftest parallel-execution-properties
  (testing "Parallel execution property: all tasks complete"
    (let [prop (prop/for-all [task-count (gen/choose 1 10)]
                 (let [executor (perf/create-parallel-executor 4)
                       tasks (repeatedly task-count
                                         (fn [] (fn [] (Thread/sleep 10) :done)))]
                   
                   (let [results (perf/execute-parallel executor tasks)]
                     (= task-count (count results)))))]
      
      (is (tc/quick-check 30 prop)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn run-all-performance-tests
  "Run all performance optimization tests."
  []
  (run-tests 'agent.performance-test))

(comment
  ;; Run tests
  (run-all-performance-tests)
  
  ;; Interactive testing
  (let [cache (perf/create-lru-cache 5)]
    (perf/put-cached cache "test" "value")
    (println (perf/get-cached cache "test")))
  
  ;; Test parallel execution
  (let [executor (perf/create-parallel-executor 4)]
    (perf/execute-parallel executor
                           [(fn [] "a") (fn [] "b") (fn [] "c")]))
  
  ;; Test connection pool
  (let [pool (perf/create-connection-pool (fn [] "conn") 3 30000)]
    (perf/with-connection pool
      (fn [conn]
        (println "Using connection:" conn)))))