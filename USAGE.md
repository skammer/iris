# Clojure AI Agent - Usage Guide

> Legacy archive: examples below target pre-rewrite namespaces now quarantined under `legacy_src/`. Current canonical entrypoint is `agent.core`.

## Quick Examples

### Basic Agent
```clojure
(ns my-app.core
  (:require [agent.core :as agent]
            [agent.llm.core :as llm]
            [agent.tools.core :as tools]))

;; Create a simple agent
(def my-agent
  (agent/create
   {:name "Research Assistant"
    :llm (llm/openai {:api-key (System/getenv "OPENAI_API_KEY")})
    :tools [(tools/web-search)
            (tools/file-reader)]
    :memory-size 1000}))

;; Run the agent
(agent/run my-agent "Research recent AI developments")
```

### Multi-Head Decision System
```clojure
(ns my-app.decision
  (:require [agent.multi-head :as multi-head]))

;; Create decision heads with different priorities
(def decision-system
  (multi-head/create
   {:heads [{:id :safety :priority 10 :weight 0.3}
            {:id :efficiency :priority 8 :weight 0.4}
            {:id :creativity :priority 6 :weight 0.3}]
    :consensus-threshold 0.7}))

;; Make a collaborative decision
(multi-head/decide decision-system {:task "Generate marketing copy"})
```

### Knowledge Graph Integration
```clojure
(ns my-app.kg
  (:require [agent.knowledge-graph :as kg]))

;; Create and query knowledge graph
(def knowledge-base
  (kg/create-graph {:backend :asami
                    :storage-path "data/kg.db"}))

;; Add facts
(kg/add-fact knowledge-base
  {:subject "AI"
   :predicate :has-application
   :object "Healthcare"})

;; Query
(kg/query knowledge-base
  '[:find ?app
    :where [?ai :type "AI"]
           [?ai :has-application ?app]])
```

## Getting Started

### Installation

1. **Clone the repository** (or copy the project structure)
2. **Install dependencies**:
   ```bash
   cd clj-agent
   clojure -P  # Download dependencies
   ```
3. **Set up environment variables**:
   ```bash
   # For OpenAI integration
   export OPENAI_API_KEY="your-api-key-here"
   
   # Optional: Agent configuration
   export AGENT_ENV="development"
   export AGENT_LOG_LEVEL="info"
   ```

### Quick Test

```clojure
;; In a REPL or .clj file
(require '[agent.example :as ex])
(require '[agent.example-kg :as ex-kg])
(require '[agent.example-multi-head :as ex-mh])

;; Run basic examples
(ex/-main)        ; LLM integration examples
(ex-kg/-main)     ; Knowledge graph examples  
(ex-mh/-main)     ; Multi-head decision examples
```

## Basic Usage Patterns

### Pattern 1: Simple Agent with LLM

```clojure
(ns my-app.simple-agent
  (:require [agent.llm :as llm]
            [clojure.pprint :refer [pprint]]))

(defn ask-agent [question]
  (let [provider (llm/create-openai-provider {})]
    (llm/simple-completion provider question)))

;; Usage
(ask-agent "What is functional programming?")
```

### Pattern 2: Knowledge-Based Agent

```clojure
(ns my-app.knowledge-agent
  (:require [agent.llm :as llm]
            [agent.knowledge-graph :as kg]))

(defn create-knowledge-agent [name]
  (let [llm-provider (llm/create-openai-provider {})
        knowledge-graph (kg/create-in-memory-graph {:name name})]
    {:llm llm-provider
     :kg knowledge-graph
     :name name}))

(defn teach-agent [agent fact-triples]
  (doseq [[subject predicate object] fact-triples]
    (kg/store-triple (:kg agent) subject predicate object))
  agent)

(defn query-agent [agent question]
  (let [response (llm/simple-completion (:llm agent) question)
        ;; Extract and store knowledge
        facts (extract-facts question response)]
    (teach-agent agent facts)
    response))
```

### Pattern 3: Decision-Making Agent

```clojure
(ns my-app.decision-agent
  (:require [agent.llm :as llm]
            [agent.knowledge-graph :as kg]
            [agent.multi-head :as mh]))

(defn create-decision-agent []
  (let [llm-provider (llm/create-openai-provider {})
        knowledge-graph (kg/create-in-memory-graph {:name "decision-agent"})
        orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
    {:orchestrator orchestrator
     :knowledge-graph knowledge-graph}))

(defn consult-agent [agent context options]
  (let [result (mh/make-decision (:orchestrator agent) context options)]
    ;; Store decision for learning
    (kg/store-fact (:knowledge-graph agent)
                  (keyword (str "decision-" (System/currentTimeMillis)))
                  :made-decision (:decision result))
    result))
```

## Common Workflows

### Workflow 1: Technology Evaluation

```clojure
(defn evaluate-technology-stack [requirements]
  (let [agent (create-decision-agent)
        context (format "Evaluate technology stack for: %s" requirements)
        options ["Microservices with Kubernetes"
                 "Monolith with vertical scaling"
                 "Serverless with AWS Lambda"
                 "Event-driven architecture"]]
    
    (consult-agent agent context options)))
```

### Workflow 2: Team Meeting Facilitation

```clojure
(defn facilitate-team-meeting [topic participants]
  (let [knowledge-graph (kg/create-in-memory-graph {:name "meeting-minutes"})
        ;; Create a head for each participant
        heads (map (fn [participant]
                     (mh/->DecisionHead (:id participant)
                                        (:name participant)
                                        (:role participant)
                                        llm-provider
                                        knowledge-graph))
                   participants)
        orchestrator (assoc (mh/->DecisionOrchestrator [] knowledge-graph)
                            :heads heads)]
    
    (fn discuss [agenda-item options]
      (let [result (mh/make-decision orchestrator agenda-item options)]
        ;; Generate meeting minutes
        (generate-minutes knowledge-graph agenda-item result)
        result))))
```

### Workflow 3: Continuous Learning System

```clojure
(defn create-learning-agent []
  (let [knowledge-graph (kg/create-in-memory-graph {:name "learning-agent"})
        agent (atom {:knowledge-graph knowledge-graph
                     :decision-history []})]
    
    {:learn (fn [interaction]
              (let [{:keys [question answer]} interaction]
                ;; Extract facts from interaction
                (doseq [fact (extract-facts question answer)]
                  (kg/store-triple knowledge-graph
                                  (:subject fact)
                                  (:predicate fact)
                                  (:object fact)))))
     
     :decide (fn [context options]
               (let [orchestrator (mh/create-orchestrator
                                   (llm/create-openai-provider {})
                                   knowledge-graph)
                     result (mh/make-decision orchestrator context options)]
                 ;; Store in history
                 (swap! agent update :decision-history conj result)
                 result))
     
     :knowledge (fn [] @agent)}))
```

## Example Applications

The project includes several working examples in the `/examples/` directory:

### 1. Consensus Algorithm Demo (`consensus_demo.clj`)
Demonstrates distributed consensus algorithms:
- **Raft leader election** and log replication
- **Paxos consensus** on specific values
- **Fault tolerance scenarios** with node failures
- **Integration** with distributed coordination systems

```clojure
;; Run the consensus demo
(require '[examples.consensus-demo :as demo])

;; Create a Raft cluster with 3 nodes
(def raft-cluster (demo/create-raft-cluster 3))

;; Demonstrate Paxos consensus
(def paxos-nodes (demo/create-paxos-nodes 3))
```

### 2. Market-Based Allocation (`market_allocation.clj`)
Shows market-based task allocation system:
- **Task marketplace** with bidding mechanism
- **Resource allocation** based on agent capabilities
- **Dynamic pricing** and load balancing
- **Real-time monitoring** of market activity

```clojure
;; Run market allocation demo
(require '[examples.market-allocation :as market])

;; Create market with multiple agents
(def marketplace (market/create-marketplace 5))

;; Submit tasks and observe allocation
(market/submit-task marketplace {:type :research :priority :high})
```

### 3. Distributed Orchestrator (`distributed_orchestrator.clj`)
Demonstrates multi-agent orchestration:
- **Worker registration** and health monitoring
- **Task distribution** with load balancing
- **Failure recovery** and checkpointing
- **Real-time coordination** between agents

```clojure
;; Run orchestrator demo
(require '[examples.distributed-orchestrator :as orchestrator])

;; Create orchestrator with worker pool
(def system (orchestrator/create-system 3))

;; Submit complex workflow
(orchestrator/submit-workflow system complex-workflow)
```

### 4. Advanced Coordination (`advanced_coordination.clj`)
Shows sophisticated coordination patterns:
- **Multi-level decision making** with hierarchical agents
- **Conflict resolution** between agent groups
- **Resource negotiation** and sharing
- **Collaborative problem solving**

```clojure
;; Run advanced coordination demo
(require '[examples.advanced-coordination :as coordination])

;; Create coordinated agent system
(def agent-system (coordination/create-system {:agents 4 :levels 2}))

;; Execute coordinated task
(coordination/execute-task agent-system complex-task)
```

### Running Examples

1. **Navigate to project directory**:
   ```bash
   cd ~/projects/clj-agent
   ```

2. **Start a REPL**:
   ```bash
   clojure -M:dev
   ```

3. **Load and run examples**:
   ```clojure
   ;; In the REPL
   (require '[examples.consensus-demo :as demo])
   (demo/run-demo)
   ```

4. **Run validation script** (no Clojure required):
   ```bash
   ./validate_examples.sh
   ```

### Testing Examples

Test files are available in `/test/examples/`:
```clojure
;; Run example tests
(require '[test.examples.consensus-demo-test])
(clojure.test/run-tests 'test.examples.consensus-demo-test)
```

## Integration Examples

### Example 1: Web Service Integration

```clojure
(ns my-app.web-service
  (:require [agent.llm :as llm]
            [agent.multi-head :as mh]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :as middleware]
            [compojure.core :refer [defroutes POST GET]]))

(defonce agent-system
  (delay
    (let [llm-provider (llm/create-openai-provider {})
          knowledge-graph (kg/create-in-memory-graph {:name "web-agent"})]
      {:llm llm-provider
       :orchestrator (mh/create-orchestrator llm-provider knowledge-graph)})))

(defn handle-decision-request [request]
  (let [body (:body request)
        context (:context body)
        options (:options body)
        result (mh/make-decision (:orchestrator @agent-system)
                                 context
                                 options)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body result}))

(defroutes app
  (POST "/decide" request (handle-decision-request request))
  (GET "/health" _ {:status 200 :body "OK"}))

(defn start-server [port]
  (jetty/run-jetty (middleware/wrap-json-body app {:keywords? true})
                   {:port port :join? false}))
```

### Example 2: CLI Tool

```clojure
(ns my-app.cli
  (:require [agent.llm :as llm]
            [agent.multi-head :as mh]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]))

(def cli-options
  [["-c" "--context CONTEXT" "Decision context"]
   ["-o" "--options OPTIONS" "Comma-separated options" :parse-fn #(str/split % #",")]
   ["-v" "--verbose" "Verbose output"]])

(defn -main [& args]
  (let [{:keys [options arguments errors]} (parse-opts args cli-options)
        llm-provider (llm/create-openai-provider {})
        knowledge-graph (kg/create-in-memory-graph {:name "cli-agent"})
        orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
    
    (when errors
      (doseq [error errors]
        (println error))
      (System/exit 1))
    
    (when (and (:context options) (:options options))
      (let [result (mh/make-decision orchestrator
                                     (:context options)
                                     (:options options))]
        (println "Decision:" (:decision result))
        (println "Consensus:" (:consensus result))
        
        (when (:verbose options)
          (println "\nEvaluations:")
          (doseq [eval (:evaluations result)]
            (println "  " (:head-name eval) "->" (:choice eval))))))))
```

### Example 3: Interactive REPL Session

```clojure
;; In your REPL, evaluate these forms:

;; 1. Start an agent session
(require '[agent.llm :as llm]
         '[agent.knowledge-graph :as kg]
         '[agent.multi-head :as mh])

(def my-agent
  (let [llm (llm/create-openai-provider {})
        kg (kg/create-in-memory-graph {:name "repl-agent"})]
    {:llm llm
     :kg kg
     :orchestrator (mh/create-orchestrator llm kg)}))

;; 2. Teach the agent
(kg/store-triple (:kg my-agent) :clojure :type :programming-language)
(kg/store-triple (:kg my-agent) :clojure :paradigm :functional)
(kg/store-triple (:kg my-agent) :clojure :creator "Rich Hickey")

;; 3. Make decisions
(def decision
  (mh/make-decision (:orchestrator my-agent)
                    "Choose a language for data processing"
                    ["Python" "Clojure" "Julia" "R"]))

;; 4. Inspect results
(:decision decision)      ; => "Clojure" (or another choice)
(:consensus decision)     ; => 0.75 (example consensus)

;; 5. View individual evaluations
(doseq [eval (:evaluations decision)]
  (println (:head-name eval) "chose" (:choice eval)
           "with confidence" (:confidence eval)))

;; 6. Query knowledge
(kg/find-entities (:kg my-agent) :programming-language)
(kg/get-facts (:kg my-agent) :clojure)
```

## Testing Your Integration

### Unit Testing with Mocks

```clojure
(ns my-app.test
  (:require [clojure.test :refer :all]
            [agent.test-framework :as tf]))

(use-fixtures :each (tf/with-test-agent-fixture))

(deftest test-my-integration
  (testing "Decision integration"
    (let [llm-provider (tf/create-mock-llm-provider {})
          knowledge-graph (tf/create-mock-knowledge-graph)
          orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
      
      (is (mh/list-heads orchestrator))
      
      (let [result (mh/make-decision orchestrator "test" ["A" "B"])]
        (is (:decision result))
        (is (<= 0 (:consensus result) 1))))))
```

### Integration Testing

```clojure
(deftest ^:integration test-web-integration
  (testing "Web service integration"
    (let [server (start-server 8080)]
      (try
        (let [response (http/post "http://localhost:8080/decide"
                                  {:body {:context "test"
                                          :options ["A" "B"]}
                                   :as :json})]
          (is (= 200 (:status response)))
          (is (contains? (:body response) :decision)))
        (finally
          (.stop server))))))
```

## Performance Tips

### 1. Caching LLM Responses

```clojure
(defn cached-llm-provider [base-provider cache]
  (reify llm/ILLMProvider
    (complete [_ messages opts]
      (let [cache-key (hash [messages opts])]
        (if-let [cached (get @cache cache-key)]
          cached
          (let [response (llm/complete base-provider messages opts)]
            (swap! cache assoc cache-key response)
            response))))
    
    ;; Implement other methods...
    ))
```

### 2. Batch Processing

```clojure
(defn batch-decisions [orchestrator decisions]
  (pmap (fn [{:keys [context options]}]
          (mh/make-decision orchestrator context options))
        decisions))
```

### 3. Async Processing

```clojure
(defn async-decision [orchestrator context options]
  (let [result-promise (promise)]
    (future
      (deliver result-promise
               (mh/make-decision orchestrator context options)))
    result-promise))
```

## Troubleshooting Common Issues

### Issue 1: LLM API Errors

**Symptoms**: `Exception: OpenAI API key required`

**Solution**:
```bash
# Set environment variable
export OPENAI_API_KEY="your-key-here"

# Or pass explicitly
(llm/create-openai-provider {:api-key "your-key-here"})
```

### Issue 2: Slow Decision Making

**Symptoms**: Decisions take >10 seconds

**Solutions**:
1. Reduce number of options (3-5 optimal)
2. Use mock providers for testing
3. Implement caching
4. Run heads in parallel (future implementation)

### Issue 3: Knowledge Graph Performance

**Symptoms**: Slow queries with many facts

**Solutions**:
1. Use indexing for frequent queries
2. Limit fact extraction to relevant information
3. Consider persistent storage for large knowledge bases

### Issue 4: Flow Integration Issues

**Symptoms**: Flow doesn't start or hangs

**Solutions**:
1. Check core.async version compatibility
2. Ensure all flow steps are properly defined
3. Use timeouts for async operations
4. Enable flow tracing for debugging

## Next Steps

### 1. Production Deployment

- Add monitoring and metrics
- Implement persistence for knowledge graphs
- Add authentication and authorization
- Set up CI/CD pipeline

### 2. Advanced Features

- Custom decision heads for domain-specific expertise
- Integration with external knowledge bases
- Real-time collaboration features
- Advanced conflict resolution algorithms

### 3. Scaling

- Distributed decision making
- Load balancing across multiple agents
- Caching and optimization
- Horizontal scaling strategies

## Getting Help

- **Documentation**: Check `/obsidian/` for detailed documentation
- **Examples**: Review `/examples/` directory
- **Tests**: Look at `/test/` for usage patterns
- **Issues**: Report problems with reproduction steps

## Contributing

1. Follow the protocol-based architecture
2. Write tests for new features
3. Update documentation in Obsidian
4. Add examples for new functionality
5. Ensure backward compatibility
