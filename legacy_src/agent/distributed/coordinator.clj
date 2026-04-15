(ns agent.distributed.coordinator
  "Distributed coordination protocols and implementations."
  (:require
   [clojure.core.async :as async :refer [go chan >! <! >!! <!! alts! timeout]]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [manifold.stream :as stream]
   [manifold.deferred :as d]))

;; ============================================================================
;; Protocols
;; ============================================================================

(defprotocol ICoordinator
  "Protocol for distributed coordination of agents."
  
  (register-agent [this agent-id capabilities]
    "Register an agent with the coordinator.
    
    Args:
      agent-id: Unique identifier for the agent
      capabilities: Set of capabilities the agent provides
      
    Returns:
      Deferred that completes with registration result")
  
  (deregister-agent [this agent-id]
    "Deregister an agent from the coordinator.
    
    Args:
      agent-id: Identifier of agent to deregister
      
    Returns:
      Deferred that completes with deregistration result")
  
  (assign-task [this task agent-id]
    "Assign a task to a specific agent.
    
    Args:
      task: Task map with :task-id, :type, :payload, etc.
      agent-id: Identifier of agent to assign task to
      
    Returns:
      Deferred that completes with assignment result")
  
  (find-agent [this capabilities]
    "Find agents with specific capabilities.
    
    Args:
      capabilities: Set of required capabilities
      
    Returns:
      Deferred that completes with list of matching agent IDs")
  
  (broadcast [this message filter-fn]
    "Broadcast a message to all agents matching filter.
    
    Args:
      message: Message to broadcast
      filter-fn: Function that takes agent metadata, returns truthy if agent should receive message
      
    Returns:
      Deferred that completes with broadcast result")
  
  (consensus [this proposal voters]
    "Reach consensus on a proposal.
    
    Args:
      proposal: Proposal map with :proposal-id, :content, etc.
      voters: Set of agent IDs participating in consensus
      
    Returns:
      Deferred that completes with consensus result"))

(defprotocol IAgentNode
  "Protocol for individual agent nodes in distributed system."
  
  (connect [this coordinator-url]
    "Connect to a coordinator.
    
    Args:
      coordinator-url: URL of coordinator to connect to
      
    Returns:
      Deferred that completes with connection result")
  
  (disconnect [this]
    "Disconnect from coordinator.
    
    Returns:
      Deferred that completes with disconnection result")
  
  (process-task [this task]
    "Process an assigned task.
    
    Args:
      task: Task to process
      
    Returns:
      Deferred that completes with task result")
  
  (receive-message [this message]
    "Receive a broadcast message.
    
    Args:
      message: Message received
      
    Returns:
      Deferred that completes with acknowledgement"))

;; ============================================================================
;; Basic Orchestrator Implementation
;; ============================================================================

(defrecord BasicOrchestrator [registry-chan command-chan state]
  ICoordinator
  
  (register-agent [this agent-id capabilities]
    (d/future
      (log/info "Registering agent" agent-id "with capabilities" capabilities)
      (let [agent-info {:agent-id agent-id
                        :capabilities (set capabilities)
                        :status :available
                        :registered-at (java.time.Instant/now)
                        :last-heartbeat (java.time.Instant/now)}]
        (async/>!! registry-chan [:register agent-info])
        agent-info)))
  
  (deregister-agent [this agent-id]
    (d/future
      (log/info "Deregistering agent" agent-id)
      (async/>!! registry-chan [:deregister agent-id])
      {:agent-id agent-id :status :deregistered}))
  
  (assign-task [this task agent-id]
    (d/future
      (log/info "Assigning task" (:task-id task) "to agent" agent-id)
      (let [assignment {:type :task-assignment
                        :task task
                        :agent-id agent-id
                        :assigned-at (java.time.Instant/now)}]
        (async/>!! command-chan [:assign assignment])
        assignment)))
  
  (find-agent [this capabilities]
    (d/future
      (let [required (set capabilities)
            agents (filter (fn [[_ info]]
                             (every? (:capabilities info) required))
                           @state)]
        (map first agents))))
  
  (broadcast [this message filter-fn]
    (d/future
      (let [recipients (filter (fn [[_ info]] (filter-fn info)) @state)]
        (log/info "Broadcasting to" (count recipients) "agents")
        (doseq [[agent-id _] recipients]
          (async/>!! command-chan [:broadcast agent-id message]))
        {:recipients (count recipients) :message message})))
  
  (consensus [this proposal voters]
    (d/future
      (log/info "Starting consensus for proposal" (:proposal-id proposal))
      ;; Simple majority consensus for now
      (let [votes-chan (async/chan (count voters))
            deadline (+ (System/currentTimeMillis) 30000) ;; 30 second timeout
            votes (atom {})]
        
        ;; Request votes from all voters
        (doseq [voter voters]
          (async/>!! command-chan [:vote-request voter proposal votes-chan]))
        
        ;; Collect votes with timeout
        (loop [remaining (count voters)
               collected 0]
          (if (or (zero? remaining) (> (System/currentTimeMillis) deadline))
            (let [vote-counts (frequencies (vals @votes))
                  total (reduce + (vals vote-counts))
                  approval (get vote-counts :approve 0)
                  ratio (if (zero? total) 0 (/ approval total))]
              {:proposal-id (:proposal-id proposal)
               :votes @votes
               :approved? (>= ratio 0.5)
               :approval-ratio ratio})
            (let [[vote channel] (async/alts! [votes-chan (timeout 1000)])]
              (when (and vote (= channel votes-chan))
                (let [[voter decision] vote]
                  (swap! votes assoc voter decision)
                  (recur (dec remaining) (inc collected))))))))))

  ;; Private methods
  Object
  (toString [this]
    (str "BasicOrchestrator[agents=" (count @state) "]")))

(defn start-orchestrator
  "Start a basic orchestrator.
  
  Returns:
    Orchestrator instance"
  []
  (let [registry-chan (async/chan 100)
        command-chan (async/chan 100)
        state (atom {})]
    
    ;; Registry management loop
    (go
      (loop []
        (when-let [[op data] (<! registry-chan)]
          (case op
            :register
            (let [{:keys [agent-id] :as agent-info} data]
              (swap! state assoc agent-id agent-info)
              (log/info "Agent registered:" agent-id))
            
            :deregister
            (do
              (swap! state dissoc data)
              (log/info "Agent deregistered:" data)))
          (recur))))
    
    ;; Command processing loop
    (go
      (loop []
        (when-let [[op data] (<! command-chan)]
          (case op
            :assign
            (let [{:keys [agent-id task]} data]
              (log/debug "Task assigned to" agent-id ":" (:task-id task)))
            
            :broadcast
            (let [[agent-id message] data]
              (log/debug "Broadcasting to" agent-id))
            
            :vote-request
            (let [[voter proposal response-chan] data]
              ;; For now, auto-approve all proposals
              (async/>! response-chan [voter :approve])))
          (recur))))
    
    (->BasicOrchestrator registry-chan command-chan state)))

;; ============================================================================
;; Basic Agent Node Implementation
;; ============================================================================

(defrecord BasicAgentNode [agent-id capabilities task-chan message-chan status]
  IAgentNode
  
  (connect [this coordinator-url]
    (d/future
      (log/info "Agent" agent-id "connecting to coordinator at" coordinator-url)
      (swap! status assoc :coordinator-url coordinator-url :connected? true)
      {:agent-id agent-id :status :connected :coordinator-url coordinator-url}))
  
  (disconnect [this]
    (d/future
      (log/info "Agent" agent-id "disconnecting")
      (swap! status assoc :connected? false :coordinator-url nil)
      {:agent-id agent-id :status :disconnected}))
  
  (process-task [this task]
    (d/future
      (log/info "Agent" agent-id "processing task" (:task-id task))
      (swap! status assoc :current-task (:task-id task) :status :busy)
      
      ;; Simulate task processing
      (async/<!! (timeout 1000))
      
      (let [result {:task-id (:task-id task)
                    :agent-id agent-id
                    :result {:answer "Task completed successfully"
                             :processed-at (java.time.Instant/now)}
                    :status :completed}]
        (swap! status assoc :current-task nil :status :available)
        result)))
  
  (receive-message [this message]
    (d/future
      (log/debug "Agent" agent-id "received message:" (:type message))
      (async/>!! message-chan message)
      {:agent-id agent-id :message-received true}))
  
  ;; Task processing loop
  Object
  (toString [this]
    (str "BasicAgentNode[" agent-id ", capabilities=" capabilities ", status=" @status "]")))

(defn start-agent-node
  "Start a basic agent node.
  
  Args:
    agent-id: Unique identifier for the agent
    capabilities: Set of capabilities this agent provides
    
  Returns:
    Agent node instance"
  [agent-id capabilities]
  (let [task-chan (async/chan 10)
        message-chan (async/chan 10)
        status (atom {:agent-id agent-id
                      :capabilities (set capabilities)
                      :status :available
                      :connected? false
                      :coordinator-url nil
                      :current-task nil})]
    
    ;; Task processing loop
    (go
      (loop []
        (when-let [task (<! task-chan)]
          (log/info "Agent" agent-id "received task:" (:task-id task))
          ;; Process task (in real implementation, this would call process-task)
          (recur))))
    
    ;; Message processing loop
    (go
      (loop []
        (when-let [message (<! message-chan)]
          (log/debug "Agent" agent-id "processing message:" (:type message))
          (recur))))
    
    (->BasicAgentNode agent-id capabilities task-chan message-chan status)))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Example: Basic orchestrator-worker setup
  
  ;; Start orchestrator
  (def orchestrator (start-orchestrator))
  
  ;; Start agent nodes
  (def agent-1 (start-agent-node "agent-1" #{:llm :reasoning}))
  (def agent-2 (start-agent-node "agent-2" #{:web-search :data-processing}))
  (def agent-3 (start-agent-node "agent-3" #{:llm :knowledge-graph}))
  
  ;; Connect agents to orchestrator
  @(connect agent-1 "coordinator://localhost:8080")
  @(connect agent-2 "coordinator://localhost:8080")
  @(connect agent-3 "coordinator://localhost:8080")
  
  ;; Register agents
  @(register-agent orchestrator "agent-1" #{:llm :reasoning})
  @(register-agent orchestrator "agent-2" #{:web-search :data-processing})
  @(register-agent orchestrator "agent-3" #{:llm :knowledge-graph})
  
  ;; Find agents with LLM capability
  @(find-agent orchestrator #{:llm})
  ;; => ("agent-1" "agent-3")
  
  ;; Create a task
  (def sample-task
    {:task-id "task-123"
     :type :complex-reasoning
     :payload {:question "What are the implications of AI safety research?"
               :context {:domain :ai-ethics}}
     :required-capabilities #{:llm :reasoning}})
  
  ;; Assign task to agent-1
  @(assign-task orchestrator sample-task "agent-1")
  
  ;; Broadcast message to all LLM agents
  @(broadcast orchestrator
              {:type :system-update
               :content "New model weights available"}
              (fn [agent-info]
                (contains? (:capabilities agent-info) :llm)))
  
  ;; Simple consensus example
  (def sample-proposal
    {:proposal-id "prop-456"
     :content {:action :deploy-new-model
               :model-id "gpt-5"}})
  
  @(consensus orchestrator sample-proposal #{"agent-1" "agent-3"})
  ;; => {:proposal-id "prop-456", :votes {"agent-1" :approve, "agent-3" :approve}, ...}
  
  ;; Cleanup
  @(deregister-agent orchestrator "agent-1")
  @(disconnect agent-1)
  )