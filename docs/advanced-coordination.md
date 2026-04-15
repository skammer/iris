# Advanced Coordination Features Documentation

## Overview

This module provides advanced coordination features for distributed AI agents, including health monitoring, failure detection, load balancing, and system observability. These features build upon the basic orchestrator-worker pattern to create a robust, production-ready distributed agent system.

## Architecture

### Components

1. **Health Monitor** (`BasicHealthMonitor`)
   - Heartbeat-based failure detection
   - Health status tracking
   - Status change listeners
   - Configurable failure thresholds

2. **Load Balancer** (`RoundRobinLoadBalancer`)
   - Round-robin agent selection
   - Capability-based matching
   - Load tracking and statistics
   - Dynamic agent registration

3. **Enhanced Orchestrator** (`EnhancedOrchestrator`)
   - Combines health monitoring and load balancing
   - Integrated failure recovery
   - System load statistics
   - Health status listeners

## Features

### Health Monitoring

#### Failure Detection
- **Heartbeat monitoring**: Agents send regular heartbeats
- **Configurable thresholds**: Set failure detection sensitivity
- **Automatic status updates**: Health status changes automatically
- **Listener support**: Subscribe to health status changes

#### Health Status States
- `:healthy` - Agent is responding normally
- `:failed` - Agent has missed too many heartbeats
- `:recovering` - Agent was failed but sent a heartbeat
- `:unknown` - Agent not being monitored

### Load Balancing

#### Selection Algorithms
- **Round-robin**: Equal distribution among agents
- **Capability matching**: Only select agents with required capabilities
- **Load-aware**: Track and consider agent load
- **Dynamic registration**: Agents can join/leave at runtime

#### Load Tracking
- **Individual agent load**: Track load per agent
- **System statistics**: Overall load metrics
- **Real-time updates**: Load changes immediately reflected
- **Performance metrics**: Average, max, min loads

### Integration with Basic Coordinator

The advanced features integrate seamlessly with the basic coordinator:

```clojure
;; Create enhanced system
(def system (create-agent-system))

;; Register agent with all components
(register-agent-with-system system "llm-agent-1" #{:llm :reasoning})

;; Agent automatically:
;; 1. Starts health monitoring
;; 2. Registers with load balancer  
;; 3. Registers with coordinator
;; 4. Begins sending heartbeats
```

## Usage

### Basic Setup

```clojure
(ns my-system.core
  (:require [agent.distributed.health :as health]
            [agent.distributed.coordinator :as coord]))

;; Create enhanced orchestrator
(def orchestrator (health/start-enhanced-orchestrator
                   :heartbeat-interval-ms 5000    ;; 5 second heartbeats
                   :failure-threshold 3           ;; Fail after 3 missed
                   :check-interval-ms 1000))      ;; Check every second

;; Create basic coordinator
(def coordinator (coord/start-orchestrator))
```

### Health Monitoring

```clojure
;; Start monitoring an agent
@(health/start-monitoring orchestrator "agent-1")

;; Register heartbeat (agent should do this regularly)
@(health/register-heartbeat orchestrator "agent-1")

;; Check health status
(let [status @(health/get-health-status orchestrator "agent-1")]
  (println "Agent health:" status))

;; Add health status change listener
(let [listener-id @(health/add-health-listener orchestrator
                     (fn [agent-id old-status new-status]
                       (println "Health change:" agent-id old-status "->" new-status)))]
  
  ;; Later, remove listener
  @(health/remove-health-listener orchestrator listener-id))

;; Get all failed agents
(let [failed @(health/get-failed-agents orchestrator)]
  (println "Failed agents:" failed))
```

### Load Balancing

```clojure
;; Register agents with load balancer
@(health/register-agent-with-balancer 
   (:load-balancer orchestrator) "llm-1" #{:llm :reasoning})
@(health/register-agent-with-balancer
   (:load-balancer orchestrator) "llm-2" #{:llm :creative})
@(health/register-agent-with-balancer
   (:load-balancer orchestrator) "web-1" #{:web-search})

;; Select agent for task (round-robin with capability matching)
(let [selected @(health/select-agent orchestrator #{:llm})]
  (println "Selected agent:" selected))

;; Update agent load (when task starts/completes)
@(health/update-agent-load orchestrator "llm-1" 1)   ;; Task started
@(health/update-agent-load orchestrator "llm-1" -1)  ;; Task completed

;; Get agent load
(let [load @(health/get-agent-load orchestrator "llm-1")]
  (println "Agent load:" load))

;; Get system load statistics
(let [stats @(health/get-system-load orchestrator)]
  (println "System load:" stats))
```

### Complete System Integration

```clojure
(defn create-complete-agent-system
  "Create and manage a complete distributed agent system."
  []
  (let [system (atom {:orchestrator nil
                      :coordinator nil
                      :agents {}})]
    
    ;; Initialize
    (swap! system assoc
           :orchestrator (health/start-enhanced-orchestrator)
           :coordinator (coord/start-orchestrator))
    
    ;; Agent management functions
    {:register-agent (fn [agent-id capabilities]
                       (let [{:keys [orchestrator coordinator]} @system]
                         ;; Start health monitoring
                         @(health/start-monitoring orchestrator agent-id)
                         
                         ;; Register with load balancer
                         @(health/register-agent-with-balancer
                            (:load-balancer orchestrator) agent-id capabilities)
                         
                         ;; Register with coordinator
                         @(coord/register-agent coordinator agent-id capabilities)
                         
                         ;; Store in local registry
                         (swap! system update :agents assoc agent-id
                                {:id agent-id
                                 :capabilities capabilities
                                 :registered-at (java.time.Instant/now)})
                         
                         {:agent-id agent-id :registered true}))
     
     :assign-task (fn [task]
                    (let [{:keys [orchestrator coordinator]} @system
                          required-caps (:required-capabilities task)]
                      
                      ;; Select agent using load balancer
                      (when-let [agent-id @(health/select-agent orchestrator required-caps)]
                        
                        ;; Update load
                        @(health/update-agent-load orchestrator agent-id 1)
                        
                        ;; Assign task
                        @(coord/assign-task coordinator task agent-id)
                        
                        ;; Return assignment info
                        {:task-id (:task-id task)
                         :assigned-to agent-id
                         :assigned-at (java.time.Instant/now)})))
     
     :get-system-stats (fn []
                         (let [{:keys [orchestrator]} @system]
                           {:health (count @(health/get-failed-agents orchestrator))
                            :load @(health/get-system-load orchestrator)
                            :agents (count (:agents @system))}))
     
     :shutdown (fn []
                 ;; Clean shutdown procedures
                 (println "Shutting down agent system..."))}))
```

## Configuration

### Health Monitor Configuration

```clojure
;; Custom configuration example
(def custom-monitor
  (health/start-health-monitor
   :heartbeat-interval-ms 3000    ;; Expect heartbeats every 3 seconds
   :failure-threshold 5           ;; Fail after 5 missed heartbeats  
   :check-interval-ms 500))       ;; Check for failures every 500ms
```

**Recommended Settings:**

| Environment | Heartbeat Interval | Failure Threshold | Check Interval |
|-------------|-------------------|-------------------|----------------|
| Development | 5000 ms | 3 | 1000 ms |
| Testing | 1000 ms | 2 | 200 ms |
| Production | 10000 ms | 5 | 2000 ms |

### Load Balancer Configuration

The round-robin load balancer is stateless and requires no configuration. Future implementations may include:

- Weighted round-robin
- Least connections
- Response time-based
- Resource-based balancing

## Failure Recovery Strategies

### Automatic Recovery
1. **Heartbeat-based**: Agent sends heartbeat after failure
2. **Status transition**: `:failed` → `:healthy` on heartbeat
3. **Listener notification**: All listeners notified of recovery

### Manual Recovery
```clojure
;; Check if agent is failed
(let [status @(health/get-health-status orchestrator "agent-1")]
  (when (= :failed (:status status))
    ;; Manual recovery actions
    (println "Agent failed, attempting recovery...")
    
    ;; Option 1: Restart agent process
    ;; Option 2: Re-register agent
    ;; Option 3: Notify administrator
    ))
```

### Preventive Measures
1. **Regular heartbeats**: Agents should send heartbeats consistently
2. **Grace periods**: Allow for network delays
3. **Redundancy**: Multiple agents with same capabilities
4. **Circuit breakers**: Prevent cascade failures

## Performance Considerations

### Scalability
- **Health monitoring**: O(n) where n = number of agents
- **Load balancing**: O(m) where m = agents with matching capabilities
- **Memory usage**: ~2KB per agent for monitoring state
- **Network overhead**: Minimal (heartbeats only)

### Optimization Tips
1. **Batch heartbeats**: Multiple agents in single message
2. **Compressed state**: Minimal agent state storage
3. **Lazy evaluation**: Only compute statistics when needed
4. **Connection pooling**: Reuse connections for heartbeats

## Monitoring and Observability

### Key Metrics to Track
1. **Agent health rate**: Percentage of healthy agents
2. **Heartbeat success rate**: Percentage of expected heartbeats received
3. **Load distribution**: Evenness of load across agents
4. **Failure recovery time**: Time from failure to detection/recovery
5. **System throughput**: Tasks processed per time unit

### Integration with Monitoring Systems
```clojure
;; Example: Export metrics to Prometheus
(defn export-metrics [orchestrator]
  (let [failed-count (count @(health/get-failed-agents orchestrator))
        load-stats @(health/get-system-load orchestrator)]
    
    {:metrics {:agents_failed failed-count
               :system_load_total (:total-load load-stats)
               :system_load_average (:average-load load-stats)
               :system_agents_count (:agent-count load-stats)}}))
```

## Testing

### Unit Tests
```bash
# Run health monitoring tests
clj -M:test -m agent.distributed.health-test

# Run load balancing tests  
clj -M:test -m agent.distributed.health-test/load-balancer-tests
```

### Integration Tests
```clojure
;; Example integration test
(deftest health-load-balancer-integration-test
  (testing "Complete system integration"
    (let [system (create-complete-agent-system)]
      
      ;; Register agents
      (@system :register-agent) "test-1" #{:llm}
      (@system :register-agent) "test-2" #{:llm}
      
      ;; Assign tasks
      (let [task {:task-id "test-task" :required-capabilities #{:llm}}
            assignment (@system :assign-task) task]
        (is assignment)
        (is (:assigned-to assignment)))
      
      ;; Check statistics
      (let [stats (@system :get-system-stats)]
        (is (:health stats))
        (is (:load stats))))))
```

### Performance Tests
```clojure
;; Simulate high load
(deftest performance-under-load-test
  (testing "System performance under high load"
    (let [orchestrator (health/start-enhanced-orchestrator)
          agent-count 1000]
      
      ;; Register many agents
      (dotimes [i agent-count]
        @(health/register-agent-with-balancer
           (:load-balancer orchestrator)
           (str "agent-" i)
           #{:test}))
      
      ;; Perform many selections
      (time
       (dotimes [i 10000]
         @(health/select-agent orchestrator #{:test})))
      
      ;; Should complete in reasonable time
      )))
```

## Troubleshooting

### Common Issues

#### 1. Agents Marked as Failed Too Quickly
**Solution**: Increase `:failure-threshold` or `:heartbeat-interval-ms`
```clojure
;; More lenient configuration
(health/start-health-monitor
 :heartbeat-interval-ms 10000   ;; 10 seconds
 :failure-threshold 5)          ;; 5 missed heartbeats
```

#### 2. Load Not Balanced Evenly
**Solution**: Check capability matching and agent registration
```clojure
;; Verify agent capabilities
(println "Agent capabilities:"
         @(:capabilities-map (:load-balancer balancer)))

;; Check queue rotation
(println "Agent queue:" @(:agent-queue (:load-balancer balancer)))
```

#### 3. High Memory Usage
**Solution**: Implement agent cleanup and state compression
```clojure
;; Regularly clean up old agent states
(defn cleanup-old-agents [monitor max-age-hours]
  (let [cutoff (.minus (java.time.Instant/now)
                       java.time.Duration/ofHours max-age-hours)]
    ;; Remove agents older than cutoff
    ))
```

### Debugging Tips
1. **Enable debug logging**: `(log/debug ...)` in critical paths
2. **Monitor heartbeat timing**: Log heartbeat send/receive times
3. **Track load changes**: Log when agent load increases/decreases
4. **Use health listeners**: Add temporary listeners for debugging

## Future Enhancements

### Planned Features
1. **Advanced load balancing algorithms**
   - Least connections
   - Weighted round-robin
   - Response time-based
   - Predictive load balancing

2. **Enhanced failure recovery**
   - Automatic agent restart
   - State checkpointing
   - Failover to backup agents
   - Graceful degradation

3. **Advanced monitoring**
   - Historical health data
   - Predictive failure detection
   - Anomaly detection
   - Automated alerting

4. **Security features**
   - Authenticated heartbeats
   - Encrypted health status
   - Role-based access control
   - Audit logging

### Integration Points
1. **Container orchestration**: Kubernetes, Docker Swarm
2. **Service mesh**: Istio, Linkerd
3. **Monitoring systems**: Prometheus, Grafana, Datadog
4. **Alerting systems**: PagerDuty, OpsGenie, Slack

## Related Documentation

1. [Basic Coordinator Documentation](./distributed-coordination.md)
2. [Health Monitoring Tests](../test/agent/distributed/health_test.clj)
3. [Advanced Coordination Examples](../examples/advanced_coordination.clj)
4. [Performance Benchmarks](../benchmarks/distributed-coordination.md)

## API Reference

See the source code for complete API documentation:
- `src/agent/distributed/health.clj` - Health monitoring and load balancing
- `test/agent/distributed/health_test.clj` - Test coverage and examples
- `examples/advanced_coordination.clj` - Usage examples and demonstrations