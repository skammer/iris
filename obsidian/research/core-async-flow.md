# Core.async.flow for Agent Programming

## Overview
core.async.flow enables flow-based programming in Clojure, separating application logic from deployment concerns.

## Key Concepts

### Step Functions
- **Four arities**: describe, init, transition, transform
- **No direct channel access**: Promotes testability and reuse
- **Lifecycle management**: Built-in start/stop/pause/resume

### Architecture Benefits
- **Separation of concerns**: Logic vs deployment
- **Testability**: Step functions can be tested in isolation
- **Reusability**: Step functions can be composed
- **Error handling**: Built-in flow error recovery

## Application to AI Agents

### Agent Steps as Step Functions
Each cognitive function could be a step-fn:
1. **Reasoning step**: Process input, generate thoughts
2. **Memory step**: Retrieve/store information
3. **Action step**: Execute tools or generate responses
4. **Evaluation step**: Assess results, adjust strategy

### Message Passing via Channels
- Natural fit for agent communication
- Asynchronous processing
- Backpressure handling

### Lifecycle Management
- Easy agent state transitions
- Graceful shutdown
- Pause/resume capabilities

## Implementation Patterns

### Basic Step Function
```clojure
(defn reasoning-step
  "Step function for agent reasoning"
  ([] ; describe arity
   {:params {:model "LLM model to use"}
    :ins {:input "Input message"}
    :outs {:thoughts "Generated thoughts"
           :decision "Final decision"}})
  
  ([{:keys [model]}] ; init arity
   {:model model
    :context []})
  
  ([state transition] ; transition arity
   (case transition
     ::flow/start (assoc state :active? true)
     ::flow/stop (assoc state :active? false)
     state))
  
  ([state input-msg] ; transform arity
   (let [thoughts (generate-thoughts (:model state) input-msg)
         decision (make-decision thoughts)]
     [(assoc state :context (conj (:context state) thoughts))
      {:thoughts [thoughts]
       :decision [decision]}])))
```

### Flow Composition
```clojure
(def agent-flow
  (flow/flow
   {:steps {:reasoning reasoning-step
            :memory memory-step
            :action action-step}
    :connections [[:reasoning :thoughts -> :memory :input]
                  [:memory :context -> :reasoning :context]
                  [:reasoning :decision -> :action :input]]}))
```

## Benefits for Agent Architecture

### 1. Modularity
- Each agent capability as separate step function
- Easy to add/remove/replace capabilities
- Clear interfaces between components

### 2. Testability
- Step functions can be tested in isolation
- Mock channels for integration testing
- State transitions can be verified

### 3. Scalability
- Horizontal scaling of individual steps
- Load balancing across step instances
- Resource management per step

### 4. Observability
- Monitor each step independently
- Track message flow through system
- Debug specific components

## Integration with Other Patterns

### With LiteLLM-clj
```clojure
(defn llm-step
  "Step function for LLM calls"
  ([]
   {:params {:provider "LLM provider"
             :model "Model name"}
    :ins {:prompt "Input prompt"}
    :outs {:response "LLM response"}})
  
  ([{:keys [provider model]}]
   {:client (litellm/client {:provider provider :model model})})
  
  ([state {:keys [prompt]}]
   (let [response (litellm/completion (:client state) prompt)]
     [state {:response [response]}])))
```

### With Knowledge Graphs
```clojure
(defn knowledge-graph-step
  "Step function for knowledge graph queries"
  ([]
   {:params {:graph "Knowledge graph instance"}
    :ins {:query "Query to execute"}
    :outs {:results "Query results"}})
  
  ([{:keys [graph]}]
   {:graph graph})
  
  ([state {:keys [query]}]
   (let [results (query-graph (:graph state) query)]
     [state {:results [results]}])))
```

## Best Practices

### 1. Stateless Step Functions
- Avoid maintaining state within step functions
- Use flow state for persistence
- Keep transformations pure where possible

### 2. Error Handling
- Handle errors within step functions
- Use dead letter channels for failed messages
- Implement retry logic at flow level

### 3. Resource Management
- Initialize resources in init arity
- Clean up in transition arity for ::flow/stop
- Monitor resource usage per step

### 4. Monitoring
- Instrument step execution times
- Track message throughput
- Monitor error rates

## References
- [core.async.flow Guide](https://github.com/clojure/core.async/blob/master/doc/flow-guide.md)
- Flow-based programming patterns
- Agent architecture examples