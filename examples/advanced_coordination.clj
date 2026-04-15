(ns examples.advanced-coordination
  "Example of advanced coordination features: health monitoring and load balancing."
  (:require
   [agent.distributed.health :as health]
   [agent.distributed.coordinator :as coord]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]))

(defn create-agent-system
  "Create a complete agent system with health monitoring and load balancing."
  []
  (log/info "Creating advanced agent system...")
  
  ;; 1. Create enhanced orchestrator with health monitoring
  (let [orchestrator (health/start-enhanced-orchestrator
                      :heartbeat-interval-ms 3000    ;; 3 second heartbeats
                      :failure-threshold 3           ;; Fail after 3 missed
                      :check-interval-ms 1000)       ;; Check every second
        
        ;; 2. Create basic coordinator for task assignment
        coordinator (coord/start-orchestrator)
        
        ;; 3. Simulated agent registry
        agents (atom {})]
    
    {:orchestrator orchestrator
     :coordinator coordinator
     :agents agents}))

(defn register-agent-with-system
  "Register an agent with the complete system."
  [system agent-id capabilities]
  (log/info "Registering agent" agent-id "with capabilities" capabilities)
  
  (let [{:keys [orchestrator coordinator agents]} system]
    
    ;; 1. Start health monitoring
    @(health/start-monitoring orchestrator agent-id)
    
    ;; 2. Register with load balancer
    @(health/register-agent-with-balancer (:load-balancer orchestrator) agent-id capabilities)
    
    ;; 3. Register with coordinator
    @(coord/register-agent coordinator agent-id capabilities)
    
    ;; 4. Store in local registry
    (swap! agents assoc agent-id {:id agent-id
                                  :capabilities capabilities
                                  :registered-at (java.time.Instant/now)})
    
    {:agent-id agent-id :registered true}))

(defn simulate-agent-workload
  "Simulate agents processing tasks with load balancing."
  [system duration-ms]
  (log/info "Simulating workload for" duration-ms "ms...")
  
  (let [{:keys [orchestrator coordinator]} system
        start-time (System/currentTimeMillis)
        task-counter (atom 0)]
    
    (go
      (loop []
        (let [elapsed (- (System/currentTimeMillis) start-time)]
          (if (< elapsed duration-ms)
            (do
              ;; Create a random task
              (let [task-types [{:type :llm-reasoning :caps #{:llm :reasoning}}
                                {:type :web-search :caps #{:web-search}}
                                {:type :data-analysis :caps #{:data-processing}}
                                {:type :creative-writing :caps #{:llm :creative}}]
                    task-type (rand-nth task-types)
                    task-id (str "task-" (swap! task-counter inc))
                    task {:task-id task-id
                          :type (:type task-type)
                          :payload {:data (str "Task data for " task-id)}
                          :required-capabilities (:caps task-type)}]
                
                ;; Select agent using load balancer
                (when-let [agent-id @(health/select-agent orchestrator (:caps task-type))]
                  
                  ;; Assign task
                  @(coord/assign-task coordinator task agent-id)
                  
                  ;; Update load (simulate work)
                  @(health/update-agent-load orchestrator agent-id 1)
                  
                  ;; Simulate task completion after random delay
                  (go
                    (async/<! (async/timeout (+ 100 (rand-int 400))))
                    @(health/update-agent-load orchestrator agent-id -1)
                    (log/debug "Task" task-id "completed by" agent-id)))
                
                ;; Wait before next task
                (async/<! (async/timeout 50))
                (recur)))
            (log/info "Workload simulation completed. Tasks processed:" @task-counter)))))))

(defn demonstrate-health-monitoring
  "Demonstrate health monitoring features."
  [system]
  (log/info "\n=== Health Monitoring Demonstration ===")
  
  (let [{:keys [orchestrator]} system]
    
    ;; Add health status change listener
    (let [listener-id @(health/add-health-listener orchestrator
                                                   (fn [agent-id old-status new-status]
                                                     (log/info "HEALTH CHANGE:" agent-id old-status "->" new-status)))]
      
      ;; Register some agents
      (register-agent-with-system system "healthy-agent-1" #{:llm :reasoning})
      (register-agent-with-system system "healthy-agent-2" #{:web-search :data-processing})
      
      ;; Send initial heartbeats
      @(health/register-heartbeat orchestrator "healthy-agent-1")
      @(health/register-heartbeat orchestrator "healthy-agent-2")
      
      (log/info "Initial health statuses:")
      (log/info "  Agent 1:" @(health/get-health-status orchestrator "healthy-agent-1"))
      (log/info "  Agent 2:" @(health/get-health-status orchestrator "healthy-agent-2"))
      
      ;; Simulate agent-2 failing to send heartbeats
      (log/info "\nSimulating agent-2 heartbeat failure...")
      
      ;; Only send heartbeat for agent-1
      (go
        (dotimes [i 5]
          (async/<! (async/timeout 2000))
          @(health/register-heartbeat orchestrator "healthy-agent-1")
          (log/debug "Heartbeat sent for agent-1"))
        
        ;; Check failed agents
        (let [failed @(health/get-failed-agents orchestrator)]
          (log/info "\nFailed agents after simulation:" failed)
          (is (contains? failed "healthy-agent-2"))))
      
      ;; Remove listener
      @(health/remove-health-listener orchestrator listener-id))))

(defn demonstrate-load-balancing
  "Demonstrate load balancing features."
  [system]
  (log/info "\n=== Load Balancing Demonstration ===")
  
  (let [{:keys [orchestrator]} system]
    
    ;; Clear any existing agents
    (doseq [agent-id ["llm-1" "llm-2" "llm-3" "web-1" "web-2"]]
      (register-agent-with-system system agent-id
                                  (if (.startsWith agent-id "llm")
                                    #{:llm :reasoning}
                                    #{:web-search :scraping})))
    
    (log/info "Registered 5 agents (3 LLM, 2 Web)")
    
    ;; Demonstrate round-robin selection
    (log/info "\nRound-robin selection for LLM tasks:")
    (dotimes [i 6]
      (let [selected @(health/select-agent orchestrator #{:llm})]
        (log/info "  Selection" (inc i) "->" selected)))
    
    ;; Demonstrate load tracking
    (log/info "\nLoad tracking demonstration:")
    
    ;; Assign some load
    @(health/update-agent-load orchestrator "llm-1" 3)
    @(health/update-agent-load orchestrator "llm-2" 1)
    @(health/update-agent-load orchestrator "web-1" 2)
    
    (log/info "Agent loads:")
    (doseq [agent-id ["llm-1" "llm-2" "web-1"]]
      (log/info "  " agent-id ":" @(health/get-agent-load orchestrator agent-id)))
    
    ;; Show system load statistics
    (let [stats @(health/get-system-load orchestrator)]
      (log/info "\nSystem load statistics:")
      (log/info "  Total load:" (:total-load stats))
      (log/info "  Average load:" (:average-load stats))
      (log/info "  Max load:" (:max-load stats))
      (log/info "  Min load:" (:min-load stats))
      (log/info "  Agent count:" (:agent-count stats)))))

(defn demonstrate-failure-recovery
  "Demonstrate failure detection and recovery."
  [system]
  (log/info "\n=== Failure Recovery Demonstration ===")
  
  (let [{:keys [orchestrator]} system]
    
    ;; Register a special agent for failure testing
    (register-agent-with-system system "failure-test-agent" #{:testing})
    
    ;; Start monitoring
    @(health/start-monitoring orchestrator "failure-test-agent")
    @(health/register-heartbeat orchestrator "failure-test-agent")
    
    (log/info "Agent registered and healthy")
    (log/info "Initial status:" @(health/get-health-status orchestrator "failure-test-agent"))
    
    ;; Simulate failure (stop sending heartbeats)
    (log/info "\nSimulating failure (stopping heartbeats)...")
    
    (go
      ;; Wait for failure detection
      (async/<! (async/timeout 10000))  ;; 10 seconds should trigger failure
      
      (let [status @(health/get-health-status orchestrator "failure-test-agent")]
        (log/info "Status after failure simulation:" status)
        
        (if (= :failed (:status status))
          (do
            (log/info "Agent correctly detected as failed")
            
            ;; Simulate recovery (send heartbeat)
            (log/info "\nSimulating recovery (sending heartbeat)...")
            @(health/register-heartbeat orchestrator "failure-test-agent")
            
            (async/<! (async/timeout 1000))
            
            (let [recovered-status @(health/get-health-status orchestrator "failure-test-agent")]
              (log/info "Status after recovery:" recovered-status)
              (is (= :healthy (:status recovered-status)))))
          (log/warn "Agent not detected as failed - check configuration"))))))

(defn run-complete-demo
  "Run a complete demonstration of advanced coordination features."
  []
  (log/info "=== Advanced Coordination Features Demo ===")
  (log/info "Demonstrating: Health Monitoring, Load Balancing, Failure Recovery")
  
  (try
    ;; Create the system
    (let [system (create-agent-system)]
      
      ;; Run demonstrations
      (demonstrate-health-monitoring system)
      
      ;; Wait for health monitoring demo to complete
      (async/<!! (async/timeout 12000))
      
      (demonstrate-load-balancing system)
      (demonstrate-failure-recovery system)
      
      ;; Run workload simulation
      (log/info "\n=== Workload Simulation ===")
      (simulate-agent-workload system 5000)  ;; 5 second simulation
      
      ;; Wait for simulation
      (async/<!! (async/timeout 6000))
      
      ;; Show final statistics
      (let [{:keys [orchestrator]} system
            stats @(health/get-system-load orchestrator)]
        (log/info "\n=== Final Statistics ===")
        (log/info "Total agents monitored:" (count @(:agents system)))
        (log/info "System load:" stats)
        (log/info "Failed agents:" @(health/get-failed-agents orchestrator)))
      
      (log/info "\nDemo completed successfully!"))
    
    (catch Exception e
      (log/error e "Error in advanced coordination demo")
      (throw e))))

(defn quick-demo
  "A quick demonstration of basic features."
  []
  (println "=== Quick Advanced Coordination Demo ===")
  
  (let [orchestrator (health/start-enhanced-orchestrator
                      :heartbeat-interval-ms 2000
                      :failure-threshold 2
                      :check-interval-ms 500)]
    
    ;; Register agents
    @(health/register-agent-with-balancer (:load-balancer orchestrator) "agent-a" #{:llm})
    @(health/register-agent-with-balancer (:load-balancer orchestrator) "agent-b" #{:llm})
    @(health/register-agent-with-balancer (:load-balancer orchestrator) "agent-c" #{:web-search})
    
    ;; Start health monitoring
    @(health/start-monitoring orchestrator "agent-a")
    @(health/start-monitoring orchestrator "agent-b")
    
    ;; Send heartbeats
    @(health/register-heartbeat orchestrator "agent-a")
    @(health/register-heartbeat orchestrator "agent-b")
    
    ;; Demonstrate load balancing
    (println "\nLoad balancing selections:")
    (dotimes [i 3]
      (println "  LLM task" (inc i) "->" @(health/select-agent orchestrator #{:llm})))
    
    ;; Show health status
    (println "\nHealth status:")
    (println "  Agent A:" @(health/get-health-status orchestrator "agent-a"))
    (println "  Agent B:" @(health/get-health-status orchestrator "agent-b"))
    
    ;; Show system load
    (println "\nSystem load:" @(health/get-system-load orchestrator))
    
    (println "\nQuick demo completed!")))

(comment
  ;; Run the complete demo
  (run-complete-demo)
  
  ;; Run the quick demo
  (quick-demo)
  
  ;; Test individual components
  (let [monitor (health/start-health-monitor)]
    @(health/start-monitoring monitor "test-agent")
    @(health/register-heartbeat monitor "test-agent")
    (println "Health status:" @(health/get-health-status monitor "test-agent")))
  
  (let [balancer (health/start-round-robin-load-balancer)]
    @(health/register-agent-with-balancer balancer "test-1" #{:llm})
    @(health/register-agent-with-balancer balancer "test-2" #{:llm})
    (println "Selected:" @(health/select-agent balancer #{:llm}))))