(ns agent.distributed.health
  "Health monitoring and failure detection for distributed agents."
  (:require
   [clojure.core.async :as async :refer [go chan >! <! >!! <!! alts! timeout]]
   [clojure.tools.logging :as log]
   [manifold.deferred :as d]
   [clojure.set :as set]))

;; ============================================================================
;; Health Monitoring Protocol
;; ============================================================================

(defprotocol IHealthMonitor
  "Protocol for monitoring agent health and detecting failures."
  
  (start-monitoring [this agent-id]
    "Start monitoring an agent.
    
    Args:
      agent-id: Identifier of agent to monitor
      
    Returns:
      Deferred that completes when monitoring starts")
  
  (stop-monitoring [this agent-id]
    "Stop monitoring an agent.
    
    Args:
      agent-id: Identifier of agent to stop monitoring
      
    Returns:
      Deferred that completes when monitoring stops")
  
  (get-health-status [this agent-id]
    "Get current health status of an agent.
    
    Args:
      agent-id: Identifier of agent to check
      
    Returns:
      Deferred that completes with health status map")
  
  (register-heartbeat [this agent-id]
    "Register a heartbeat from an agent.
    
    Args:
      agent-id: Identifier of agent sending heartbeat
      
    Returns:
      Deferred that completes with acknowledgement")
  
  (get-failed-agents [this]
    "Get list of agents currently marked as failed.
    
    Returns:
      Deferred that completes with set of failed agent IDs")
  
  (add-health-listener [this listener-fn]
    "Add a listener for health status changes.
    
    Args:
      listener-fn: Function called with [agent-id old-status new-status]
      
    Returns:
      Deferred that completes with listener ID")
  
  (remove-health-listener [this listener-id]
    "Remove a health status listener.
    
    Args:
      listener-id: ID of listener to remove
      
    Returns:
      Deferred that completes when listener removed"))

;; ============================================================================
;; Basic Health Monitor Implementation
;; ============================================================================

(defrecord BasicHealthMonitor [heartbeat-chan command-chan state listeners]
  IHealthMonitor
  
  (start-monitoring [this agent-id]
    (d/future
      (log/info "Starting health monitoring for agent" agent-id)
      (let [agent-state {:agent-id agent-id
                         :status :healthy
                         :last-heartbeat (java.time.Instant/now)
                         :missed-heartbeats 0
                         :monitoring-since (java.time.Instant/now)}]
        (swap! state assoc agent-id agent-state)
        (async/>!! command-chan [:start-monitoring agent-id])
        agent-state)))
  
  (stop-monitoring [this agent-id]
    (d/future
      (log/info "Stopping health monitoring for agent" agent-id)
      (swap! state dissoc agent-id)
      (async/>!! command-chan [:stop-monitoring agent-id])
      {:agent-id agent-id :stopped true}))
  
  (get-health-status [this agent-id]
    (d/future
      (if-let [agent-state (get @state agent-id)]
        agent-state
        {:agent-id agent-id :error :not-monitored})))
  
  (register-heartbeat [this agent-id]
    (d/future
      (log/debug "Heartbeat received from agent" agent-id)
      (let [now (java.time.Instant/now)
            old-state (get @state agent-id)]
        (when old-state
          (let [new-state (assoc old-state
                                 :last-heartbeat now
                                 :missed-heartbeats 0
                                 :status :healthy)]
            (swap! state assoc agent-id new-state)
            (when (not= (:status old-state) (:status new-state))
              (doseq [[_ listener-fn] @listeners]
                (try
                  (listener-fn agent-id (:status old-state) (:status new-state))
                  (catch Exception e
                    (log/error e "Error in health listener")))))
            new-state)))))
  
  (get-failed-agents [this]
    (d/future
      (let [failed (filter (fn [[_ agent-state]]
                             (= :failed (:status agent-state)))
                           @state)]
        (set (map first failed)))))
  
  (add-health-listener [this listener-fn]
    (d/future
      (let [listener-id (str (java.util.UUID/randomUUID))]
        (swap! listeners assoc listener-id listener-fn)
        listener-id)))
  
  (remove-health-listener [this listener-id]
    (d/future
      (swap! listeners dissoc listener-id)
      {:removed listener-id}))
  
  ;; Private methods
  Object
  (toString [this]
    (str "BasicHealthMonitor[monitoring=" (count @state) ", failed=" (count @(get-failed-agents this)) "]")))

(defn start-health-monitor
  "Start a health monitor with configurable parameters.
  
  Options:
    :heartbeat-interval-ms - How often to expect heartbeats (default: 5000)
    :failure-threshold - Number of missed heartbeats before failure (default: 3)
    :check-interval-ms - How often to check for failures (default: 1000)
    
  Returns:
    Health monitor instance"
  [& {:keys [heartbeat-interval-ms failure-threshold check-interval-ms]
      :or {heartbeat-interval-ms 5000
           failure-threshold 3
           check-interval-ms 1000}}]
  
  (let [heartbeat-chan (async/chan 100)
        command-chan (async/chan 100)
        state (atom {})
        listeners (atom {})
        monitor (->BasicHealthMonitor heartbeat-chan command-chan state listeners)]
    
    ;; Heartbeat processing loop
    (go
      (loop []
        (when-let [[agent-id heartbeat-time] (<! heartbeat-chan)]
          (log/debug "Processing heartbeat from" agent-id "at" heartbeat-time)
          (recur))))
    
    ;; Health checking loop
    (go
      (loop []
        (<! (timeout check-interval-ms))
        
        (let [now (java.time.Instant/now)
              threshold-ms heartbeat-interval-ms]
          
          (doseq [[agent-id agent-state] @state]
            (let [last-heartbeat (:last-heartbeat agent-state)
                  time-since-heartbeat (.toMillis (java.time.Duration/between last-heartbeat now))
                  missed-heartbeats (if (> time-since-heartbeat threshold-ms)
                                      (inc (:missed-heartbeats agent-state))
                                      0)
                  
                  new-status (if (>= missed-heartbeats failure-threshold)
                               :failed
                               :healthy)
                  
                  new-state (assoc agent-state
                                   :missed-heartbeats missed-heartbeats
                                   :status new-status)]
              
              (when (not= (:status agent-state) new-status)
                (swap! state assoc agent-id new-state)
                (log/warn "Agent" agent-id "status changed from" (:status agent-state) "to" new-status
                         "(missed heartbeats:" missed-heartbeats ")")
                
                ;; Notify listeners
                (doseq [[_ listener-fn] @listeners]
                  (try
                    (listener-fn agent-id (:status agent-state) new-status)
                    (catch Exception e
                      (log/error e "Error in health listener for agent" agent-id))))))))
        
        (recur)))
    
    ;; Command processing loop
    (go
      (loop []
        (when-let [[op data] (<! command-chan)]
          (case op
            :start-monitoring
            (let [agent-id data]
              (log/debug "Started monitoring for agent" agent-id))
            
            :stop-monitoring
            (let [agent-id data]
              (log/debug "Stopped monitoring for agent" agent-id)))
          (recur))))
    
    monitor))

;; ============================================================================
;; Load Balancer Implementation
;; ============================================================================

(defprotocol ILoadBalancer
  "Protocol for load balancing across multiple agents."
  
  (select-agent [this capabilities]
    "Select an agent for a task based on capabilities and load.
    
    Args:
      capabilities: Set of required capabilities
      
    Returns:
      Deferred that completes with selected agent ID or nil")
  
  (update-agent-load [this agent-id load-change]
    "Update the load of an agent.
    
    Args:
      agent-id: Identifier of agent
      load-change: Change in load (positive = increase, negative = decrease)
      
    Returns:
      Deferred that completes with updated load")
  
  (get-agent-load [this agent-id]
    "Get current load of an agent.
    
    Args:
      agent-id: Identifier of agent
      
    Returns:
      Deferred that completes with load value")
  
  (get-system-load [this]
    "Get overall system load statistics.
    
    Returns:
      Deferred that completes with load statistics map"))

(defrecord RoundRobinLoadBalancer [agent-queue load-map capabilities-map]
  ILoadBalancer
  
  (select-agent [this required-capabilities]
    (d/future
      (let [suitable-agents (filter (fn [agent-id]
                                      (set/subset? required-capabilities
                                                   (get @capabilities-map agent-id #{})))
                                    (seq @agent-queue))]
        (when (seq suitable-agents)
          (let [selected (first suitable-agents)
                ;; Rotate queue
                _ (swap! agent-queue (fn [q]
                                       (let [without-selected (remove #{selected} q)]
                                         (conj (vec without-selected) selected))))]
            selected)))))
  
  (update-agent-load [this agent-id load-change]
    (d/future
      (swap! load-map update agent-id (fn [current] (+ (or current 0) load-change)))
      (get @load-map agent-id 0)))
  
  (get-agent-load [this agent-id]
    (d/future
      (get @load-map agent-id 0)))
  
  (get-system-load [this]
    (d/future
      (let [loads (vals @load-map)
            total-load (reduce + loads)
            agent-count (count loads)]
        {:total-load total-load
         :average-load (if (zero? agent-count) 0 (/ total-load agent-count))
         :max-load (if (seq loads) (apply max loads) 0)
         :min-load (if (seq loads) (apply min loads) 0)
         :agent-count agent-count}))))

(defn start-round-robin-load-balancer
  "Start a round-robin load balancer.
  
  Returns:
    Load balancer instance"
  []
  (->RoundRobinLoadBalancer (atom []) (atom {}) (atom {})))

(defn register-agent-with-balancer
  "Register an agent with a load balancer.
  
  Args:
    balancer: Load balancer instance
    agent-id: Agent identifier
    capabilities: Set of agent capabilities
    
  Returns:
    Deferred that completes when agent is registered"
  [balancer agent-id capabilities]
  (d/future
    (swap! (:agent-queue balancer) conj agent-id)
    (swap! (:capabilities-map balancer) assoc agent-id (set capabilities))
    (swap! (:load-map balancer) assoc agent-id 0)
    {:agent-id agent-id :registered true}))

(defn deregister-agent-from-balancer
  "Deregister an agent from a load balancer.
  
  Args:
    balancer: Load balancer instance
    agent-id: Agent identifier
    
  Returns:
    Deferred that completes when agent is deregistered"
  [balancer agent-id]
  (d/future
    (swap! (:agent-queue balancer) (fn [q] (remove #{agent-id} q)))
    (swap! (:capabilities-map balancer) dissoc agent-id)
    (swap! (:load-map balancer) dissoc agent-id)
    {:agent-id agent-id :deregistered true}))

;; ============================================================================
;; Enhanced Orchestrator with Health Monitoring and Load Balancing
;; ============================================================================

(defrecord EnhancedOrchestrator [coordinator health-monitor load-balancer]
  IHealthMonitor
  (start-monitoring [this agent-id]
    (start-monitoring health-monitor agent-id))
  
  (stop-monitoring [this agent-id]
    (stop-monitoring health-monitor agent-id))
  
  (get-health-status [this agent-id]
    (get-health-status health-monitor agent-id))
  
  (register-heartbeat [this agent-id]
    (register-heartbeat health-monitor agent-id))
  
  (get-failed-agents [this]
    (get-failed-agents health-monitor))
  
  (add-health-listener [this listener-fn]
    (add-health-listener health-monitor listener-fn))
  
  (remove-health-listener [this listener-id]
    (remove-health-listener health-monitor listener-id))
  
  ILoadBalancer
  (select-agent [this capabilities]
    (select-agent load-balancer capabilities))
  
  (update-agent-load [this agent-id load-change]
    (update-agent-load load-balancer agent-id load-change))
  
  (get-agent-load [this agent-id]
    (get-agent-load load-balancer agent-id))
  
  (get-system-load [this]
    (get-system-load load-balancer)))

(defn start-enhanced-orchestrator
  "Start an enhanced orchestrator with health monitoring and load balancing.
  
  Options:
    :heartbeat-interval-ms - Heartbeat interval for health monitor
    :failure-threshold - Failure threshold for health monitor
    :check-interval-ms - Check interval for health monitor
    
  Returns:
    Enhanced orchestrator instance"
  [& {:keys [heartbeat-interval-ms failure-threshold check-interval-ms]
      :or {heartbeat-interval-ms 5000
           failure-threshold 3
           check-interval-ms 1000}}]
  
  (let [health-monitor (start-health-monitor
                        :heartbeat-interval-ms heartbeat-interval-ms
                        :failure-threshold failure-threshold
                        :check-interval-ms check-interval-ms)
        load-balancer (start-round-robin-load-balancer)]
    
    (->EnhancedOrchestrator nil health-monitor load-balancer)))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Example 1: Basic health monitoring
  (let [monitor (start-health-monitor)]
    
    ;; Start monitoring agents
    @(start-monitoring monitor "agent-1")
    @(start-monitoring monitor "agent-2")
    
    ;; Register heartbeats
    @(register-heartbeat monitor "agent-1")
    @(register-heartbeat monitor "agent-2")
    
    ;; Check health status
    (println "Agent-1 health:" @(get-health-status monitor "agent-1"))
    (println "Agent-2 health:" @(get-health-status monitor "agent-2"))
    
    ;; Add health listener
    (let [listener-id @(add-health-listener monitor
                                            (fn [agent-id old-status new-status]
                                              (println "Health change:" agent-id old-status "->" new-status)))]
      
      ;; Wait and check for failures (simulate missed heartbeats)
      (async/<!! (timeout 7000))
      (println "Failed agents:" @(get-failed-agents monitor))
      
      ;; Remove listener
      @(remove-health-listener monitor listener-id))
    
    ;; Stop monitoring
    @(stop-monitoring monitor "agent-1")
    @(stop-monitoring monitor "agent-2"))
  
  ;; Example 2: Load balancing
  (let [balancer (start-round-robin-load-balancer)]
    
    ;; Register agents with capabilities
    @(register-agent-with-balancer balancer "llm-1" #{:llm :reasoning})
    @(register-agent-with-balancer balancer "llm-2" #{:llm :creative})
    @(register-agent-with-balancer balancer "web-1" #{:web-search})
    
    ;; Select agents for tasks
    (println "Selected for LLM task:" @(select-agent balancer #{:llm}))
    (println "Selected for web task:" @(select-agent balancer #{:web-search}))
    
    ;; Update loads
    @(update-agent-load balancer "llm-1" 1)
    @(update-agent-load balancer "llm-2" 2)
    
    ;; Check system load
    (println "System load:" @(get-system-load balancer))
    
    ;; Deregister agent
    @(deregister-agent-from-balancer balancer "web-1"))
  
  ;; Example 3: Enhanced orchestrator
  (let [orchestrator (start-enhanced-orchestrator)]
    
    ;; Use health monitoring features
    @(start-monitoring orchestrator "agent-1")
    @(register-heartbeat orchestrator "agent-1")
    
    ;; Use load balancing features
    @(register-agent-with-balancer (:load-balancer orchestrator) "agent-1" #{:llm})
    @(register-agent-with-balancer (:load-balancer orchestrator) "agent-2" #{:web-search})
    
    (println "Selected agent:" @(select-agent orchestrator #{:llm}))
    (println "System load:" @(get-system-load orchestrator))))