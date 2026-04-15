# Multi-Head Decision Making Implementation

## Overview
Implemented a multi-head decision making system inspired by "terminal dogma" from Evangelion and "personality cores" from Portal. The system enables collegial decision making with split responsibilities among specialized decision heads.

## Core Concept
Multiple "personality cores" (decision heads) with different specialties evaluate options independently, then an orchestrator resolves conflicts and reaches consensus.

## Files Created

### 1. `src/agent/multi_head.clj`
Core multi-head decision system with:
- `IDecisionHead` protocol for individual decision heads
- `DecisionHead` record implementing the protocol
- `IDecisionOrchestrator` protocol for coordinating multiple heads
- `DecisionOrchestrator` record implementing coordination
- Conflict resolution algorithms
- Consensus calculation
- Integration with knowledge graph for decision history

### 2. `example_multi_head.clj`
Comprehensive examples demonstrating:
- Basic multi-head decision making
- Conflict resolution scenarios
- Flow integration
- Decision history tracking
- Custom head creation

### 3. `test_multi_head.clj`
Test suite for the multi-head decision system.

## Key Design Decisions

### Protocol-Based Architecture
Used Clojure protocols for both individual heads and orchestrators, enabling:
- Easy addition of new head types
- Custom orchestrator implementations
- Testable, modular components

### Specialized Decision Heads
Standard heads with different perspectives:
1. **Analytical** - Logic and analysis focus
2. **Creative** - Innovation and possibilities
3. **Practical** - Feasibility and implementation
4. **Ethical** - Ethics and values
5. **Strategic** - Long-term planning

### Conflict Resolution Algorithm
Three-tier resolution strategy:
1. **Unanimous choice** - All heads agree
2. **Majority vote** - Most heads choose same option
3. **Weighted confidence** - Weight by head confidence levels
4. **Tie-breaking** - First among tied options

### Knowledge Graph Integration
All decisions and evaluations stored in knowledge graph for:
- Decision history tracking
- Learning from past decisions
- Audit trail and explainability

### Flow Integration
`multi-head-decider` flow step enables seamless integration into agent processing pipeline.

## Usage Examples

```clojure
;; Create orchestrator with standard heads
(def orchestrator (mh/create-orchestrator llm-provider knowledge-graph))

;; List available heads
(mh/list-heads orchestrator)

;; Make a decision
(def result (mh/make-decision orchestrator 
                              "Choosing tech stack"
                              ["Clojure" "Python" "Rust" "TypeScript"]))

;; Access results
(:decision result)      ; Final choice
(:consensus result)     ; Consensus level (0-1)
(:evaluations result)   ; All head evaluations

;; Add custom head
(def security-head (mh/->DecisionHead :security "Security Expert" 
                                      "security and privacy" 
                                      llm-provider knowledge-graph))
(def updated-orchestrator (mh/add-head orchestrator security-head))
```

## Architecture Integration

### Decision Flow
```
Context + Options → [Head1, Head2, Head3, ...] → Individual Evaluations
                    ↓
              Conflict Resolution → Final Decision
                    ↓
           Knowledge Graph Storage → Decision History
```

### Components
1. **Decision Heads**: Specialized evaluators with unique perspectives
2. **Orchestrator**: Coordinates heads and resolves conflicts
3. **Conflict Resolver**: Algorithm for reaching consensus
4. **History Tracker**: Stores decisions in knowledge graph
5. **Flow Step**: Integrates with core.async.flow architecture

## Key Features

### 1. Explainable Decisions
Each head provides reasoning for its choice, enabling transparent decision-making.

### 2. Confidence Weighting
Heads provide confidence scores (0-1) used in conflict resolution.

### 3. Consensus Measurement
Quantitative measure of agreement among heads (0 = no consensus, 1 = unanimous).

### 4. Extensible Architecture
Easy to add new head types with different specialties.

### 5. Historical Learning
All decisions stored for future reference and pattern analysis.

## Next Steps

1. **Advanced Conflict Resolution**: More sophisticated algorithms (Borda count, Condorcet methods)
2. **Head Specialization Training**: Fine-tune heads for specific domains
3. **Dynamic Head Selection**: Choose heads based on decision context
4. **Meta-Decision Making**: Heads that decide which other heads to consult
5. **Real-time Collaboration**: Heads that can debate and refine evaluations
6. **Performance Optimization**: Parallel evaluation of heads
7. **Visualization**: Decision process visualization tools

## Testing

Run the test suite:
```bash
clojure -M:test -m agent.test-multi-head
```

Or in REPL:
```clojure
(require '[agent.test-multi-head :refer :all])
(run-tests)
```

## Dependencies
- Inherits LLM and knowledge graph dependencies
- Uses core.async for flow integration
- Requires JSON parsing for LLM responses

## Performance Considerations
- Each head makes independent LLM call (parallelizable)
- Conflict resolution is O(n) for n heads
- Knowledge graph storage adds minimal overhead
- Suitable for complex decisions where multiple perspectives add value