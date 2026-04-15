# Checkpointing and Recovery Mechanisms Research
Date: 2026-04-15

## Task: Add checkpointing and recovery mechanisms (Phase 4, Task 22.2)

## Overview
Checkpointing and recovery mechanisms are essential for fault tolerance in distributed agent systems. They allow the system to recover from failures by restoring state from previously saved checkpoints.

## Checkpointing Strategies

### 1. Coordinated Checkpointing
- **Concept**: All agents checkpoint simultaneously
- **Advantages**: Consistent global state, simple recovery
- **Disadvantages**: High overhead, requires coordination
- **Use case**: Small to medium systems with strong consistency requirements

### 2. Uncoordinated Checkpointing  
- **Concept**: Each agent checkpoints independently
- **Advantages**: Low overhead, no coordination needed
- **Disadvantages**: Potential for domino effect during recovery
- **Use case**: Large systems where availability is critical

### 3. Communication-Induced Checkpointing
- **Concept**: Checkpoint triggered by message patterns
- **Advantages**: Balances consistency and performance
- **Disadvantages**: Complex implementation
- **Use case**: Systems with predictable communication patterns

### 4. Incremental Checkpointing
- **Concept**: Only save changed state since last checkpoint
- **Advantages**: Reduced storage and time overhead
- **Disadvantages**: More complex recovery logic
- **Use case**: Systems with large state but small changes

## Recovery Strategies

### 1. Rollback Recovery
- **Concept**: Restore system to last consistent checkpoint
- **Implementation**: Replay operations from checkpoint
- **Use case**: Transactional systems, database applications

### 2. Forward Recovery
- **Concept**: Repair current state without rolling back
- **Implementation**: Use redundancy or error correction
- **Use case**: Real-time systems where rollback is unacceptable

### 3. Hybrid Recovery
- **Concept**: Combine rollback and forward recovery
- **Implementation**: Rollback to checkpoint, then forward recovery
- **Use case**: Complex systems with mixed requirements

## State Management for Agents

### What to Checkpoint:
1. **Agent State**
   - Current task assignments
   - Processing results
   - Internal decision state
   - Knowledge graph updates

2. **Orchestrator State**
   - Agent registry (IDs, capabilities, status)
   - Task queue and assignments
   - Load balancing state
   - Health monitoring data

3. **Communication State**
   - Pending messages
   - Unacknowledged requests
   - Consensus votes in progress

### State Serialization Formats:
1. **EDN (Extensible Data Notation)** - Native Clojure serialization
2. **JSON** - Universal, human-readable
3. **Protocol Buffers** - Efficient binary format
4. **Transit** - Efficient Clojure/JavaScript serialization

## Implementation Design

### Checkpoint Protocol
```clojure
(defprotocol ICheckpointable
  (create-checkpoint [this checkpoint-id]
    "Create a checkpoint of current state")
  
  (restore-checkpoint [this checkpoint-id]
    "Restore state from checkpoint")
  
  (list-checkpoints [this]
    "List available checkpoints")
  
  (delete-checkpoint [this checkpoint-id]
    "Delete a checkpoint")
  
  (get-checkpoint-info [this checkpoint-id]
    "Get information about a checkpoint"))
```

### Agent Checkpoint Implementation
```clojure
(defrecord CheckpointableAgent [agent-state checkpoint-store]
  ICheckpointable
  (create-checkpoint [this checkpoint-id]
    (let [state-snapshot (serialize-state @agent-state)
          checkpoint {:id checkpoint-id
                      :timestamp (java.time.Instant/now)
                      :state state-snapshot
                      :agent-id (:id @agent-state)}]
      (save-checkpoint checkpoint-store checkpoint)
      checkpoint))
  
  (restore-checkpoint [this checkpoint-id]
    (if-let [checkpoint (load-checkpoint checkpoint-store checkpoint-id)]
      (do
        (reset! agent-state (deserialize-state (:state checkpoint)))
        {:restored checkpoint-id :success true})
      {:error :checkpoint-not-found :checkpoint-id checkpoint-id}))
  
  ;; Other methods...
  )
```

### Orchestrator Checkpoint Implementation
```clojure
(defrecord CheckpointableOrchestrator [orchestrator-state checkpoint-store agents]
  ICheckpointable
  (create-checkpoint [this checkpoint-id]
    ;; Coordinated checkpoint: checkpoint all agents first
    (let [agent-checkpoints (doall
                             (map (fn [[agent-id agent]]
                                    @(create-checkpoint agent checkpoint-id))
                                  @agents))
          
          orchestrator-checkpoint {:id checkpoint-id
                                   :timestamp (java.time.Instant/now)
                                   :state (serialize-state @orchestrator-state)
                                   :agent-checkpoints (map :id agent-checkpoints)}]
      
      (save-checkpoint checkpoint-store orchestrator-checkpoint)
      {:orchestrator-checkpoint orchestrator-checkpoint
       :agent-checkpoints agent-checkpoints}))
  
  (restore-checkpoint [this checkpoint-id]
    ;; Restore orchestrator state
    (if-let [checkpoint (load-checkpoint checkpoint-store checkpoint-id)]
      (do
        (reset! orchestrator-state (deserialize-state (:state checkpoint)))
        
        ;; Restore all agents (could be done lazily)
        (doseq [agent-id (:agent-checkpoints checkpoint)]
          (when-let [agent (get @agents agent-id)]
            @(restore-checkpoint agent checkpoint-id)))
        
        {:restored checkpoint-id :success true})
      {:error :checkpoint-not-found}))
  
  ;; Other methods...
  )
```

## Storage Backends

### 1. Local Filesystem
- **Pros**: Simple, no dependencies
- **Cons**: Not distributed, single point of failure
- **Implementation**: `java.nio.file` APIs

### 2. Database (PostgreSQL, MySQL)
- **Pros**: ACID guarantees, query capabilities
- **Cons**: Performance overhead, dependency
- **Implementation**: JDBC or next.jdbc

### 3. Distributed Storage (S3, HDFS)
- **Pros**: Scalable, durable, distributed
- **Cons**: Network latency, cost
- **Implementation**: AWS SDK, Hadoop client

### 4. In-Memory with Persistence (Redis, Memcached)
- **Pros**: Fast, simple
- **Cons**: Volatile (unless persisted), memory limits
- **Implementation**: Carmine (Redis client)

## Recovery Scenarios

### 1. Single Agent Failure
```clojure
(defn recover-agent-failure [orchestrator agent-id]
  (let [agent (get-agent orchestrator agent-id)
        latest-checkpoint (last (list-checkpoints agent))]
    
    (if latest-checkpoint
      ;; Restore from checkpoint
      (do
        (log/info "Restoring agent" agent-id "from checkpoint" (:id latest-checkpoint))
        @(restore-checkpoint agent (:id latest-checkpoint))
        
        ;; Update orchestrator state
        (update-agent-status orchestrator agent-id :recovering)
        
        {:recovered agent-id :from-checkpoint (:id latest-checkpoint)})
      
      ;; No checkpoint available
      (do
        (log/warn "No checkpoint available for agent" agent-id)
        (deregister-agent orchestrator agent-id)
        {:error :no-checkpoint :agent-id agent-id}))))
```

### 2. Orchestrator Failure
```clojure
(defn recover-orchestrator-failure [orchestrator]
  (let [latest-checkpoint (last (list-checkpoints orchestrator))]
    
    (if latest-checkpoint
      ;; Restore orchestrator and all agents
      (do
        (log/info "Restoring orchestrator from checkpoint" (:id latest-checkpoint))
        @(restore-checkpoint orchestrator (:id latest-checkpoint))
        
        ;; Re-establish connections
        (doseq [agent-id (get-agent-ids orchestrator)]
          (reconnect-agent orchestrator agent-id))
        
        {:recovered :orchestrator :from-checkpoint (:id latest-checkpoint)})
      
      ;; No checkpoint - cold start
      (do
        (log/warn "No orchestrator checkpoint available, cold starting")
        (initialize-orchestrator orchestrator)
        {:recovered :orchestrator :cold-start true}))))
```

### 3. Network Partition Recovery
```clojure
(defn recover-from-partition [orchestrator partition-info]
  ;; Determine which side of partition has more recent state
  (let [local-checkpoint (last (list-checkpoints orchestrator))
        remote-checkpoint (:remote-checkpoint partition-info)]
    
    (if (and local-checkpoint remote-checkpoint)
      ;; Compare timestamps
      (if (.isAfter (:timestamp local-checkpoint) (:timestamp remote-checkpoint))
        ;; Local is more recent
        (do
          (log/info "Local state is more recent, keeping local")
          {:decision :keep-local :checkpoint (:id local-checkpoint)})
        
        ;; Remote is more recent
        (do
          (log/info "Remote state is more recent, restoring from remote")
          (restore-remote-state orchestrator remote-checkpoint)
          {:decision :use-remote :checkpoint (:id remote-checkpoint)}))
      
      ;; Missing checkpoints
      {:error :cannot-determine-recent-state
       :local-checkpoint local-checkpoint
       :remote-checkpoint remote-checkpoint})))
```

## Performance Considerations

### Checkpoint Frequency
- **High frequency**: More recovery points, higher overhead
- **Low frequency**: Less overhead, larger state loss on failure
- **Adaptive frequency**: Adjust based on system load and change rate

### Storage Optimization
1. **Compression**: Compress checkpoint data (gzip, Snappy)
2. **Deduplication**: Store only changed state
3. **Incremental checkpoints**: Save only differences
4. **Cleanup policy**: Remove old checkpoints automatically

### Recovery Time Objectives (RTO)
- **Critical systems**: Seconds to minutes
- **Business systems**: Minutes to hours
- **Batch systems**: Hours to days

## Integration with Existing System

### Enhanced Agent with Checkpointing
```clojure
(defrecord CheckpointableBasicAgent [agent-node checkpoint-store]
  IAgentNode
  ;; Implement all IAgentNode methods...
  
  ICheckpointable
  ;; Implement checkpointing methods...
  
  ;; Override process-task to create checkpoints
  (process-task [this task]
    (let [result (process-task agent-node task)]
      ;; Create checkpoint after significant state change
      (when (should-checkpoint? task result)
        (go
          (let [checkpoint-id (generate-checkpoint-id)]
            @(create-checkpoint this checkpoint-id)
            (log/debug "Checkpoint created:" checkpoint-id))))
      result)))
```

### Enhanced Orchestrator with Checkpointing
```clojure
(defrecord CheckpointableEnhancedOrchestrator [enhanced-orchestrator checkpoint-store]
  IHealthMonitor
  ILoadBalancer
  ;; Implement all enhanced orchestrator methods...
  
  ICheckpointable
  ;; Implement checkpointing methods...
  
  ;; Schedule periodic checkpoints
  (schedule-checkpoints [this interval-ms]
    (go
      (loop []
        (<! (timeout interval-ms))
        (let [checkpoint-id (str "periodic-" (System/currentTimeMillis))]
          @(create-checkpoint this checkpoint-id)
          (log/info "Periodic checkpoint created:" checkpoint-id))
        (recur)))))
```

## Testing Strategy

### Unit Tests
1. Checkpoint creation and restoration
2. State serialization/deserialization
3. Storage backend operations
4. Recovery scenarios

### Integration Tests
1. Coordinated checkpointing across multiple agents
2. Failure and recovery simulation
3. Performance under load
4. Storage cleanup and management

### Chaos Engineering Tests
1. Kill agents during checkpoint creation
2. Corrupt checkpoint storage
3. Network partitions during recovery
4. Concurrent checkpoint operations

## Next Steps
1. Design checkpoint storage abstraction
2. Implement basic checkpoint creation/restoration
3. Add periodic checkpoint scheduling
4. Create recovery procedures for common failure scenarios
5. Integrate with health monitoring for automatic recovery

## References
1. [Checkpoint/Restart in Distributed Systems](https://dl.acm.org/doi/10.1145/502034.502037)
2. [Fault Tolerance in Distributed Systems](https://www.cs.cornell.edu/fbs/publications/FTinDS.pdf)
3. [State Management in Microservices](https://microservices.io/patterns/data/saga.html)
4. [Event Sourcing and CQRS](https://martinfowler.com/eaaDev/EventSourcing.html)