(ns examples.consensus-demo
  "Demonstration of consensus algorithms in action.
   
   Shows:
   1. Raft leader election and log replication
   2. Paxos consensus on specific values
   3. Integration with distributed coordination
   4. Fault tolerance scenarios"
  (:require
   [agent.distributed.consensus :as consensus]
   [clojure.core.async :as async :refer [go chan >! <! timeout]]
   [clojure.tools.logging :as log])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Raft Cluster Demo
;; ============================================================================

(defn create-raft-cluster
  "Create a Raft cluster with multiple nodes."
  [node-count]
  (let [nodes (map #(consensus/create-raft-node (str "raft-node-" %)) (range node-count))]
    ;; Set up peer relationships (simplified - in real implementation would use network addresses)
    (doseq [node nodes]
      (doseq [peer nodes
              :when (not= node peer)]
        (consensus/add-peer node (:node-id peer) (str "localhost:" (+ 9000 (rand-int 1000))))))
    
    nodes))

(defn demo-raft-election
  "Demonstrate Raft leader election."
  []
  (log/info "=== Raft Leader Election Demo ===")
  
  (let [nodes (create-raft-cluster 3)]
    
    ;; Start all nodes
    (doseq [node nodes]
      (consensus/start node))
    
    (log/info "All Raft nodes started. Waiting for election...")
    
    ;; Wait for election
    (Thread/sleep 3000)
    
    ;; Check leader status
    (doseq [node nodes]
      (let [state (consensus/get-consensus-state node)]
        (log/info "Node state:" {:node-id (:node-id node)
                                   :role (:role state)
                                   :leader (:leader state)
                                   :term (:current-term state)})))
    
    nodes))

(defn demo-raft-log-replication
  "Demonstrate Raft log replication."
  [raft-nodes]
  (log/info "=== Raft Log Replication Demo ===")
  
  ;; Find leader
  (let [leader (first (filter #(= :leader (:role (consensus/get-consensus-state %))) raft-nodes))]
    
    (when leader
      (log/info "Found leader:" (:node-id leader))
      
      ;; Submit commands through leader
      (doseq [i (range 3)]
        (let [command {:type :task
                       :id (str "task-" i)
                       :description (str "Test task " i)}]
          (log/info "Submitting command:" command)
          (when-let [result (consensus/propose leader command)]
            (log/info "Command proposed:" result))
          (Thread/sleep 1000))))
    
    ;; Check logs on all nodes
    (doseq [node raft-nodes]
      (let [state (consensus/get-consensus-state node)]
        (log/info "Node log status:" {:node-id (:node-id node)
                                        :log-length (count (:log state))
                                        :commit-index (:commit-index state)})))))

;; ============================================================================
;; Paxos Consensus Demo
;; ============================================================================

(defn create-paxos-cluster
  "Create a Paxos cluster for consensus."
  [node-count]
  (let [nodes (map #(consensus/create-paxos-node (str "paxos-node-" %)) (range node-count))]
    
    ;; Set up acceptors (all nodes are acceptors in this demo)
    (doseq [node nodes]
      (doseq [acceptor nodes
              :when (not= node acceptor)]
        (consensus/add-peer node (:node-id acceptor) (str "localhost:" (+ 9100 (rand-int 1000))))))
    
    nodes))

(defn demo-paxos-consensus
  "Demonstrate Paxos consensus on a value."
  []
  (log/info "=== Paxos Consensus Demo ===")
  
  (let [nodes (create-paxos-cluster 3)
        values ["config-change-1" "leader-election" "task-assignment"]]
    
    ;; Start all nodes
    (doseq [node nodes]
      (consensus/start node))
    
    (log/info "All Paxos nodes started. Proposing values...")
    
    ;; Have each node propose a value
    (doseq [[node value] (map vector nodes values)]
      (go
        (log/info "Node" (:node-id node) "proposing value:" value)
        (when-let [result (consensus/propose node value)]
          (log/info "Consensus result:" result))))
    
    ;; Wait for consensus
    (Thread/sleep 5000)
    
    nodes))

;; ============================================================================
;; Fault Tolerance Demo
;; ============================================================================

(defn demo-fault-tolerance
  "Demonstrate consensus system fault tolerance."
  []
  (log/info "=== Fault Tolerance Demo ===")
  
  ;; Create Raft cluster
  (let [raft-nodes (create-raft-cluster 5)]
    
    ;; Start all nodes
    (doseq [node raft-nodes]
      (consensus/start node))
    
    (Thread/sleep 2000)
    
    ;; Simulate leader failure
    (let [leader (first (filter #(= :leader (:role (consensus/get-consensus-state %))) raft-nodes))]
      (when leader
        (log/info "Simulating leader failure:" (:node-id leader))
        (consensus/stop leader)
        
        (Thread/sleep 3000)
        
        ;; Check new leader election
        (let [remaining-nodes (remove #(= % leader) raft-nodes)
              new-leader (first (filter #(= :leader (:role (consensus/get-consensus-state %))) remaining-nodes))]
          
          (if new-leader
            (log/info "New leader elected:" (:node-id new-leader))
            (log/error "No new leader elected!"))
          
          ;; Restart failed node
          (log/info "Restarting failed node...")
          (consensus/start leader)
          
          (Thread/sleep 2000)
          
          ;; Check node rejoins as follower
          (let [state (consensus/get-consensus-state leader)]
            (log/info "Restarted node state:" {:role (:role state)
                                                 :term (:current-term state)})))))))

;; ============================================================================
;; Integration with Coordinator
;; ============================================================================

(defn demo-coordinator-consensus
  "Demonstrate consensus integration with coordinator."
  []
  (log/info "=== Coordinator Consensus Integration ===")
  
  ;; Create consensus-backed coordinator
  (let [raft-node (consensus/create-raft-node "coordinator-raft")
        client (consensus/->ConsensusClient raft-node)]
    
    (consensus/start raft-node)
    
    ;; Simulate coordinator operations requiring consensus
    (let [operations [{:type :register-agent
                       :agent-id "agent-1"
                       :capabilities #{:research :analysis}}
                      {:type :assign-task
                       :task-id "task-1"
                       :agent-id "agent-1"}
                      {:type :update-config
                       :config {:max-agents 10
                                :timeout-ms 5000}}]]
      
      (doseq [op operations]
        (log/info "Coordinator operation requiring consensus:" op)
        
        ;; In real implementation, coordinator would use consensus for these operations
        (go
          (Thread/sleep (rand-int 1000))
          (when-let [result (consensus/submit-command client op)]
            (log/info "Consensus reached for operation:" result)))))))

;; ============================================================================
;; Main Demo Execution
;; ============================================================================

(defn -main
  "Run consensus algorithm demonstrations."
  [& args]
  (println "=== Consensus Algorithms Demo ===")
  (println)
  
  ;; Demo 1: Raft
  (println "1. Demonstrating Raft leader election...")
  (let [raft-nodes (demo-raft-election)]
    (demo-raft-log-replication raft-nodes))
  
  (println)
  
  ;; Demo 2: Paxos
  (println "2. Demonstrating Paxos consensus...")
  (demo-paxos-consensus)
  
  (println)
  
  ;; Demo 3: Fault tolerance
  (println "3. Demonstrating fault tolerance...")
  (demo-fault-tolerance)
  
  (println)
  
  ;; Demo 4: Integration
  (println "4. Demonstrating coordinator integration...")
  (demo-coordinator-consensus)
  
  (println)
  (println "=== Demo Complete ===")
  
  ;; Clean shutdown
  (System/exit 0))

(comment
  ;; Interactive exploration
  
  ;; Run Raft demo
  (let [nodes (demo-raft-election)]
    (demo-raft-log-replication nodes))
  
  ;; Run Paxos demo
  (demo-paxos-consensus)
  
  ;; Test fault tolerance
  (demo-fault-tolerance)
  
  ;; Run full demo
  (-main))
