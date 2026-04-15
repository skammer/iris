(ns agent.distributed.consensus
  "Consensus algorithms for distributed coordination.
   
   Implements:
   - Raft consensus algorithm
   - Paxos consensus algorithm
   - Leader election
   - Log replication
   - Fault-tolerant coordination"
  (:require
   [clojure.core.async :as async :refer [go chan >! <! timeout]]
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol IConsensusNode
  "Base protocol for consensus nodes."
  
  (start [this]
    "Start the consensus node.")
  
  (stop [this]
    "Stop the consensus node.")
  
  (propose [this value]
    "Propose a value for consensus.")
  
  (get-consensus-state [this]
    "Get current consensus state.")
  
  (add-peer [this peer-id address]
    "Add a peer to the consensus cluster.")
  
  (remove-peer [this peer-id]
    "Remove a peer from the consensus cluster."))

(defprotocol IRaftNode
  "Raft-specific consensus operations."
  
  (request-vote [this candidate-id term last-log-index last-log-term]
    "Handle vote request from candidate.")
  
  (append-entries [this leader-id term prev-log-index prev-log-term entries leader-commit]
    "Handle log append request from leader.")
  
  (get-raft-state [this]
    "Get Raft-specific state."))

(defprotocol IPaxosNode
  "Paxos-specific consensus operations."
  
  (prepare [this proposal-number]
    "Handle prepare request (Phase 1).")
  
  (promise [this proposal-number accepted-proposal accepted-value]
    "Handle promise response (Phase 1).")
  
  (accept [this proposal-number value]
    "Handle accept request (Phase 2).")
  
  (accepted [this proposal-number value]
    "Handle accepted response (Phase 2)."))

(defprotocol IConsensusClient
  "Client interface to consensus system."
  
  (submit-command [this command]
    "Submit command for consensus.")
  
  (read-state [this]
    "Read current consensus state.")
  
  (get-leader [this]
    "Get current leader information."))

;; ============================================================================
;; Raft Implementation
;; ============================================================================

(defrecord RaftNode [node-id peers state log commit-index last-applied current-term voted-for]
  IConsensusNode
  IRaftNode
  
  (start [this]
    (log/info "Starting Raft node" {:node-id node-id})
    ;; Start election timer
    (reset-election-timer this)
    this)
  
  (stop [this]
    (log/info "Stopping Raft node" {:node-id node-id})
    ;; Stop all timers
    this)
  
  (propose [this value]
    (when (= :leader (:role @state))
      (let [entry {:term @current-term
                   :index (inc (count @log))
                   :command value}]
        (swap! log conj entry)
        ;; Replicate to followers
        (replicate-log this)
        {:proposed true
         :entry entry})))
  
  (request-vote [this candidate-id term last-log-index last-log-term]
    (locking state
      (cond
        ;; Reject if term is older
        (< term @current-term)
        {:vote-granted false
         :term @current-term
         :reason :stale-term}
        
        ;; Grant vote if:
        ;; 1. Haven't voted in this term OR voted for this candidate
        ;; 2. Candidate's log is at least as up-to-date
        (or (nil? @voted-for)
            (= @voted-for candidate-id)
            (> term @current-term))
        (let [log-up-to-date? (or (> last-log-term (:term (last @log)))
                                  (and (= last-log-term (:term (last @log)))
                                       (>= last-log-index (count @log))))]
          (when log-up-to-date?
            (reset! voted-for candidate-id)
            (reset! current-term term)
            {:vote-granted true
             :term term}))
        
        :else
        {:vote-granted false
         :term @current-term
         :reason :already-voted})))
  
  (append-entries [this leader-id term prev-log-index prev-log-term entries leader-commit]
    (locking state
      (cond
        ;; Reject if term is older
        (< term @current-term)
        {:success false
         :term @current-term}
        
        ;; Check log consistency
        (let [prev-entry (nth @log prev-log-index nil)]
          (when (or (nil? prev-entry)
                    (not= (:term prev-entry) prev-log-term))
            {:success false
             :term @current-term
             :reason :log-inconsistent}))
        
        :else
        (do
          ;; Update term and convert to follower
          (when (> term @current-term)
            (reset! current-term term)
            (reset! state {:role :follower}))
          
          ;; Reset election timer
          (reset-election-timer this)
          
          ;; Append entries
          (when (seq entries)
            (let [existing-index (inc prev-log-index)]
              ;; Delete conflicting entries
              (when (not= (subvec @log existing-index (min (+ existing-index (count entries))
                                                          (count @log)))
                          entries)
                (swap! log (fn [l] (vec (take existing-index l))))))
            
            ;; Append new entries
            (swap! log into entries))
          
          ;; Update commit index
          (when (and leader-commit (> leader-commit @commit-index))
            (reset! commit-index (min leader-commit (count @log))))
          
          {:success true
           :term @current-term}))))
  
  ;; Other methods would be implemented here...
  )

;; Helper functions for Raft
(defn reset-election-timer [raft-node]
  "Reset election timeout for Raft node."
  ;; Implementation would start/restart timer
  )

(defn replicate-log [raft-node]
  "Replicate log entries to followers."
  ;; Implementation would send AppendEntries to all followers
  )

(defn start-election [raft-node]
  "Start leader election process."
  ;; Implementation would convert to candidate and request votes
  )

;; ============================================================================
;; Paxos Implementation
;; ============================================================================

(defrecord PaxosNode [node-id acceptors learners proposal-counter accepted-values]
  IConsensusNode
  IPaxosNode
  
  (start [this]
    (log/info "Starting Paxos node" {:node-id node-id})
    this)
  
  (stop [this]
    (log/info "Stopping Paxos node" {:node-id node-id})
    this)
  
  (propose [this value]
    (go
      (let [proposal-n (swap! proposal-counter inc)
            ;; Phase 1: Prepare
            promises (mapv #(prepare % proposal-n) @acceptors)
            majority-promises (filter :promised promises)]
        
        (when (>= (count majority-promises) (quot (count @acceptors) 2))
          ;; Choose value (highest-numbered from promises or new value)
          (let [chosen-value (or (some :accepted-value majority-promises)
                                 value)]
            
            ;; Phase 2: Accept
            (let [accepts (mapv #(accept % proposal-n chosen-value) @acceptors)
                  majority-accepts (filter :accepted accepts)]
              
              (when (>= (count majority-accepts) (quot (count @acceptors) 2))
                ;; Value chosen, notify learners
                (doseq [learner @learners]
                  (accepted learner proposal-n chosen-value))
                
                {:chosen true
                 :value chosen-value
                 :proposal-n proposal-n})))))))
  
  (prepare [this proposal-number]
    (locking accepted-values
      (let [highest-promised (apply max (keys @accepted-values))
            highest-accepted (get @accepted-values highest-promised)]
        
        (if (> proposal-number highest-promised)
          (do
            (swap! accepted-values assoc proposal-number nil) ;; Promise not to accept lower
            {:promised true
             :proposal-number proposal-number
             :accepted-proposal highest-promised
             :accepted-value (:value highest-accepted)})
          {:promised false
           :highest-promised highest-promised}))))
  
  (accept [this proposal-number value]
    (locking accepted-values
      (let [promised-number (some->> (keys @accepted-values)
                                     (filter #(nil? (get @accepted-values %)))
                                     (apply max))]
        (if (and promised-number (>= proposal-number promised-number))
          (do
            (swap! accepted-values assoc proposal-number {:value value})
            {:accepted true
             :proposal-number proposal-number})
          {:accepted false
           :promised-number promised-number}))))
  
  (accepted [this proposal-number value]
    (log/info "Value accepted" {:proposal-number proposal-number
                                  :value value})
    ;; Store learned value
    ))

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-raft-node
  "Create a new Raft consensus node."
  [node-id]
  (->RaftNode node-id
              (atom #{})        ;; peers
              (atom {:role :follower
                     :leader nil})
              (atom [])         ;; log
              (atom 0)          ;; commit-index
              (atom 0)          ;; last-applied
              (atom 0)          ;; current-term
              (atom nil)))      ;; voted-for

(defn create-paxos-node
  "Create a new Paxos consensus node."
  [node-id]
  (->PaxosNode node-id
               (atom #{})       ;; acceptors
               (atom #{})       ;; learners
               (atom 0)         ;; proposal-counter
               (atom {})))      ;; accepted-values

;; ============================================================================
;; Consensus Client
;; ============================================================================

(defrecord ConsensusClient [consensus-node]
  IConsensusClient
  
  (submit-command [this command]
    (propose consensus-node command))
  
  (read-state [this]
    (get-consensus-state consensus-node))
  
  (get-leader [this]
    (when-let [raft-state (get-raft-state consensus-node)]
      (:leader raft-state))))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Create Raft cluster
  (def node1 (create-raft-node "node-1"))
  (def node2 (create-raft-node "node-2"))
  (def node3 (create-raft-node "node-3"))
  
  ;; Add peers
  (add-peer node1 "node-2" "localhost:9002")
  (add-peer node1 "node-3" "localhost:9003")
  
  ;; Start nodes
  (start node1)
  (start node2)
  (start node3)
  
  ;; Create client
  (def client (->ConsensusClient node1))
  
  ;; Submit command
  (submit-command client {:type :config-change
                          :action :add-agent
                          :agent-id "new-agent"})
  
  ;; Read state
  (read-state client)
  
  ;; Create Paxos nodes
  (def paxos1 (create-paxos-node "paxos-1"))
  (def paxos2 (create-paxos-node "paxos-2"))
  (def paxos3 (create-paxos-node "paxos-3"))
  
  ;; Set up acceptors and learners
  (add-peer paxos1 "paxos-2" "localhost:9102")
  (add-peer paxos1 "paxos-3" "localhost:9103")
  
  ;; Propose value
  (propose paxos1 "consensus-value"))
