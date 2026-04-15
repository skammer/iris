(ns examples.distributed-orchestrator
  "Example of using the basic orchestrator-worker pattern."
  (:require
   [agent.distributed.coordinator :as coord]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]))

(defn create-sample-tasks
  "Create a list of sample tasks for demonstration."
  []
  [{:task-id "task-1"
    :type :reasoning
    :payload {:question "What are the ethical implications of AI?"
              :context {:domain :ai-ethics}}
    :required-capabilities #{:llm :reasoning}}
   
   {:task-id "task-2"
    :type :web-search
    :payload {:query "latest AI research papers 2026"
              :max-results 5}
    :required-capabilities #{:web-search}}
   
   {:task-id "task-3"
    :type :data-analysis
    :payload {:dataset "sales-2026.csv"
              :analysis-type :trends}
    :required-capabilities #{:data-processing}}
   
   {:task-id "task-4"
    :type :knowledge-graph
    :payload {:operation :query
              :sparql "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10"}
    :required-capabilities #{:knowledge-graph}}])

(defn run-orchestrator-worker-example
  "Run a complete orchestrator-worker example."
  []
  (log/info "Starting orchestrator-worker example...")
  
  ;; 1. Start orchestrator
  (let [orchestrator (coord/start-orchestrator)
        
        ;; 2. Start worker agents with different capabilities
        workers [(coord/start-agent-node "llm-worker-1" #{:llm :reasoning})
                 (coord/start-agent-node "llm-worker-2" #{:llm :creative-writing})
                 (coord/start-agent-node "web-worker-1" #{:web-search :data-processing})
                 (coord/start-agent-node "kg-worker-1" #{:knowledge-graph :reasoning})]
        
        coordinator-url "coordinator://localhost:8080"
        
        ;; 3. Sample tasks
        tasks (create-sample-tasks)]
    
    (try
      (log/info "Connecting workers to orchestrator...")
      
      ;; Connect all workers
      (doseq [worker workers]
        @(coord/connect worker coordinator-url))
      
      ;; Register all workers with orchestrator
      (doseq [worker workers]
        (let [agent-id (-> worker :agent-id)
              capabilities (-> worker :capabilities)]
          @(coord/register-agent orchestrator agent-id capabilities)))
      
      (log/info (count workers) "workers registered and ready")
      
      ;; 4. Demonstrate task assignment
      (log/info "\n=== Task Assignment Demo ===")
      
      (doseq [task tasks]
        (let [required-caps (:required-capabilities task)
              ;; Find suitable workers
              suitable-workers @(coord/find-agent orchestrator required-caps)]
          
          (if (seq suitable-workers)
            (let [worker-id (first suitable-workers)
                  assignment @(coord/assign-task orchestrator task worker-id)]
              (log/info "Assigned task" (:task-id task) 
                       "to worker" worker-id 
                       "for capabilities" required-caps))
            (log/warn "No workers found for task" (:task-id task) 
                     "requiring" required-caps))))
      
      ;; 5. Demonstrate broadcasting
      (log/info "\n=== Broadcasting Demo ===")
      
      ;; Broadcast to all LLM workers
      (let [llm-message {:type :model-update
                         :content "New GPT-5 model weights available for download"
                         :priority :high}
            llm-filter (fn [agent-info]
                         (contains? (:capabilities agent-info) :llm))
            broadcast-result @(coord/broadcast orchestrator llm-message llm-filter)]
        
        (log/info "Broadcasted to" (:recipients broadcast-result) "LLM workers"))
      
      ;; 6. Demonstrate consensus
      (log/info "\n=== Consensus Demo ===")
      
      (let [proposal {:proposal-id "deploy-model-v1"
                      :content {:action :deploy-new-model
                                :model-id "claude-4"
                                :environment :production}}
            voters #{"llm-worker-1" "llm-worker-2" "kg-worker-1"}
            consensus-result @(coord/consensus orchestrator proposal voters)]
        
        (log/info "Consensus result for proposal" (:proposal-id proposal) ":")
        (log/info "  Approved?" (:approved? consensus-result))
        (log/info "  Approval ratio:" (:approval-ratio consensus-result))
        (log/info "  Votes:" (:votes consensus-result)))
      
      ;; 7. Demonstrate load balancing simulation
      (log/info "\n=== Load Balancing Simulation ===")
      
      ;; Simulate multiple concurrent tasks
      (let [concurrent-tasks (take 5 (repeat {:task-id "concurrent-task"
                                              :type :simple-processing
                                              :payload {:data "test"}
                                              :required-capabilities #{:llm}}))
            assignments (doall
                         (for [task concurrent-tasks
                               :let [workers @(coord/find-agent orchestrator #{:llm})]
                               :when (seq workers)]
                           (let [worker-id (rand-nth (vec workers))]
                             @(coord/assign-task orchestrator task worker-id))))]
        
        (log/info "Assigned" (count assignments) "concurrent tasks to LLM workers"))
      
      ;; 8. Clean shutdown demonstration
      (log/info "\n=== Clean Shutdown ===")
      
      (doseq [worker workers]
        (let [agent-id (-> worker :agent-id)]
          @(coord/deregister-agent orchestrator agent-id)
          @(coord/disconnect worker)
          (log/info "Worker" agent-id "deregistered and disconnected")))
      
      (log/info "\nExample completed successfully!")
      
      (catch Exception e
        (log/error e "Error in orchestrator-worker example")
        (throw e)))))

(defn simple-demo
  "A simpler demo for quick testing."
  []
  (println "=== Simple Orchestrator-Worker Demo ===")
  
  (let [orchestrator (coord/start-orchestrator)
        worker (coord/start-agent-node "demo-worker" #{:demo :testing})]
    
    (try
      ;; Connect and register
      @(coord/connect worker "demo://localhost")
      @(coord/register-agent orchestrator "demo-worker" #{:demo :testing})
      
      ;; Verify registration
      (let [workers @(coord/find-agent orchestrator #{:demo})]
        (println "Registered workers:" workers))
      
      ;; Create and assign a task
      (let [task {:task-id "demo-task"
                  :type :demo
                  :payload {:message "Hello from orchestrator!"}
                  :required-capabilities #{:demo}}
            assignment @(coord/assign-task orchestrator task "demo-worker")]
        (println "Task assigned:" assignment))
      
      ;; Cleanup
      @(coord/deregister-agent orchestrator "demo-worker")
      @(coord/disconnect worker)
      
      (println "Demo completed successfully!")
      
      (catch Exception e
        (println "Error in demo:" (.getMessage e))
        (throw e)))))

(comment
  ;; Run the full example
  (run-orchestrator-worker-example)
  
  ;; Run the simple demo
  (simple-demo)
  
  ;; Quick test of individual components
  (let [orchestrator (coord/start-orchestrator)
        worker (coord/start-agent-node "test-worker" #{:test})]
    
    @(coord/connect worker "test://localhost")
    @(coord/register-agent orchestrator "test-worker" #{:test})
    
    (println "Find test workers:" @(coord/find-agent orchestrator #{:test}))
    
    @(coord/deregister-agent orchestrator "test-worker")
    @(coord/disconnect worker)))