(ns agent.distributed.checkpoint
  "Checkpointing and recovery mechanisms for distributed agents."
  (:require
   [clojure.core.async :as async :refer [go chan >! <! >!! <!! alts! timeout]]
   [clojure.tools.logging :as log]
   [manifold.deferred :as d]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; ============================================================================
;; Checkpoint Protocol
;; ============================================================================

(defprotocol ICheckpointable
  "Protocol for checkpointing and restoring state."
  
  (create-checkpoint [this checkpoint-id]
    "Create a checkpoint of current state.
    
    Args:
      checkpoint-id: Unique identifier for the checkpoint
      
    Returns:
      Deferred that completes with checkpoint info")
  
  (restore-checkpoint [this checkpoint-id]
    "Restore state from checkpoint.
    
    Args:
      checkpoint-id: Identifier of checkpoint to restore
      
    Returns:
      Deferred that completes with restoration result")
  
  (list-checkpoints [this]
    "List available checkpoints.
    
    Returns:
      Deferred that completes with list of checkpoint info")
  
  (delete-checkpoint [this checkpoint-id]
    "Delete a checkpoint.
    
    Args:
      checkpoint-id: Identifier of checkpoint to delete
      
    Returns:
      Deferred that completes with deletion result")
  
  (get-checkpoint-info [this checkpoint-id]
    "Get information about a checkpoint.
    
    Args:
      checkpoint-id: Identifier of checkpoint
      
    Returns:
      Deferred that completes with checkpoint info"))

;; ============================================================================
;; Storage Backend Protocol
;; ============================================================================

(defprotocol ICheckpointStorage
  "Protocol for checkpoint storage backends."
  
  (save-checkpoint [this checkpoint-id data]
    "Save checkpoint data.
    
    Args:
      checkpoint-id: Unique identifier for checkpoint
      data: Checkpoint data to save
      
    Returns:
      Deferred that completes when saved")
  
  (load-checkpoint [this checkpoint-id]
    "Load checkpoint data.
    
    Args:
      checkpoint-id: Identifier of checkpoint to load
      
    Returns:
      Deferred that completes with checkpoint data or nil")
  
  (list-checkpoints [this]
    "List all checkpoints in storage.
    
    Returns:
      Deferred that completes with list of checkpoint IDs")
  
  (delete-checkpoint [this checkpoint-id]
    "Delete a checkpoint.
    
    Args:
      checkpoint-id: Identifier of checkpoint to delete
      
    Returns:
      Deferred that completes when deleted")
  
  (cleanup-old-checkpoints [this max-age-ms]
    "Clean up checkpoints older than specified age.
    
    Args:
      max-age-ms: Maximum age in milliseconds
      
    Returns:
      Deferred that completes with cleanup result"))

;; ============================================================================
;; Filesystem Storage Implementation
;; ============================================================================

(defrecord FilesystemStorage [base-dir]
  ICheckpointStorage
  
  (save-checkpoint [this checkpoint-id data]
    (d/future
      (let [checkpoint-file (io/file base-dir (str checkpoint-id ".edn"))
            parent-dir (.getParentFile checkpoint-file)]
        
        ;; Create directory if it doesn't exist
        (when (and parent-dir (not (.exists parent-dir)))
          (.mkdirs parent-dir))
        
        ;; Write checkpoint data
        (spit checkpoint-file (pr-str data))
        {:checkpoint-id checkpoint-id :saved true :path (.getPath checkpoint-file)})))
  
  (load-checkpoint [this checkpoint-id]
    (d/future
      (let [checkpoint-file (io/file base-dir (str checkpoint-id ".edn"))]
        (if (.exists checkpoint-file)
          (try
            (let [data (edn/read-string (slurp checkpoint-file))]
              {:checkpoint-id checkpoint-id :data data :loaded true})
            (catch Exception e
              (log/error e "Failed to load checkpoint" checkpoint-id)
              {:checkpoint-id checkpoint-id :error :load-failed :exception e}))
          {:checkpoint-id checkpoint-id :error :not-found}))))
  
  (list-checkpoints [this]
    (d/future
      (let [dir (io/file base-dir)]
        (if (.exists dir)
          (->> (.listFiles dir)
               (filter #(str/ends-with? (.getName %) ".edn"))
               (map #(str/replace (.getName %) #"\.edn$" ""))
               (sort)
               (vec))
          []))))
  
  (delete-checkpoint [this checkpoint-id]
    (d/future
      (let [checkpoint-file (io/file base-dir (str checkpoint-id ".edn"))]
        (if (.exists checkpoint-file)
          (do
            (.delete checkpoint-file)
            {:checkpoint-id checkpoint-id :deleted true})
          {:checkpoint-id checkpoint-id :error :not-found}))))
  
  (cleanup-old-checkpoints [this max-age-ms]
    (d/future
      (let [cutoff (- (System/currentTimeMillis) max-age-ms)
            dir (io/file base-dir)]
        (if (.exists dir)
          (let [files (->> (.listFiles dir)
                           (filter #(str/ends-with? (.getName %) ".edn"))
                           (filter #(> cutoff (.lastModified %))))
                deleted (count files)]
            (doseq [file files]
              (.delete file))
            {:deleted deleted :total (count files)})
          {:deleted 0 :total 0})))))

(defn start-filesystem-storage
  "Start a filesystem-based checkpoint storage.
  
  Args:
    base-dir: Base directory for checkpoint storage
    
  Returns:
    Filesystem storage instance"
  [base-dir]
  (let [dir (io/file base-dir)]
    (when (not (.exists dir))
      (.mkdirs dir))
    (->FilesystemStorage base-dir)))

;; ============================================================================
;; Agent Checkpoint Implementation
;; ============================================================================

(defrecord CheckpointableAgent [agent-state storage agent-id]
  ICheckpointable
  
  (create-checkpoint [this checkpoint-id]
    (d/future
      (log/info "Creating checkpoint for agent" agent-id "->" checkpoint-id)
      (let [state-snapshot @agent-state
            checkpoint-data {:checkpoint-id checkpoint-id
                             :agent-id agent-id
                             :timestamp (java.time.Instant/now)
                             :state state-snapshot
                             :type :agent-checkpoint}]
        
        @(save-checkpoint storage checkpoint-id checkpoint-data)
        checkpoint-data)))
  
  (restore-checkpoint [this checkpoint-id]
    (d/future
      (log/info "Restoring agent" agent-id "from checkpoint" checkpoint-id)
      (let [result @(load-checkpoint storage checkpoint-id)]
        (if (:data result)
          (let [checkpoint-data (:data result)]
            (reset! agent-state (:state checkpoint-data))
            {:restored checkpoint-id
             :agent-id agent-id
             :success true
             :timestamp (:timestamp checkpoint-data)})
          {:error :checkpoint-not-found
           :checkpoint-id checkpoint-id
           :agent-id agent-id}))))
  
  (list-checkpoints [this]
    (d/future
      (let [all-checkpoints @(list-checkpoints storage)
            agent-checkpoints (filter #(str/starts-with? % (str agent-id "-")) all-checkpoints)]
        (vec agent-checkpoints))))
  
  (delete-checkpoint [this checkpoint-id]
    (delete-checkpoint storage checkpoint-id))
  
  (get-checkpoint-info [this checkpoint-id]
    (load-checkpoint storage checkpoint-id)))

(defn start-checkpointable-agent
  "Start a checkpointable agent.
  
  Args:
    agent-id: Unique identifier for the agent
    initial-state: Initial agent state
    storage-dir: Directory for checkpoint storage
    
  Returns:
    Checkpointable agent instance"
  [agent-id initial-state storage-dir]
  (let [agent-dir (io/file storage-dir "agents" agent-id)
        storage (start-filesystem-storage (.getPath agent-dir))
        agent-state (atom initial-state)]
    (->CheckpointableAgent agent-state storage agent-id)))

;; ============================================================================
;; Orchestrator Checkpoint Implementation
;; ============================================================================

(defrecord CheckpointableOrchestrator [orchestrator-state storage agents checkpoint-chan]
  ICheckpointable
  
  (create-checkpoint [this checkpoint-id]
    (d/future
      (log/info "Creating coordinated checkpoint ->" checkpoint-id)
      
      ;; 1. Create orchestrator checkpoint
      (let [orchestrator-snapshot @orchestrator-state
            orchestrator-checkpoint {:checkpoint-id checkpoint-id
                                     :timestamp (java.time.Instant/now)
                                     :state orchestrator-snapshot
                                     :type :orchestrator-checkpoint
                                     :agent-count (count @agents)}]
        
        ;; 2. Save orchestrator checkpoint
        @(save-checkpoint storage checkpoint-id orchestrator-checkpoint)
        
        ;; 3. Notify agents to create their own checkpoints (async)
        (async/>!! checkpoint-chan [:create-checkpoints checkpoint-id])
        
        {:checkpoint-id checkpoint-id
         :orchestrator-checkpoint orchestrator-checkpoint
         :coordinated true
         :agent-count (count @agents)})))
  
  (restore-checkpoint [this checkpoint-id]
    (d/future
      (log/info "Restoring orchestrator from checkpoint" checkpoint-id)
      
      ;; 1. Load orchestrator checkpoint
      (let [result @(load-checkpoint storage checkpoint-id)]
        (if (:data result)
          (let [checkpoint-data (:data result)]
            ;; 2. Restore orchestrator state
            (reset! orchestrator-state (:state checkpoint-data))
            
            ;; 3. Notify agents to restore (async)
            (async/>!! checkpoint-chan [:restore-checkpoints checkpoint-id])
            
            {:restored checkpoint-id
             :success true
             :timestamp (:timestamp checkpoint-data)
             :agent-count (:agent-count checkpoint-data)})
          
          {:error :checkpoint-not-found
           :checkpoint-id checkpoint-id}))))
  
  (list-checkpoints [this]
    (list-checkpoints storage))
  
  (delete-checkpoint [this checkpoint-id]
    (delete-checkpoint storage checkpoint-id))
  
  (get-checkpoint-info [this checkpoint-id]
    (load-checkpoint storage checkpoint-id))
  
  ;; Additional methods for orchestrator
  Object
  (toString [this]
    (str "CheckpointableOrchestrator[agents=" (count @agents) ", checkpoints=" (count @(list-checkpoints this)) "]")))

(defn start-checkpointable-orchestrator
  "Start a checkpointable orchestrator.
  
  Args:
    initial-state: Initial orchestrator state
    storage-dir: Directory for checkpoint storage
    
  Returns:
    Checkpointable orchestrator instance"
  [initial-state storage-dir]
  (let [orchestrator-dir (io/file storage-dir "orchestrator")
        storage (start-filesystem-storage (.getPath orchestrator-dir))
        orchestrator-state (atom initial-state)
        agents (atom {})
        checkpoint-chan (async/chan 100)]
    
    ;; Checkpoint coordination loop
    (go
      (loop []
        (when-let [[op checkpoint-id] (<! checkpoint-chan)]
          (case op
            :create-checkpoints
            (do
              (log/debug "Coordinating checkpoint creation for" checkpoint-id)
              ;; In a real implementation, this would coordinate with all agents
              )
            
            :restore-checkpoints
            (do
              (log/debug "Coordinating checkpoint restoration for" checkpoint-id)
              ;; In a real implementation, this would coordinate with all agents
              ))
          (recur))))
    
    (->CheckpointableOrchestrator orchestrator-state storage agents checkpoint-chan)))

;; ============================================================================
;; Recovery Manager
;; ============================================================================

(defrecord RecoveryManager [orchestrator agents health-monitor]
  ICheckpointable
  
  (create-checkpoint [this checkpoint-id]
    (create-checkpoint orchestrator checkpoint-id))
  
  (restore-checkpoint [this checkpoint-id]
    (restore-checkpoint orchestrator checkpoint-id))
  
  (list-checkpoints [this]
    (list-checkpoints orchestrator))
  
  (delete-checkpoint [this checkpoint-id]
    (delete-checkpoint orchestrator checkpoint-id))
  
  (get-checkpoint-info [this checkpoint-id]
    (get-checkpoint-info orchestrator checkpoint-id))
  
  ;; Recovery-specific methods
  (recover-agent-failure [this agent-id]
    (d/future
      (log/info "Recovering agent failure:" agent-id)
      
      ;; 1. Check if agent has checkpoints
      (if-let [agent (get @(:agents orchestrator) agent-id)]
        (let [checkpoints @(list-checkpoints agent)]
          (if (seq checkpoints)
            ;; 2. Restore from latest checkpoint
            (let [latest-checkpoint (last checkpoints)
                  result @(restore-checkpoint agent latest-checkpoint)]
              
              (if (:success result)
                (do
                  (log/info "Agent" agent-id "recovered from checkpoint" latest-checkpoint)
                  {:recovered agent-id
                   :from-checkpoint latest-checkpoint
                   :success true})
                
                (do
                  (log/error "Failed to restore agent" agent-id "from checkpoint" latest-checkpoint)
                  {:error :restore-failed
                   :agent-id agent-id
                   :checkpoint latest-checkpoint})))
            
            ;; 3. No checkpoints available
            (do
              (log/warn "No checkpoints available for agent" agent-id)
              {:error :no-checkpoints
               :agent-id agent-id})))
        
        ;; 4. Agent not registered
        {:error :agent-not-found
         :agent-id agent-id})))
  
  (schedule-periodic-checkpoints [this interval-ms]
    (go
      (loop []
        (<! (timeout interval-ms))
        (let [checkpoint-id (str "periodic-" (System/currentTimeMillis))]
          (log/debug "Creating periodic checkpoint:" checkpoint-id)
          @(create-checkpoint this checkpoint-id))
        (recur)))))

(defn start-recovery-manager
  "Start a recovery manager for automatic checkpointing and recovery.
  
  Args:
    orchestrator: Checkpointable orchestrator instance
    storage-dir: Directory for recovery management
    
  Returns:
    Recovery manager instance"
  [orchestrator storage-dir]
  (let [recovery-dir (io/file storage-dir "recovery")
        health-monitor (atom nil)  ;; Would integrate with actual health monitor
        agents (:agents orchestrator)]
    
    (->RecoveryManager orchestrator agents health-monitor)))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Example 1: Basic agent checkpointing
  (let [agent (start-checkpointable-agent "test-agent"
                                          {:status :idle
                                           :tasks-completed 0
                                           :current-task nil}
                                          "/tmp/checkpoints")]
    
    ;; Create a checkpoint
    (let [checkpoint @(create-checkpoint agent "checkpoint-1")]
      (println "Created checkpoint:" checkpoint))
    
    ;; Modify agent state
    (swap! (:agent-state agent) assoc :tasks-completed 5 :status :busy)
    
    ;; List checkpoints
    (println "Available checkpoints:" @(list-checkpoints agent))
    
    ;; Restore from checkpoint
    (let [result @(restore-checkpoint agent "checkpoint-1")]
      (println "Restoration result:" result)
      (println "Agent state after restore:" @(:agent-state agent))))
  
  ;; Example 2: Orchestrator checkpointing
  (let [orchestrator (start-checkpointable-orchestrator
                      {:agents {}
                       :task-queue []
                       :next-task-id 0}
                      "/tmp/checkpoints")]
    
    ;; Create coordinated checkpoint
    (let [checkpoint @(create-checkpoint orchestrator "orchestrator-checkpoint-1")]
      (println "Orchestrator checkpoint:" checkpoint))
    
    ;; List orchestrator checkpoints
    (println "Orchestrator checkpoints:" @(list-checkpoints orchestrator)))
  
  ;; Example 3: Recovery manager
  (let [orchestrator (start-checkpointable-orchestrator {} "/tmp/checkpoints")
        recovery-mgr (start-recovery-manager orchestrator "/tmp/checkpoints")]
    
    ;; Schedule periodic checkpoints (every 5 minutes)
    @(schedule-periodic-checkpoints recovery-mgr (* 5 60 1000))
    
    ;; Simulate agent recovery
    (let [result @(recover-agent-failure recovery-mgr "failed-agent")]
      (println "Recovery result:" result)))
  
  ;; Example 4: Filesystem storage operations
  (let [storage (start-filesystem-storage "/tmp/checkpoints/test")]
    
    ;; Save checkpoint
    @(save-checkpoint storage "test-1" {:data "test checkpoint"})
    
    ;; List checkpoints
    (println "Stored checkpoints:" @(list-checkpoints storage))
    
    ;; Load checkpoint
    (println "Loaded checkpoint:" @(load-checkpoint storage "test-1"))
    
    ;; Cleanup old checkpoints (older than 1 hour)
    @(cleanup-old-checkpoints storage (* 60 60 1000))))