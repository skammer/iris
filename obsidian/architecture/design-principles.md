# Agent Core Architecture Design

## Overview
Core architecture design for the Clojure AI agent, based on research findings and architectural decisions documented in Phase 1 and 2.

## Architecture Principles

### 1. Flow-Based Processing
- **core.async.flow foundation**: Step functions for modular processing
- **Data pipeline architecture**: Input → Process → Output flow
- **Lifecycle management**: Built-in start/stop/pause/resume

### 2. Security-First Design
- **Sandboxed execution**: All tools run in isolated containers
- **Local-only data**: User data never leaves their control
- **Defense in depth**: Multiple security layers

### 3. Hybrid Memory System
- **Short-term**: LLM context window
- **Medium-term**: Vector embeddings for semantic search
- **Long-term**: Knowledge graph for structured knowledge
- **Working memory**: Current task context

### 4. Modular Component Architecture
- **Clear separation of concerns**: Independent, testable components
- **Protocol-based interfaces**: Well-defined component contracts
- **Pluggable architecture**: Easy to extend and modify

## Core Components

### 1. Agent Engine
```clojure
(defrecord AgentEngine
  [config      ; Configuration settings
   memory      ; Memory system
   tools       ; Tool registry
   providers   ; LLM providers
   state       ; Current agent state
   flow        ; Processing flow
   listeners]) ; Event listeners
```

### 2. Processing Flow
```clojure
(defn create-agent-flow
  "Create the core agent processing flow"
  []
  (flow/flow
   {:steps {:input     input-step      ; Receive and parse input
            :reason    reasoning-step  ; Generate thoughts and reasoning
            :memory    memory-step     ; Store/retrieve from memory
            :plan      planning-step   ; Create execution plan
            :tools     tools-step      ; Execute tools if needed
            :generate  generation-step ; Generate final response
            :evaluate  evaluation-step ; Evaluate and learn
            :output    output-step}    ; Format and send output
   
    :connections [[:input :parsed -> :reason :input]
                  [:reason :thoughts -> :memory :store]
                  [:memory :context -> :reason :context]
                  [:reason :plan -> :plan :input]
                  [:plan :actions -> :tools :commands]
                  [:tools :results -> :generate :context]
                  [:generate :response -> :evaluate :input]
                  [:evaluate :feedback -> :memory :learn]
                  [:evaluate :output -> :output :message]]}))
```

### 3. Step Function Definitions

#### Input Step
```clojure
(defn input-step
  "Parse and validate input"
  ([]
   {:params {:parsers "Input parsers"}
    :ins {:raw "Raw input message"}
    :outs {:parsed "Parsed input structure"
           :valid? "Input validation result"}})
  
  ([{:keys [parsers]}]
   {:parsers parsers})
  
  ([state {:keys [raw]}]
   (let [parsed (parse-input (:parsers state) raw)
         valid? (validate-input parsed)]
     [state {:parsed [parsed]
             :valid? [valid?]}])))
```

#### Reasoning Step
```clojure
(defn reasoning-step
  "Generate thoughts and reasoning"
  ([]
   {:params {:llm "LLM provider"
             :reasoning-model "Model for reasoning"}
    :ins {:input "Parsed input"
          :context "Retrieved context"}
    :outs {:thoughts "Generated thoughts"
           :plan "Execution plan"
           :confidence "Reasoning confidence"}})
  
  ([{:keys [llm reasoning-model]}]
   {:llm llm
    :model reasoning-model
    :max-thoughts 5})
  
  ([state {:keys [input context]}]
   (let [prompt (format-reasoning-prompt input context)
         thoughts (generate-thoughts (:llm state) prompt (:model state))
         plan (create-plan thoughts)
         confidence (calculate-confidence thoughts)]
     [state {:thoughts [thoughts]
             :plan [plan]
             :confidence [confidence]}])))
```

#### Memory Step
```clojure
(defn memory-step
  "Store and retrieve from memory"
  ([]
   {:params {:memory-system "Hybrid memory system"}
    :ins {:store "Information to store"
          :retrieve "Query for retrieval"}
    :outs {:stored "Storage confirmation"
           :retrieved "Retrieved information"}})
  
  ([{:keys [memory-system]}]
   {:memory memory-system})
  
  ([state {:keys [store retrieve]}]
   (let [stored (when store (store-memory (:memory state) store))
         retrieved (when retrieve (retrieve-memory (:memory state) retrieve))]
     [state {:stored [stored]
             :retrieved [retrieved]}])))
```

## Component Interfaces

### 1. Memory System Protocol
```clojure
(defprotocol IMemorySystem
  (store-short-term [this key value ttl])
  (retrieve-short-term [this key])
  (search-vector [this query limit])
  (query-knowledge-graph [this sparql])
  (add-fact [this fact])
  (get-working-memory [this])
  (set-working-memory [this value]))
```

### 2. Tool System Protocol
```clojure
(defprotocol IToolSystem
  (register-tool [this name description execute-fn])
  (execute-tool [this name args])
  (list-tools [this])
  (describe-tool [this name])
  (create-sandbox [this config])
  (monitor-tool [this name metrics]))
```

### 3. LLM Provider Protocol
```clojure
(defprotocol ILLMProvider
  (complete [this prompt opts])
  (stream [this prompt opts])
  (embed [this text opts])
  (function-call [this prompt functions opts])
  (get-models [this])
  (get-capabilities [this]))
```

### 4. Agent State Protocol
```clojure
(defprotocol IAgentState
  (get-state [this])
  (update-state [this key value])
  (transition [this from to])
  (add-listener [this event-type listener])
  (remove-listener [this event-type listener])
  (notify-listeners [this event data]))
```

## Configuration Management

### 1. Configuration Structure
```clojure
(def default-config
  {:agent {:name "Clojure AI Agent"
           :version "0.1.0"
           :mode :development}
   
   :memory {:short-term-ttl 300000      ; 5 minutes
            :vector-store {:type :hnsw :dimensions 384}
            :knowledge-graph {:type :in-memory}}
   
   :llm {:default-provider :openai
         :providers {:openai {:api-key nil :model "gpt-4"}
                     :local {:type :ollama :model "llama2"}}}
   
   :tools {:sandbox {:type :docker :resources {:memory "512MB"}}
           :timeout-ms 30000}
   
   :security {:require-auth true
              :max-request-size 1048576  ; 1MB
              :rate-limit {:requests 100 :per-seconds 60}}
   
   :logging {:level :info
             :file "agent.log"
             :format :json}})
```

### 2. Configuration Loading
```clojure
(defn load-config
  "Load configuration with environment overrides"
  [config-path]
  (let [base (read-config config-path)
        env (System/getenv "AGENT_ENV")
        env-config (when env (read-config (str config-path "." env)))
        secrets (load-secrets)]
    (deep-merge base env-config secrets)))

(defn with-config
  "Execute with configuration context"
  [config f]
  (binding [*config* config]
    (f)))
```

## Event System

### 1. Event Types
```clojure
(def event-types
  {:agent/started      "Agent started"
   :agent/stopped      "Agent stopped"
   :input/received     "Input received"
   :reasoning/started  "Reasoning started"
   :reasoning/completed "Reasoning completed"
   :tool/executed      "Tool executed"
   :tool/failed        "Tool execution failed"
   :memory/stored      "Memory stored"
   :memory/retrieved   "Memory retrieved"
   :response/generated "Response generated"
   :error/occurred     "Error occurred"})
```

### 2. Event Bus
```clojure
(defrecord EventBus
  [listeners]  ; Map of event-type -> [listeners]
  
  IAgentState
  (add-listener [this event-type listener]
    (update this :listeners update event-type conj listener))
  
  (remove-listener [this event-type listener]
    (update this :listeners update event-type
            (fn [lst] (remove #(= % listener) lst))))
  
  (notify-listeners [this event data]
    (doseq [listener (get-in this [:listeners (:type event)])]
      (try
        (listener event data)
        (catch Exception e
          (log/error "Listener error:" e))))))
```

## Error Handling

### 1. Error Types
```clojure
(def error-types
  {:input/invalid      "Invalid input"
   :llm/unavailable    "LLM provider unavailable"
   :tool/execution     "Tool execution failed"
   :memory/full        "Memory capacity exceeded"
   :security/violation "Security violation"
   :configuration      "Configuration error"
   :unknown            "Unknown error"})
```

### 2. Error Recovery
```clojure
(defn with-error-recovery
  "Execute with error recovery"
  [f & {:keys [retries on-error]
        :or {retries 3 on-error identity}}]
  (loop [attempt 1]
    (let [result (try {:success? true :value (f)}
                    (catch Exception e
                      {:success? false :error e}))]
      (cond
        (:success? result) (:value result)
        (>= attempt retries) (on-error (:error result))
        :else (do
                (log/warn "Retrying after error:" (:error result))
                (Thread/sleep (* attempt 1000)) ; Exponential backoff
                (recur (inc attempt)))))))
```

## Initialization Sequence

### 1. Startup Process
```clojure
(defn start-agent
  "Initialize and start the agent"
  [config-path]
  (let [config (load-config config-path)
        event-bus (->EventBus {})
        memory (create-memory-system (:memory config))
        tools (create-tool-system (:tools config))
        providers (create-llm-providers (:llm config))
        flow (create-agent-flow)
        
        agent (map->AgentEngine
               {:config config
                :memory memory
                :tools tools
                :providers providers
                :state {:status :starting
                        :started-at (java.util.Date.)
                        :request-count 0}
                :flow flow
                :listeners event-bus})]
    
    ; Initialize components
    (initialize-memory memory)
    (initialize-tools tools)
    (initialize-providers providers)
    
    ; Start flow
    (flow/start flow)
    
    ; Notify startup
    (notify-listeners event-bus
                     {:type :agent/started :timestamp (java.util.Date.)}
                     {:config config})
    
    (assoc-in agent [:state :status] :running)))
```

### 2. Shutdown Process
```clojure
(defn stop-agent
  "Gracefully stop the agent"
  [agent]
  (let [event-bus (:listeners agent)]
    
    ; Notify stopping
    (notify-listeners event-bus
                     {:type :agent/stopping :timestamp (java.util.Date.)}
                     {})
    
    ; Stop flow
    (flow/stop (:flow agent))
    
    ; Cleanup components
    (cleanup-memory (:memory agent))
    (cleanup-tools (:tools agent))
    (cleanup-providers (:providers agent))
    
    ; Update state
    (-> agent
        (assoc-in [:state :status] :stopped)
        (assoc-in [:state :stopped-at] (java.util.Date.)))))
```

## Multi-Agent Coordination (Future Extension)

### 1. Orchestrator Pattern
```clojure
(defrecord Orchestrator
  [agents      ; Map of agent-id -> agent
   task-queue  ; Queue of tasks to distribute
   results     ; Map of task-id -> result
   coordinator ; Coordination logic
   
   IOrchestrator
   (assign-task [this task]
     (let [agent-id (select-agent this task)
           agent (get agents agent-id)]
       (execute-task agent task)))
   
   (collect-results [this]
     (reduce merge {} (map :result (vals agents))))
   
   (monitor-agents [this]
     (map :status (vals agents)))])
```

### 2. Worker Agent
```clojure
(defrecord WorkerAgent
  [parent      ; Parent orchestrator
   capabilities ; What this worker can do
   current-task ; Currently executing task
   status       ; :idle, :working, :error
   
   IWorker
   (execute [this task]
     (assoc this :current-task task
                 :status :working))
   
   (report-result [this result]
     (send-result parent (:id current-task) result)
     (assoc this :current-task nil
                 :status :idle))
   
   (report-error [this error]
     (send-error parent (:id current-task) error)
     (assoc this :current-task nil
                 :status :error))])
```

## Implementation Roadmap

### Phase 3.1: Core Architecture (Current)
1. **Implement AgentEngine record** with basic structure
2. **Create flow step functions** for each processing stage
3. **Implement protocol interfaces** for memory, tools, LLM providers
4. **Build configuration system** with environment support
5. **Create event system** for monitoring and logging

### Phase 3.2: Component Implementation
1. **Implement memory system** with hybrid storage
2. **Build tool execution framework** with sandboxing
3. **Create LLM provider abstraction** with multiple backends
4. **Implement security layer** with input validation and access control

### Phase 3.3: Integration and Testing
1. **Integrate all components** into working agent
2. **Create comprehensive test suite** with mocks and fixtures
3. **Implement error handling and recovery** mechanisms
4. **Add monitoring and observability** features

### Phase 3.4: Advanced Features
1. **Implement multi-agent coordination** patterns
2. **Add knowledge graph integration** for advanced reasoning
3. **Create plugin system** for extensibility
4. **Build deployment and distribution** tools

## Next Steps

### Immediate Tasks
1. Create implementation directory structure
2. Implement AgentEngine record and basic flow
3. Create stub implementations for protocols
4. Build configuration loading system
5. Create basic test harness

### Dependencies
- **core.async** for flow-based programming
- **clj-http** or **http-kit** for HTTP requests
- **cheshire** for JSON processing
- **tools.deps** for dependency management
- **clojure.test** for testing

### Success Criteria
- Agent can be started with configuration
- Basic input → processing → output flow works
- Components can be tested in isolation
- Error handling prevents catastrophic failures
- Architecture supports future extensions