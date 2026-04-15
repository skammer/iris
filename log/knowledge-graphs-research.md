# Knowledge Graphs Research: Noumenon and Oxford Semantic
Date: 2026-04-15

## Overview
Knowledge graphs are essential for augmenting agent reasoning. Based on references in README.md, we need to research:
1. **Noumenon** (noumenon.leifericf.com) - Knowledge graph system
2. **Oxford Semantic** (RDFox) - Semantic reasoning engine with negation-as-failure

## Oxford Semantic Technologies (RDFox)

### Company Background
- **Oxford University spinout**: Founded by three professors from Oxford Computer Science
- **Acquisition**: Recently acquired by Samsung Electronics
- **Expertise**: Decades of research in Knowledge Representation and Reasoning (KRR)
- **Leadership**: Professor Ian Horrocks (BCS Lovelace Medal winner)

### RDFox Technology

#### Core Capabilities
1. **Semantic Reasoning Engine**
   - Knowledge-based reasoning similar to human cognition
   - Deduces and builds new knowledge from facts
   - Focus on accuracy, truth, and explainability

2. **High-Performance Knowledge Graph**
   - **In-memory operation**: Unique among knowledge graphs
   - **Performance**: Outperforms other graph technologies by orders of magnitude
   - **Scale**: Handles billions of triples with complex relationships
   - **Real-time results**: High availability and speed

3. **Negation-as-Failure**
   - Advanced reasoning capability mentioned in README
   - Logical inference where absence of evidence is treated as evidence of absence
   - Critical for complex decision-making and uncertainty handling

#### Technical Features
- **Knowledge Graph Database**: Stores data in network structure
- **Semantic Reasoning**: Rules-based AI with human-like reasoning
- **Deployment flexibility**: From tiny edge devices to massive cloud instances
- **Enterprise features**: Beyond basic reasoning capabilities

### Use Cases
1. **Semantic Search & Recommendation**
2. **Rules & Regulations** (compliance, policy enforcement)
3. **Configuration Management**
4. **Autonomous Vehicles** (decision-making systems)

## Noumenon (Inferred from Context)

### Based on Domain Name
- **Domain**: noumenon.leifericf.com
- **"Noumenon"**: Philosophical term meaning "thing-in-itself" (Kantian philosophy)
- **Likely focus**: Fundamental knowledge representation system

### Potential Characteristics
1. **Philosophical foundation**: Based on epistemological principles
2. **Knowledge representation**: Focus on core entities and relationships
3. **Reasoning capabilities**: Logical inference and deduction
4. **Integration**: Possibly works with semantic web standards (RDF, OWL)

## Knowledge Graph Concepts for AI Agents

### Why Essential for Agents
1. **Structured knowledge**: Organizes information in graph format (entities, relationships)
2. **Reasoning augmentation**: Enables logical inference beyond LLM capabilities
3. **Memory enhancement**: Long-term, structured memory for agents
4. **Explainability**: Traceable reasoning paths for decisions

### Key Components

#### 1. Graph Structure
- **Nodes**: Entities, concepts, objects
- **Edges**: Relationships, properties, connections
- **Properties**: Attributes, metadata, weights

#### 2. Reasoning Capabilities
- **Logical inference**: Deductive reasoning
- **Negation-as-failure**: Handling absence of information
- **Rule-based systems**: If-then logic for decision making
- **Semantic similarity**: Finding related concepts

#### 3. Integration with LLMs
- **Retrieval-augmented generation**: Use knowledge graph for context
- **Fact verification**: Check LLM outputs against known facts
- **Knowledge expansion**: Add new information to graph from LLM insights

## Implementation Considerations for Clojure Agent

### 1. Knowledge Graph Storage Options

#### Local/Embedded
```clojure
; Option 1: Native Clojure data structures
(def knowledge-graph
  {:entities {}
   :relationships {}
   :inferences {}})

; Option 2: Graph database integration
; - Datomic (native Clojure)
; - Neo4j (via bolt protocol)
; - Apache TinkerPop/Gremlin
```

#### RDFox Integration
- **Commercial product**: Requires licensing
- **High performance**: In-memory reasoning engine
- **SPARQL support**: Standard query language
- **Rule-based reasoning**: Advanced inference capabilities

### 2. Negation-as-Failure Implementation

#### Basic Pattern
```clojure
(defn negation-as-failure [query knowledge-base]
  (if (contains? knowledge-base query)
    true
    (if (exhaustively-searched? knowledge-base query)
      false
      :unknown))) ; Negation as failure

(defn exhaustive-search? [kb query]
  ; Determine if all possible sources have been checked
  (complete-coverage? kb (query-domain query)))
```

#### Use Cases for Agents
1. **Closed-world assumption**: Treat absence of information as false
2. **Default reasoning**: Make assumptions when information is incomplete
3. **Exception handling**: Special cases override general rules

### 3. Semantic Reasoning Patterns

#### Rule-based Reasoning
```clojure
(def rules
  {:if-all [:bird :can-fly]
   :then :flies
   
   :if [:penguin :bird]
   :then [:not :can-fly]})

(defn apply-rules [facts rules]
  (loop [inferred facts]
    (let [new-facts (infer-from-rules inferred rules)]
      (if (= new-facts inferred)
        inferred
        (recur new-facts)))))
```

#### Graph Traversal
```clojure
(defn find-related-concepts [graph start-concept depth]
  (let [traverse (fn traverse [node current-depth visited]
                   (when (<= current-depth depth)
                     (let [neighbors (get-in graph [:relationships node])]
                       (reduce (fn [acc neighbor]
                                 (if (contains? visited neighbor)
                                   acc
                                   (conj acc neighbor
                                         (traverse neighbor (inc current-depth)
                                                   (conj visited neighbor)))))
                               [node]
                               neighbors))))]
    (traverse start-concept 0 #{})))
```

## Integration with Agent Architecture

### 1. Memory Layer Enhancement
- **Short-term**: LLM context window
- **Medium-term**: Vector embeddings (semantic search)
- **Long-term**: Knowledge graph (structured, relational)

### 2. Reasoning Pipeline
```clojure
(defn agent-reasoning-pipeline [query]
  (let [context (retrieve-context query)           ; From vector store
        facts (extract-facts context)              ; Entity/relationship extraction
        inferred (reason-with-knowledge-graph facts) ; Graph-based reasoning
        answer (generate-answer query inferred)]   ; LLM generation
    answer))
```

### 3. Knowledge Acquisition
- **From conversations**: Extract entities and relationships
- **From documents**: Parse structured information
- **From LLM outputs**: Fact extraction and verification
- **From external sources**: APIs, databases, web scraping

## Recommendations for Clojure Implementation

### Phase 1: Basic Knowledge Graph
1. Implement simple graph structure with Clojure data types
2. Add basic traversal and query functions
3. Integrate with agent memory system

### Phase 2: Reasoning Capabilities
1. Add rule-based inference engine
2. Implement negation-as-failure logic
3. Create semantic similarity functions

### Phase 3: Advanced Integration
1. Evaluate RDFox or similar commercial solutions
2. Implement SPARQL query interface
3. Add learning capabilities (graph expansion)

### Phase 4: Production Features
1. Add persistence layer (Datomic/Neo4j)
2. Implement caching and performance optimization
3. Create monitoring and debugging tools

## Key Takeaways

### 1. Knowledge Graphs vs Vector Stores
- **Knowledge graphs**: Structured, relational, explainable
- **Vector stores**: Semantic, similarity-based, fuzzy
- **Combination**: Hybrid approach for comprehensive memory

### 2. Reasoning Enhancement
- **Logical inference**: Beyond pattern matching
- **Explainability**: Traceable decision paths
- **Consistency**: Maintain logical coherence

### 3. Implementation Strategy
- **Start simple**: Basic graph structure in Clojure
- **Iterate**: Add reasoning capabilities gradually
- **Evaluate**: Consider commercial solutions for advanced needs

## References
- Oxford Semantic Technologies: https://oxfordsemantic.tech
- RDFox documentation (inaccessible due to restrictions)
- Knowledge graph concepts and implementations
- Negation-as-failure reasoning patterns