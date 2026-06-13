# Clojure AI Agent - Obsidian Vault

## Overview
This Obsidian vault contains documentation for the Clojure-based AI agent project. It organizes research, design decisions, implementation details, and best practices.

## Directory Structure

### 1. Research
- **core-async-flow.md**: Flow-based programming for agents
- **llm-integration.md**: LiteLLM-clj and custom library approaches
- **existing-agents.md**: Analysis of claw0, pi-mono, moltis, ironclaw
- **knowledge-graphs.md**: Oxford Semantic/RDFox, Noumenon, negation-as-failure

### 2. Architecture
- **design-principles.md**: Core architectural decisions
- **component-structure.md**: Module organization and interfaces
- **security-model.md**: Sandboxing, authentication, data protection
- **multi-agent-coordination.md**: Orchestrator/worker patterns, communication

### 3. Implementation
- **agent-loop.md**: Core reasoning engine implementation
- **memory-system.md**: Short/medium/long-term memory strategies
- **tool-execution.md**: Sandboxed tool execution framework
- **knowledge-graph-integration.md**: Graph-based reasoning implementation

### 4. Best Practices
- **clojure-patterns.md**: Idiomatic Clojure for AI agents
- **testing-strategies.md**: Unit, integration, and agent testing
- **performance-optimization.md**: Memory, CPU, and latency considerations
- **deployment-patterns.md**: Production deployment strategies

### 5. Decision Logs
- **decisions-2026.md**: Architectural and implementation decisions
- **issue-tracking.md**: Problems encountered and solutions
- **future-considerations.md**: Ideas for future enhancements
- **archive/**: Completed historical refactor/release reports

### 6. References
- **external-resources.md**: Links to documentation, papers, tools
- **glossary.md**: Terminology definitions
- **cheatsheets.md**: Quick reference guides

## How to Use This Vault

### For Research
1. Browse the Research section for analysis of existing systems
2. Check References for external resources
3. Use tags and links to connect related concepts

### For Development
1. Review Architecture section before implementing
2. Check Best Practices for coding guidelines
3. Consult Implementation section for detailed patterns

### For Decision Making
1. Review Decision Logs for past decisions
2. Document new decisions with rationale
3. Track issues and solutions in Issue Tracking

## Tags System
- `#research` - Research findings and analysis
- `#architecture` - Architectural decisions and patterns
- `#implementation` - Code implementation details
- `#best-practice` - Recommended approaches
- `#decision` - Documented decisions with rationale
- `#issue` - Problems and solutions
- `#future` - Ideas for future work
- `#reference` - External resources and links

## Linking Strategy
- Use `[[double-brackets]]` for internal links
- Create backlinks by mentioning other notes
- Use tags for cross-cutting concerns
- Maintain a graph view of connected concepts

## Maintenance
- Update this README when adding new sections
- Regularly review and prune outdated content
- Ensure all decisions are documented with context
- Keep external references up to date

## Getting Started
1. Open this vault in Obsidian
2. Review the directory structure
3. Start with Research section for background
4. Move to Architecture for design decisions
5. Implement using patterns in Implementation section
