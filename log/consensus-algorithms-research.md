# Consensus Algorithms Research (Raft/Paxos)
Date: 2026-04-15

## Task: Implement consensus algorithms (Phase 4, Task 22.4)

## Overview
Consensus algorithms ensure that multiple distributed processes agree on a single value or sequence of values. Essential for:
- Leader election in distributed systems
- Replicated state machines
- Fault-tolerant coordination
- Consistent distributed decision making

## Raft Algorithm

### Key Concepts
1. **Leader election**: Nodes elect a leader to coordinate operations
2. **Log replication**: Leader replicates log entries to followers
3. **Safety**: Ensures consistency despite failures
4. **Liveness**: System makes progress despite failures

### Node States
- **Leader**: Handles client requests, replicates log
- **Candidate**: Attempting to become leader
- **Follower**: Passive, responds to leader/candidate requests

### Key Components
1. **Term**: Monotonically increasing counter (like election term)
2. **Log**: Sequence of commands with term numbers
3. **Commit index**: Highest log entry known to be committed
4. **Last applied**: Highest log entry applied to state machine

### Election Process
1. Follower timeout → becomes Candidate
2. Candidate increments term, votes for itself
3. Requests votes from other nodes
4. Receives majority votes → becomes Leader
5. Sends heartbeat to maintain leadership

### Log Replication
1. Client sends command to Leader
2. Leader appends to local log
3. Leader sends AppendEntries to Followers
4. Followers append to log, respond with success
5. Leader commits entry after majority replication
6. Leader notifies Followers of commit

## Paxos Algorithm

### Key Concepts
1. **Proposers**: Propose values for consensus
2. **Acceptors**: Accept proposed values
3. **Learners**: Learn chosen values
4. **Quorum**: Majority of acceptors

### Phases
#### Phase 1: Prepare
1. Proposer chooses proposal number n
2. Sends Prepare(n) to Acceptors
3. Acceptor responds with:
   - Promise not to accept proposals < n
   - Highest-numbered proposal accepted (if any)

#### Phase 2: Accept
1. Proposer receives promises from majority
2. If no value previously accepted, propose any value
3. If value previously accepted, propose that value
4. Send Accept(n, value) to Acceptors
5. Acceptor accepts if n >= promised number

#### Phase 3: Learn
1. When majority accepts (n, value), value is chosen
2. Learners learn chosen value

### Multi-Paxos
Optimization for multiple consensus instances:
- Elect "distinguished proposer" (like Leader in Raft)
- Skip Prepare phase after initial election
- Faster consensus for sequence of values

## Comparison: Raft vs Paxos

### Raft Advantages
- **Easier to understand**: Designed for understandability
- **Strong leadership**: Clear leader/follower roles
- **Log-centric**: Natural for replicated state machines
- **Implementation simplicity**: Fewer edge cases

### Paxos Advantages
- **Theoretical elegance**: Mathematically proven
- **Flexibility**: Multiple proposers can coexist
- **Optimizations**: Multi-Paxos for efficiency
- **Industry adoption**: Foundation for many systems

### Use Cases
- **Raft**: etcd, Consul, TiKV, CockroachDB
- **Paxos**: Google Chubby, Amazon DynamoDB, Microsoft Azure

## Implementation Considerations for Agent System

### Requirements
1. **Leader election**: For coordinator/orchestrator role
2. **Configuration changes**: Adding/removing agents
3. **Task assignment consensus**: Agreeing on task allocation
4. **State replication**: Replicating agent state across nodes

### Design Decisions

#### Option 1: Pure Raft Implementation
- Implement full Raft protocol
- Use for leader election and log replication
- Integrate with existing coordinator

#### Option 2: Paxos-Based Consensus
- Implement Multi-Paxos
- Use for specific consensus decisions
- Lighter weight than full Raft

#### Option 3: Hybrid Approach
- Raft for leader election and membership
- Paxos for specific consensus decisions
- Best of both worlds

### Integration Points
1. **Coordinator consensus**: Elect leader coordinator
2. **Market consensus**: Agree on auction results
3. **Health consensus**: Agree on node health status
4. **Configuration consensus**: Agree on system configuration

## Implementation Plan

### Phase 1: Raft Core
1. Implement Raft node state machine
2. Implement election logic
3. Implement log replication
4. Add persistence (log, state)

### Phase 2: Paxos Core
1. Implement basic Paxos
2. Implement Multi-Paxos optimization
3. Add learner role for value propagation

### Phase 3: Integration
1. Integrate Raft with coordinator
2. Use Paxos for specific consensus decisions
3. Add configuration management

### Phase 4: Testing
1. Unit tests for algorithms
2. Network partition simulations
3. Failure recovery tests
4. Performance benchmarks

## Key Challenges

### 1. Network Partitions
- Split-brain scenarios
- Leader isolation
- Partition recovery

### 2. Performance
- Latency in consensus decisions
- Throughput for high-frequency decisions
- Resource usage (memory, network)

### 3. Correctness
- Safety violations
- Liveness guarantees
- Edge case handling

### 4. Integration Complexity
- Existing system compatibility
- State machine integration
- Failure handling coordination

## References

### Raft Resources
1. [Raft Paper](https://raft.github.io/raft.pdf)
2. [Raft Visualization](https://raft.github.io/)
3. [Raft Implementation Guide](https://thesquareplanet.com/blog/students-guide-to-raft/)

### Paxos Resources
1. [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf)
2. [Paxos Made Live](https://static.googleusercontent.com/media/research.google.com/en//archive/paxos_made_live.pdf)
3. [Paxos Explained](https://www.cs.utexas.edu/users/lorenzo/corsi/cs380d/papers/paper2-1.pdf)

### Implementation Examples
1. [etcd Raft](https://github.com/etcd-io/etcd/tree/main/raft)
2. [LogCabin Raft](https://github.com/logcabin/logcabin)
3. [JPaxos](https://github.com/jpaxos/jpaxos)

## Next Steps
1. Design consensus protocol interfaces
2. Implement Raft node state machine
3. Add election and replication logic
4. Create integration with coordinator