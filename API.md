# Iris - API Documentation

> Legacy archive: this document describes pre-rewrite namespaces now quarantined under `legacy_src/`. Current canonical runtime is `agent.core` + `agent.api`.

## Overview

The Iris is a modular, extensible agent system built with Clojure. It features:

- **Flow-based processing** using core.async.flow
- **Protocol-based architecture** for extensibility
- **Multi-head decision making** inspired by Evangelion/Portal
- **Knowledge graph integration** for structured reasoning
- **Comprehensive testing framework** with mocks and scenarios

## Quick Start

### 1. Installation

Add to your `deps.edn`:

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
        org.clojure/core.async {:mvn/version "1.6.681"}
        clj-http/clj-http {:mvn/version "3.12.3"}
        cheshire/cheshire {:mvn/version "5.11.0"}
        org.clojars.quoll/asami {:mvn/version "2.2.4"}}}
```

### 2. Basic Usage

```clojure
(ns my-app.core
  (:require [agent.llm :as llm]
            [agent.knowledge-graph :as kg]
            [agent.multi-head :as mh]))

;; Create LLM provider (requires OPENAI_API_KEY env var)
(def llm-provider (llm/create-openai-provider {}))

;; Create knowledge graph
(def knowledge-graph (kg/create-in-memory-graph {:name "my-agent"}))

;; Create multi-head decision orchestrator
(def orchestrator (mh/create-orchestrator llm-provider knowledge-graph))

;; Make a decision
(def decision (mh/make-decision orchestrator
                                "Choose programming language"
                                ["Clojure" "Python" "Rust"]))
```

## Core Components

### 1. LLM Integration (`agent.llm`)

#### Protocols

```clojure
(defprotocol ILLMProvider
  (complete [this messages opts])
  (stream [this messages opts])
  (embed [this text opts]))
```

#### Implementations

- `OpenAIProvider`: OpenAI API integration
- `MockLLMProvider`: For testing (in test framework)

#### Usage

```clojure
;; Create provider
(def provider (llm/create-openai-provider {:api-key "sk-..."}))

;; Complete
(llm/complete provider
              [{:role "user" :content "Hello"}]
              {:temperature 0.7})

;; Stream
(let [ch (llm/stream provider messages opts)]
  (async/<!! ch))

;; Embed
(llm/embed provider "text to embed" {})
```

### 2. Knowledge Graph (`agent.knowledge-graph`)

#### Protocols

```clojure
(defprotocol IKnowledgeGraph
  (store-fact [this subject predicate object])
  (query [this pattern])
  (find-entities [this type])
  (get-facts [this subject])
  (infer [this rules]))
```

#### Implementations

- `AsamiKnowledgeGraph`: Asami graph database backend
- `MockKnowledgeGraph`: For testing

#### Usage

```clojure
;; Create graph
(def kg (kg/create-in-memory-graph {:name "my-kg"}))

;; Store facts
(kg/store-fact kg :clojure :type :programming-language)
(kg/store-triple kg :clojure :paradigm :functional)

;; Add entity
(kg/add-entity kg :agent-1 :ai-agent
               {:name "Test Agent"
                :capabilities [:reasoning :learning]})

;; Query
(kg/find-entities kg :programming-language)
(kg/get-facts kg :clojure)
```

### 3. Multi-Head Decision Making (`agent.multi-head`)

#### Protocols

```clojure
(defprotocol IDecisionHead
  (evaluate [this context options])
  (specialty [this])
  (confidence [this evaluation])
  (explain [this evaluation]))

(defprotocol IDecisionOrchestrator
  (add-head [this head])
  (remove-head [this head-id])
  (list-heads [this])
  (make-decision [this context options])
  (resolve-conflict [this evaluations])
  (consensus-level [this evaluations]))
```

#### Standard Heads

1. **Analytical** - Logic and analysis
2. **Creative** - Innovation and possibilities
3. **Practical** - Feasibility and implementation
4. **Ethical** - Ethics and values
5. **Strategic** - Long-term planning

#### Usage

```clojure
;; Create orchestrator with standard heads
(def orchestrator (mh/create-orchestrator llm-provider knowledge-graph))

;; List heads
(mh/list-heads orchestrator)

;; Make decision
(def result (mh/make-decision orchestrator
                              "Context"
                              ["Option 1" "Option 2" "Option 3"]))

;; Access results
(:decision result)      ; Final choice
(:consensus result)     ; Consensus level (0-1)
(:evaluations result)   ; All head evaluations

;; Add custom head
(def custom-head (mh/->DecisionHead :security "Security Expert"
                                    "security and privacy"
                                    llm-provider knowledge-graph))
(def updated (mh/add-head orchestrator custom-head))
```

### 4. Knowledge Graph Integration (`agent.kg-integration`)

#### Flow Steps

- `knowledge-extractor`: Extract facts from text
- `knowledge-query`: Query knowledge graph
- `knowledge-reasoner`: Apply inference rules
- `multi-head-decider`: Multi-head decision flow step

#### Usage

```clojure
;; Store interaction
(kgi/store-interaction "User query" "Agent response")

;; Extract knowledge
(kgi/extract-simple-facts "Clojure is a functional language")

;; Query relevant knowledge
(kgi/query-relevant-knowledge "Tell me about programming languages")
```

### 5. Flow Integration (`flow.clj`)

#### Core Flow

```clojure
;; Start agent session
(def fw (start-session! {}))

(def chs (flow/start fw))
(flow/resume fw)

;; Inject prompt
@(flow/inject fw [:agent :prompt] ["Hello, agent!"])

;; Check results
(async/poll! (:report-chan chs))
```

## Examples

### Complete Example: Technology Selection

```clojure
(ns my-app.technology-selection
  (:require [agent.llm :as llm]
            [agent.knowledge-graph :as kg]
            [agent.multi-head :as mh]))

(defn select-technology []
  (let [llm-provider (llm/create-openai-provider {})
        knowledge-graph (kg/create-in-memory-graph {:name "tech-selection"})
        orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
    
    (def context "Choosing backend technology for scalable web service")
    (def options ["Clojure + http-kit - functional, async, JVM"
                  "Go + Gin - performance, simplicity, concurrency"
                  "Python + FastAPI - rapid development, AI integration"
                  "Rust + Actix - safety, performance, systems programming"])
    
    (let [result (mh/make-decision orchestrator context options)]
      {:decision (:decision result)
       :consensus (:consensus result)
       :evaluations (map #(select-keys % [:head-name :choice :confidence])
                         (:evaluations result))})))
```

### Example: Team Decision Making

```clojure
(ns my-app.team-decision
  (:require [agent.multi-head :as mh]
            [agent.test-framework :as tf]))

(defn team-decision-meeting []
  (let [knowledge-graph (tf/create-mock-knowledge-graph)
        ;; Create team roles as decision heads
        heads [(tf/create-mock-decision-head :engineering "Engineering Lead"
                                             "technical feasibility" "Option A" 0.9)
               (tf/create-mock-decision-head :product "Product Manager"
                                             "user value" "Option B" 0.8)
               (tf/create-mock-decision-head :design "Design Lead"
                                             "user experience" "Option C" 0.7)
               (tf/create-mock-decision-head :business "Business Analyst"
                                             "ROI" "Option A" 0.85)]
        orchestrator (assoc (mh/->DecisionOrchestrator [] knowledge-graph)
                            :heads heads)]
    
    (mh/make-decision orchestrator
                      "Q2 feature prioritization"
                      ["User Profiles" "API Integration" "Analytics Dashboard"])))
```

## Testing

### Using the Testing Framework

```clojure
(ns my-app.test
  (:require [clojure.test :refer :all]
            [agent.test-framework :as tf]))

(use-fixtures :each (tf/with-test-agent-fixture))

(deftest test-decision-making
  (let [llm-provider (tf/create-mock-llm-provider {})
        knowledge-graph (tf/create-mock-knowledge-graph)
        orchestrator (mh/create-orchestrator llm-provider knowledge-graph)]
    
    (testing "Basic decision"
      (let [result (mh/make-decision orchestrator "test" ["A" "B"])]
        (is (:decision result))
        (is (<= 0 (:consensus result) 1))))))
```

### Running Tests

```bash
# Run all tests
clojure -M:test

# Run specific test suites
clojure -M:test -n agent.test-framework
clojure -M:test -n agent.integration-tests
clojure -M:test -n agent.end-to-end-tests

# Run by tag
clojure -M:test -v :framework
clojure -M:test -v :integration
clojure -M:test -v :scenario
```

## Configuration

### Environment Variables

```bash
# OpenAI API (for LLM integration)
export OPENAI_API_KEY="sk-..."

# Agent configuration
export AGENT_ENV="development"  # or "production"
export AGENT_LOG_LEVEL="info"
```

### Project Structure

```
iris/
├── src/agent/
│   ├── llm.clj              # LLM integration
│   ├── knowledge_graph.clj  # Knowledge graph
│   ├── multi_head.clj       # Multi-head decision making
│   ├── kg_integration.clj   # Knowledge graph integration
│   └── ...                  # Other components
├── test/agent/
│   ├── test_framework.clj   # Testing framework
│   ├── integration_tests.clj # Integration tests
│   └── end_to_end_tests.clj # End-to-end tests
├── examples/
│   ├── example.clj          # Basic examples
│   ├── example_kg.clj       # Knowledge graph examples
│   └── example_multi_head.clj # Decision making examples
├── obsidian/                # Documentation vault
├── log/                     # Implementation logs
├── deps.edn                 # Dependencies
├── flow.clj                 # Core flow integration
└── TODO.md                  # Project tracking
```

## Advanced Usage

### Custom Decision Heads

```clojure
(defrecord SecurityExpert [id name llm-provider knowledge-graph]
  mh/IDecisionHead
  (evaluate [_ context options]
    ;; Custom security-focused evaluation
    {:head-id id
     :head-name name
     :choice (security-analysis context options)
     :reasoning "Security-first evaluation"
     :confidence 0.9
     :risks ["Potential vulnerabilities"]
     :benefits ["Enhanced security"]})
  
  (specialty [_] "Security and Privacy")
  
  (confidence [_ evaluation] (:confidence evaluation))
  
  (explain [_ evaluation] (:reasoning evaluation)))
```

### Custom Knowledge Graph Backend

```clojure
(defrecord CustomKnowledgeGraph [connection]
  kg/IKnowledgeGraph
  (store-fact [_ subject predicate object]
    ;; Custom storage logic
    )
  
  (query [_ pattern]
    ;; Custom query logic
    )
  
  ;; Implement other protocol methods
  )
```

### Flow Customization

```clojure
(defn custom-agent-flow []
  (flow/flow
   {:steps {:input     custom-input-step
            :reason    custom-reasoning-step
            :decide    mh/multi-head-decider
            :enhance   kgi/knowledge-enhancer
            :output    custom-output-step}
    :conns [[:input :reason]
            [:reason :decide]
            [:decide :enhance]
            [:enhance :output]]}))
```

## Troubleshooting

### Common Issues

1. **LLM API errors**: Check `OPENAI_API_KEY` environment variable
2. **Knowledge graph errors**: Ensure Asami dependency is included
3. **Flow not starting**: Check core.async version compatibility
4. **Test failures**: Run with `-v` flag for verbose output

### Debugging

```clojure
;; Enable debug logging
(System/setProperty "agent.log.level" "debug")

;; Trace flow execution
(flow/trace fw true)  ; Enable flow tracing

;; Inspect knowledge graph state
(kg/get-facts knowledge-graph :all)
```

## Troubleshooting

### Common Issues

#### 1. Flow Processing Stalls
**Symptoms**: Agent appears stuck, no progress in flow execution.
**Solutions**:
- Check flow topology for cycles: `(flow/debug-topology fw)`
- Increase flow buffer sizes: `(flow/configure {:buffer-size 1000})`
- Enable tracing: `(flow/trace fw true)`

#### 2. LLM Integration Failures
**Symptoms**: Timeouts or connection errors with LLM providers.
**Solutions**:
- Verify API keys and endpoints
- Check network connectivity: `(llm/test-connection provider)`
- Adjust timeout settings: `(llm/configure {:timeout-ms 30000})`
- Enable debug logging: `(System/setProperty "llm.debug" "true")`

#### 3. Knowledge Graph Performance
**Symptoms**: Slow queries or memory issues.
**Solutions**:
- Index frequently queried properties
- Use query optimization: `(kg/optimize-query query)`
- Implement caching: `(kg/with-cache knowledge-graph)`
- Monitor memory usage: `(kg/stats knowledge-graph)`

#### 4. Multi-Head Decision Conflicts
**Symptoms**: Inconsistent or contradictory decisions.
**Solutions**:
- Review decision weights: `(multi-head/get-weights system)`
- Check consensus thresholds: `(multi-head/get-thresholds system)`
- Enable decision logging: `(multi-head/enable-logging system true)`
- Use tie-breaking rules: `(multi-head/set-tie-breaker system :priority)`

#### 5. Distributed Coordination Issues
**Symptoms**: Lost messages or inconsistent state across nodes.
**Solutions**:
- Check network connectivity between nodes
- Verify consensus algorithm health: `(consensus/health-check cluster)`
- Monitor message queues: `(distributed/monitor-queues system)`
- Enable detailed logging: `(System/setProperty "distributed.debug" "true")`

### Debugging Tools

```clojure
;; Get system health status
(agent/health-check system)

;; View component dependencies
(component/dependency-graph system)

;; Monitor performance metrics
(monitoring/get-metrics system)

;; Generate diagnostic report
(diagnostics/generate-report system)
```

## Contributing

1. Follow protocol-based architecture
2. Write tests using the testing framework
3. Document new components in Obsidian vault
4. Update API documentation
5. Run all tests before submitting

## License

[Specify license here]

## Support

- Issues: [GitHub Issues]
- Documentation: Obsidian vault in `/obsidian/`
- Examples: `/examples/` directory
- API Reference: This document
