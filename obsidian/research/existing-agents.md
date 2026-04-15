# Existing AI Agents Analysis

## Overview
Analysis of existing AI agent projects for architectural patterns and best practices: claw0, pi-mono, moltis, and ironclaw.

## Claw0 (shareAI-lab)

### Repository Analysis
- **Stars**: 2.1k, **Forks**: 237
- **Structure**: `sessions/`, `workspace/`, `.env.example`
- **Inferred focus**: Tool use and execution ("claw" metaphor)

### Architectural Insights
1. **Session Management**: `sessions/` directory suggests multi-session support
2. **Workspace Integration**: `workspace/` suggests file/system interaction
3. **Tool-Oriented Design**: Likely focused on external tool integration

### Potential Patterns
- **Multi-session agents**: Concurrent agent instances
- **Workspace abstraction**: File system interaction patterns
- **Tool execution**: External tool integration approaches

## Pi-mono (badlogic)

### Repository Analysis
- **Stars**: 35.6k, **Forks**: 4.1k, **Commits**: 3,535
- **Structure**: Monorepo with `packages/` directory
- **Context**: Related to Pi AI assistant from Inflection AI

### Architectural Insights
1. **Monorepo Architecture**: Clear separation of concerns across packages
2. **Scale Management**: Handling large, popular open-source project
3. **Development Practices**: Husky git hooks, extensive GitHub Actions

### Key Patterns
- **Package organization**: Logical separation of agent capabilities
- **Build system**: Unified build/test/deploy across components
- **Quality enforcement**: Automated code quality checks

## Moltis (moltis-org)

### Repository Analysis
- **Stars**: 2.6k, **Forks**: 302, **Commits**: 3,019
- **Philosophy**: "One binary — sandboxed, secure, yours."
- **Language**: Rust with zero `unsafe` code

### Architectural Insights
1. **Security-First Design**: Sandboxed execution, local-only keys
2. **Modular Crate Structure**: 46 crates with clear separation
3. **Agent Loop**: ~5K LoC (`runner.rs` + `model.rs`)

### Key Patterns
- **Sandbox isolation**: Docker/Apple Container for tool execution
- **Memory safety**: Ownership model, no unsafe code
- **Persistence**: SQLite + FTS + vector storage
- **Authentication**: Multiple methods (password, passkey, API keys, vault)

## IronClaw (nearai)

### Repository Analysis
- **Stars**: 11.8k, **Forks**: 1.3k, **Commits**: 1,048
- **Philosophy**: "Your AI assistant should work for you, not against you."
- **Focus**: Multi-agent coordination and security

### Architectural Insights
1. **Multi-Agent Coordination**: Orchestrator/worker pattern
2. **Security Architecture**: WASM sandbox, credential protection
3. **Self-Expanding**: Dynamic tool building, MCP protocol support

### Key Patterns
- **Orchestrator/worker**: Clear separation of coordination and execution
- **WASM sandboxing**: Isolated execution of untrusted code
- **Dynamic capabilities**: Runtime tool generation without restart
- **Hybrid search**: Full-text + vector with Reciprocal Rank Fusion

## Comparative Analysis

### Security Approaches
| Project | Security Focus | Key Features |
|---------|---------------|--------------|
| **Moltis** | Sandbox isolation | Docker containers, local keys, zero unsafe |
| **IronClaw** | Multi-layer defense | WASM sandbox, credential injection, allowlisting |
| **Pi-mono** | Scale security | Monorepo isolation, quality gates |
| **Claw0** | Tool security | Inferred from structure |

### Architecture Patterns
| Pattern | Best Example | Key Benefits |
|---------|--------------|--------------|
| **Monorepo** | Pi-mono | Clear separation, unified tooling |
| **Modular crates** | Moltis | Independent auditing, feature gating |
| **Orchestrator/worker** | IronClaw | Scalability, fault isolation |
| **Session-based** | Claw0 | Multi-instance support |

### Memory and Persistence
| Project | Memory Approach | Key Features |
|---------|----------------|--------------|
| **Moltis** | Hybrid storage | SQLite + FTS + vectors |
| **IronClaw** | Hybrid search | Full-text + vector with RRF |
| **Pi-mono** | Scale patterns | Large-scale data management |
| **Claw0** | Workspace focus | File system integration |

## Implementation Patterns for Clojure Agent

### 1. Security Patterns from Moltis
```clojure
; Sandboxed execution pattern
(defn execute-in-sandbox [command]
  (let [sandbox (create-sandbox {:type :docker :resources {:memory "512MB"}})]
    (execute-command sandbox command)))

; Local key management
(defn with-secure-keys [f]
  (let [keys (load-keys-from-secure-store)]
    (f keys)))
```

### 2. Multi-Agent Patterns from IronClaw
```clojure
; Orchestrator pattern
(defn orchestrator [tasks]
  (->> tasks
       (map #(future (worker %)))  ; Spawn workers
       (map deref)                 ; Wait for completion
       (reduce merge-results)))    ; Combine results

; WASM sandbox equivalent
(defn execute-in-isolation [code]
  (with-isolated-context {:type :sci :memory-limit "128MB"}
    (eval code)))
```

### 3. Monorepo Patterns from Pi-mono
```clojure
; Project structure
:agent-core/      ; Core reasoning engine
:agent-memory/    ; Memory systems
:agent-tools/     ; Tool execution
:agent-providers/ ; LLM integrations
:agent-api/       ; External interfaces

; Build configuration
{:deps {io.github.cognitect-labs/agent-core {:git/url "..." :sha "..."}
        io.github.cognitect-labs/agent-memory {:git/url "..." :sha "..."}}}
```

### 4. Session Patterns from Claw0
```clojure
; Session management
(defmulti handle-session :session-type)

(defmethod handle-session :conversation [session]
  (process-conversation session))

(defmethod handle-session :task [session]
  (execute-task session))

; Workspace integration
(defn with-workspace [workspace-path f]
  (let [workspace (load-workspace workspace-path)]
    (f workspace)))
```

## Integration Recommendations

### Phase 1: Foundation
1. **Adopt Moltis security patterns**: Sandboxing, local keys
2. **Use Pi-mono monorepo structure**: Clear component separation
3. **Implement basic session management**: From Claw0 patterns

### Phase 2: Advanced Features
1. **Add IronClaw multi-agent coordination**: Orchestrator/worker
2. **Implement hybrid memory**: From Moltis and IronClaw
3. **Add dynamic tool building**: From IronClaw

### Phase 3: Production Ready
1. **Scale patterns from Pi-mono**: Large project management
2. **Security hardening from Moltis**: Comprehensive protection
3. **Coordination from IronClaw**: Robust multi-agent support

## Key Takeaways

### 1. Security is Non-Negotiable
- Sandbox all tool execution
- Never expose credentials to untrusted code
- Implement defense in depth

### 2. Modularity Enables Maintenance
- Clear separation of concerns
- Independent component development
- Easy testing and replacement

### 3. Scalability Requires Planning
- Design for horizontal scaling
- Implement efficient coordination
- Manage resources carefully

### 4. User Control is Essential
- Local data storage
- Transparent operations
- User-configurable behavior

## References
- [Claw0 GitHub](https://github.com/shareAI-lab/claw0)
- [Pi-mono GitHub](https://github.com/badlogic/pi-mono)
- [Moltis GitHub](https://github.com/moltis-org/moltis)
- [IronClaw GitHub](https://github.com/nearai/ironclaw)