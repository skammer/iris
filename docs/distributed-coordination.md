# Distributed Coordination Documentation

## Overview

The distributed coordination module provides a basic orchestrator-worker pattern for coordinating multiple AI agents. This implementation follows the research and design documented in the project's distributed coordination research and design documents.

## Architecture

### Components

1. **Orchestrator** (`BasicOrchestrator`)
   - Central coordination point
   - Agent registry management
   - Task assignment
   - Message broadcasting
   - Consensus mechanism

2. **Agent Node** (`BasicAgentNode`)
   - Individual worker agent
   - Capability declaration
   - Task processing
   - Message reception

### Communication Patterns

- **Registration/Deregistration**: Agents register capabilities with orchestrator
- **Task Assignment**: Orchestrator assigns tasks to suitable agents
- **Broadcasting**: Send messages to groups of agents
- **Consensus**: Collective decision-making among agents

## Usage

### Basic Setup

```clojure
(ns my-agent.system
  (:require [agent.distributed.coordinator :as coord]))

;; Start orchestrator
(def orchestrator (coord/start-orchestrator))

;; Start agent nodes
(def llm-agent (coord/start-agent-node "llm-1" #{:llm :reasoning}))
(def web-agent (coord/start-agent-node "web-1" #{:web-search :data-processing}))

;; Connect agents to orchestrator
@(coord/connect llm-agent "coordinator://localhost:8080")
@(coord/connect web-agent "coordinator://localhost:8080")

;; Register agents
@(coord/register-agent orchestrator "llm-1" #{:llm :reasoning})
@(coord/register-agent orchestrator "web-1" #{:web-search :data-processing})
```

### Task Assignment

```clojure
;; Create a task
(def reasoning-task
  {:task-id "reason-123"
   :type :complex-reasoning
   :payload {:question "Explain quantum computing basics"
             :context {:domain :physics}}
   :required-capabilities #{:llm :reasoning}})

;; Find suitable agents
(let [suitable-agents @(coord/find-agent orchestrator #{:llm :reasoning})]
  (when (seq suitable-agents)
    (let [agent-id (first suitable-agents)]
      @(coord/assign-task orchestrator reasoning-task agent-id))))
```

### Broadcasting Messages

```clojure
;; Broadcast to all LLM agents
(let [message {:type :system-alert
               :content "System maintenance scheduled for 2AM"}
      filter-fn (fn [agent-info]
                  (contains? (:capabilities agent-info) :llm))]
  @(coord/broadcast orchestrator message filter-fn))
```

### Consensus Mechanism

```clojure
;; Reach consensus on a proposal
(def deployment-proposal
  {:proposal-id "deploy-v2"
   :content {:action :deploy
             :version "2.0.0"
             :environment :staging}})

(let [voters #{"llm-1" "llm-2" "kg-1"}
      result @(coord/consensus orchestrator deployment-proposal voters)]
  
  (if (:approved? result)
    (println "Proposal approved with" (:approval-ratio result) "approval")
    (println "Proposal rejected")))
```

## API Reference

### Orchestrator Protocol (`ICoordinator`)

#### `register-agent`
```clojure
(register-agent orchestrator agent-id capabilities)
```
Registers an agent with its capabilities.

**Parameters:**
- `agent-id`: String, unique agent identifier
- `capabilities`: Set of keywords representing agent capabilities

**Returns:** Deferred containing agent registration info

#### `deregister-agent`
```clojure
(deregister-agent orchestrator agent-id)
```
Deregisters an agent from the orchestrator.

**Parameters:**
- `agent-id`: String, agent identifier to deregister

**Returns:** Deferred containing deregistration confirmation

#### `assign-task`
```clojure
(assign-task orchestrator task agent-id)
```
Assigns a task to a specific agent.

**Parameters:**
- `task`: Map with `:task-id`, `:type`, `:payload`, `:required-capabilities`
- `agent-id`: String, agent identifier to assign task to

**Returns:** Deferred containing assignment confirmation

#### `find-agent`
```clojure
(find-agent orchestrator capabilities)
```
Finds agents with specific capabilities.

**Parameters:**
- `capabilities`: Set of required capabilities

**Returns:** Deferred containing list of matching agent IDs

#### `broadcast`
```clojure
(broadcast orchestrator message filter-fn)
```
Broadcasts a message to agents matching filter.

**Parameters:**
- `message`: Any Clojure data to broadcast
- `filter-fn`: Function taking agent metadata, returning truthy for recipients

**Returns:** Deferred containing broadcast result

#### `consensus`
```clojure
(consensus orchestrator proposal voters)
```
Reaches consensus on a proposal among voters.

**Parameters:**
- `proposal`: Map with `:proposal-id` and `:content`
- `voters`: Set of agent IDs participating in consensus

**Returns:** Deferred containing consensus result

### Agent Node Protocol (`IAgentNode`)

#### `connect`
```clojure
(connect agent-node coordinator-url)
```
Connects agent to a coordinator.

**Parameters:**
- `coordinator-url`: String, URL of coordinator

**Returns:** Deferred containing connection result

#### `disconnect`
```clojure
(disconnect agent-node)
```
Disconnects agent from coordinator.

**Returns:** Deferred containing disconnection result

#### `process-task`
```clojure
(process-task agent-node task)
```
Processes an assigned task.

**Parameters:**
- `task`: Task to process

**Returns:** Deferred containing task result

#### `receive-message`
```clojure
(receive-message agent-node message)
```
Receives a broadcast message.

**Parameters:**
- `message`: Message received

**Returns:** Deferred containing acknowledgement

## Examples

See the complete example in `/examples/distributed_orchestrator.clj` for a full demonstration including:

1. Multi-agent setup with different capabilities
2. Task assignment based on capabilities
3. Targeted broadcasting
4. Consensus decision-making
5. Load balancing simulation
6. Clean shutdown procedures

## Testing

Run the test suite:

```bash
# From project root
clj -M:test -m agent.distributed.coordinator-test
```

Or from REPL:

```clojure
(require '[clojure.test :as test])
(test/run-tests 'agent.distributed.coordinator-test)
```

## Design Decisions

### 1. Protocol-Based Design
- Uses Clojure protocols for clear interfaces
- Allows multiple implementations
- Easy to mock for testing

### 2. Async-First
- Built on `core.async` and `manifold`
- Non-blocking operations
- Suitable for high-concurrency scenarios

### 3. Capability-Based Matching
- Agents declare capabilities
- Tasks specify required capabilities
- Dynamic matching at runtime

### 4. Simple Consensus
- Basic majority voting
- Configurable thresholds
- Extensible for complex algorithms

## Limitations and Future Improvements

### Current Limitations:
1. **Basic consensus**: Simple majority voting only
2. **No persistence**: Agent registry is in-memory only
3. **Single orchestrator**: No clustering/failover
4. **Basic security**: No authentication/encryption

### Planned Improvements:
1. **Advanced consensus**: Raft/Paxos algorithms
2. **Persistence**: Database-backed registry
3. **Orchestrator clustering**: High availability
4. **Security**: TLS, authentication, authorization
5. **Monitoring**: Metrics, health checks, logging

## Integration with Existing Agent System

The distributed coordination module can be integrated with the existing agent system:

```clojure
(ns my-app.system
  (:require [agent.llm :as llm]
            [agent.knowledge-graph :as kg]
            [agent.distributed.coordinator :as coord]))

;; Wrap existing agent with coordination capabilities
(defrecord CoordinatedLlmAgent [llm-provider coordinator agent-id]
  coord/IAgentNode
  (process-task [this task]
    ;; Use existing LLM agent to process task
    (llm/generate llm-provider (:payload task)))
  
  ;; ... implement other IAgentNode methods
  )
```

## Performance Considerations

### Scalability
- Designed for 10-100 agents per orchestrator
- Linear scaling with number of agents
- Async operations prevent blocking

### Memory Usage
- Agent registry: ~1KB per agent
- Task queue: Configurable buffer size
- Channels: Fixed buffer sizes prevent unbounded growth

### Latency
- Registration: < 10ms
- Task assignment: < 5ms
- Broadcasting: O(n) where n = number of recipients
- Consensus: O(v) where v = number of voters

## Troubleshooting

### Common Issues:

1. **Agent not found for capabilities**
   - Verify agent registration succeeded
   - Check capability matching (exact set match)
   - Ensure agent is connected to orchestrator

2. **Task assignment fails**
   - Check agent status (should be `:available`)
   - Verify task has `:required-capabilities`
   - Ensure orchestrator is running

3. **Consensus times out**
   - Increase timeout in consensus implementation
   - Check voter agents are responsive
   - Verify network connectivity

### Debugging:
- Enable debug logging: `(log/debug ...)`
- Check agent status: `@agent-node` (prints status)
- Monitor orchestrator state: `@orchestrator-state`

## Related Documentation

1. [Distributed Coordination Research](../log/distributed-coordination-research.md)
2. [Distributed Coordination Design](../log/distributed-coordination-design.md)
3. [API Reference](../API.md)
4. [Usage Guide](../USAGE.md)