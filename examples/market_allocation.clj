(ns examples.market-allocation
  "Example of market-based task allocation in action.
   
   Demonstrates:
   1. Creating a market with multiple agents
   2. Different bid strategies
   3. Task announcement and bidding
   4. Auction closing and winner selection
   5. Market statistics and analysis"
  (:require
   [agent.distributed.market :as market]
   [clojure.core.async :as async :refer [go chan >! <! timeout]]
   [clojure.tools.logging :as log])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Setup
;; ============================================================================

(defn setup-market
  "Create and configure a market with agents."
  []
  (let [m (market/create-market)
        
        ;; Register agents with initial balances
        agents [{:id "research-agent" :balance 2000 :capabilities #{:research :analysis :summarization}}
                {:id "coding-agent" :balance 2000 :capabilities #{:coding :testing :debugging}}
                {:id "data-agent" :balance 2000 :capabilities #{:data-analysis :visualization :statistics}}
                {:id "general-agent" :balance 2000 :capabilities #{:research :coding :data-analysis}}]]
    
    ;; Register all agents
    (doseq [{:keys [id balance]} agents]
      (market/register-agent m id balance))
    
    {:market m
     :agents agents}))

(defn create-agents
  "Create market participant agents with different strategies."
  [market agent-specs]
  (map (fn [{:keys [id capabilities balance strategy-type]}]
         (let [strategy (case strategy-type
                          :cost-plus (market/->CostPlusBidStrategy 0.15)  ; 15% profit
                          :competitive (market/->CompetitiveBidStrategy [] 0.03)  ; 3% discount
                          :utility (market/->UtilityMaximizingBidStrategy 0.6 0.05)  ; 60% utility, 5% risk
                          :adaptive (market/->AdaptiveBidStrategy 0.1 0.2 []))]  ; 10% learning, 20% exploration
           
           (market/->MarketParticipantAgent
            id
            capabilities
            (atom balance)
            strategy
            market)))
       agent-specs))

;; ============================================================================
;; Task Generation
;; ============================================================================

(def task-templates
  "Templates for different types of tasks."
  [{:type :research
    :description "Research latest AI developments"
    :required-capabilities #{:research :analysis :summarization}
    :base-cost 100
    :base-reward 180}
   
   {:type :coding
    :description "Implement new feature"
    :required-capabilities #{:coding :testing}
    :base-cost 150
    :base-reward 250}
   
   {:type :data-analysis
    :description "Analyze dataset and create report"
    :required-capabilities #{:data-analysis :visualization :statistics}
    :base-cost 120
    :base-reward 200}
   
   {:type :complex
    :description "Multi-disciplinary project"
    :required-capabilities #{:research :coding :data-analysis}
    :base-cost 300
    :base-reward 500}])

(defn generate-task
  "Generate a random task from templates."
  []
  (let [template (rand-nth task-templates)
        variation (* (rand) 0.4)  ; ±20% variation
        cost (* (:base-cost template) (+ 0.8 variation))
        reward (* (:base-reward template) (+ 0.9 (* variation 1.5)))]
    
    (market/create-task
     (:description template)
     (:required-capabilities template)
     cost
     reward
     (-> (Instant/now) (.plusSeconds (+ 1800 (rand-int 7200)))))))  ; 30 min to 3 hours

;; ============================================================================
;; Simulation
;; ============================================================================

(defn simulate-market-round
  "Simulate one round of market activity."
  [market agents]
  (let [task (generate-task)
        auction (market/announce-task market task)]
    
    (log/info "New task announced:" {:description (:description task)
                                     :reward (:estimated-reward task)
                                     :cost (:estimated-cost task)})
    
    ;; Agents evaluate and bid
    (doseq [agent agents]
      (go
        (try
          (when-let [bid (market/submit-bid-for-task agent (:task-id task))]
            (log/debug "Agent bid:" {:agent-id (:agent-id bid)
                                     :amount (:amount bid)}))
          (catch Exception e
            (log/error e "Agent failed to bid")))))
    
    ;; Wait for bidding period (simulated)
    (Thread/sleep 2000)
    
    ;; Close auction
    (let [result (market/close-auction market (:task-id task))]
      (if (:awarded result)
        (do
          (log/info "Task awarded:" {:winner (:winner result)
                                     :winning-bid (:winning-bid result)})
          
          ;; Simulate task completion and payment
          (go
            (Thread/sleep 1000)  ; Simulate work time
            (let [payment (:estimated-reward task)
                  winner-agent (first (filter #(= (:agent-id %) (:winner result)) agents))]
              (when winner-agent
                (market/receive-payment winner-agent payment)
                (log/info "Payment completed:" {:agent-id (:winner result)
                                                :amount payment})))))
        
        (log/info "No bids for task"))
      
      result)))

(defn run-market-simulation
  "Run a market simulation for multiple rounds."
  [rounds]
  (let [{:keys [market]} (setup-market)
        
        ;; Create agents with different strategies
        agents (create-agents
                market
                [{:id "research-agent" :capabilities #{:research :analysis :summarization} :balance 2000 :strategy-type :cost-plus}
                 {:id "coding-agent" :capabilities #{:coding :testing :debugging} :balance 2000 :strategy-type :competitive}
                 {:id "data-agent" :capabilities #{:data-analysis :visualization :statistics} :balance 2000 :strategy-type :utility}
                 {:id "general-agent" :capabilities #{:research :coding :data-analysis} :balance 2000 :strategy-type :adaptive}])]
    
    (log/info "Starting market simulation with" (count agents) "agents")
    
    (loop [round 1
           results []]
      (if (> round rounds)
        (do
          (log/info "Simulation completed after" rounds "rounds")
          {:final-stats (market/get-market-stats market)
           :agent-balances (map #(hash-map :agent-id (:agent-id %)
                                           :balance (market/get-balance %))
                                agents)
           :results results})
        
        (do
          (log/info "=== Round" round "===")
          (let [result (simulate-market-round market agents)]
            (recur (inc round) (conj results result))))))))

;; ============================================================================
;; Market Analysis
;; ============================================================================

(defn analyze-market-performance
  "Analyze market performance from simulation results."
  [results]
  (let [total-tasks (count results)
        awarded-tasks (count (filter :awarded results))
        no-bid-tasks (- total-tasks awarded-tasks)
        winning-bids (map :winning-bid (filter :winning-bid results))
        avg-winning-bid (if (seq winning-bids)
                          (/ (reduce + winning-bids) (count winning-bids))
                          0)]
    
    {:total-tasks total-tasks
     :awarded-tasks awarded-tasks
     :no-bid-tasks no-bid-tasks
     :award-rate (if (pos? total-tasks)
                   (float (/ awarded-tasks total-tasks))
                   0)
     :avg-winning-bid avg-winning-bid
     :total-bids (reduce + (map :total-bids results))}))

(defn analyze-agent-performance
  "Analyze individual agent performance."
  [agents results]
  (let [wins (frequencies (map :winner (filter :winner results)))]
    (map (fn [agent]
           (let [agent-id (:agent-id agent)
                 win-count (get wins agent-id 0)
                 balance (market/get-balance agent)]
             {:agent-id agent-id
              :wins win-count
              :balance balance
              :profit (- balance 2000)  ; Initial balance was 2000
              :strategy (type (:strategy agent))}))
         agents)))

;; ============================================================================
;; Hybrid Orchestrator Example
;; ============================================================================

(defn create-hybrid-orchestrator
  "Create a hybrid orchestrator that uses both market and direct allocation."
  []
  (let [market (market/create-market)
        
        ;; Simple direct allocation function (for demonstration)
        direct-allocator
        (reify
          Object
          (select-agent [this capabilities]
            ;; Simple round-robin selection
            (let [agents ["direct-agent-1" "direct-agent-2" "direct-agent-3"]]
              (rand-nth agents)))
          
          (assign-task [this task agent-id]
            (log/info "Direct assignment:" {:task (:description task)
                                            :agent-id agent-id})
            true))
        
        orchestrator (market/->MarketOrchestrator
                      direct-allocator
                      market
                      0.4)]  ; 40% chance to use market
    
    {:orchestrator orchestrator
     :market market}))

(defn hybrid-allocation-example
  "Example of hybrid task allocation."
  []
  (let [{:keys [orchestrator market]} (create-hybrid-orchestrator)
        
        ;; Register some agents
        _ (market/register-agent market "market-agent-1" 1000)
        _ (market/register-agent market "market-agent-2" 1000)
        
        ;; Create some tasks
        tasks [(market/create-task "Quick analysis" #{:analysis} 50 80 (-> (Instant/now) (.plusSeconds 1800)))
               (market/create-task "Major project" #{:research :coding :analysis} 300 500 (-> (Instant/now) (.plusSeconds 7200)))
               (market/create-task "Simple fix" #{:coding} 30 50 (-> (Instant/now) (.plusSeconds 900)))]]
    
    (log/info "Starting hybrid allocation example")
    
    (doseq [task tasks]
      (let [result (market/allocate-task orchestrator task)]
        (log/info "Allocation result:" result)
        
        (when (= :market (:allocation-method result))
          ;; Simulate bidding and closing for market-allocated tasks
          (Thread/sleep 1000)
          (market/close-auction market (:task-id (:auction result))))))
    
    (log/info "Market stats:" (market/get-market-stats market))))

;; ============================================================================
;; Main Execution
;; ============================================================================

(defn -main
  "Main entry point for market allocation examples."
  [& args]
  (println "=== Market-Based Task Allocation Examples ===")
  (println)
  
  ;; Example 1: Simple market simulation
  (println "1. Running market simulation (5 rounds)...")
  (let [simulation-result (run-market-simulation 5)
        performance (analyze-market-performance (:results simulation-result))]
    
    (println "   Simulation completed!")
    (println "   Market performance:" performance)
    (println "   Final market stats:" (:final-stats simulation-result))
    (println "   Agent balances:" (:agent-balances simulation-result)))
  
  (println)
  
  ;; Example 2: Hybrid orchestrator
  (println "2. Demonstrating hybrid allocation...")
  (hybrid-allocation-example)
  
  (println)
  (println "=== Examples Complete ===")
  
  ;; Return success
  0)

(comment
  ;; Interactive exploration
  
  ;; 1. Create and explore a market
  (let [{:keys [market]} (setup-market)
        task (generate-task)]
    (market/announce-task market task)
    (market/get-market-stats market))
  
  ;; 2. Run a quick simulation
  (run-market-simulation 3)
  
  ;; 3. Test specific bid strategies
  (let [strategy (market/->CostPlusBidStrategy 0.2)
        task {:estimated-cost 100 :estimated-reward 150}
        context {:estimated-cost 100 :estimated-utility 50 :current-balance 1000}]
    (market/calculate-bid strategy task context))
  
  ;; 4. Analyze agent strategies
  (let [{:keys [market]} (setup-market)
        agents (create-agents
                market
                [{:id "test-agent" :capabilities #{:research} :balance 1000 :strategy-type :cost-plus}])
        agent (first agents)
        task (generate-task)]
    (market/evaluate-task agent task))
  
  ;; 5. Run the main examples
  (-main))