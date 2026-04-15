# Moltis Architecture Analysis
Date: 2026-04-15

## Overview
Moltis is a secure persistent personal agent server written in Rust. Key philosophy: "One binary — sandboxed, secure, yours."

## Core Architecture Principles

### Security-First Design
1. **Sandboxed execution**: All commands run in Docker/Apple Container sandboxes
2. **No keys leave machine**: Local-only key management
3. **Zero `unsafe` code**: Memory safety guaranteed (except opt-in FFI)
4. **Supply chain security**: No plugin marketplace, built-in features only

### Technical Architecture

#### Language & Runtime
- **Language**: Rust (ownership model, zero-cost abstractions)
- **Binary size**: 44 MB single binary
- **No runtime dependencies**: No Node.js, npm, or external runtime

#### Modular Crate Structure (46 crates total)

**Core Crates (always compiled):**
1. `moltis` (cli) - 4.0K LoC - Entry point, CLI commands
2. `moltis-agents` - 9.6K LoC - Agent loop, streaming, prompt assembly
3. `moltis-providers` - 17.6K LoC - LLM provider implementations
4. `moltis-gateway` - 36.1K LoC - HTTP/WS server, RPC, auth
5. `moltis-chat` - 11.5K LoC - Chat engine, agent orchestration
6. `moltis-tools` - 21.9K LoC - Tool execution, sandbox
7. `moltis-config` - 7.0K LoC - Configuration, validation
8. `moltis-sessions` - 3.8K LoC - Session persistence
9. `moltis-plugins` - 1.9K LoC - Hook dispatch, plugin formats
10. `moltis-service-traits` - 1.3K LoC - Shared service interfaces

**Agent Loop Architecture**
- **Core loop**: ~5K LoC (`runner.rs` + `model.rs`)
- **Total core**: ~196K LoC across 46 modular crates
- **Testing**: 3,100+ tests

## Key Design Patterns

### 1. Sandboxed Tool Execution
- All tools run in isolated containers
- Docker/Apple Container support
- Host machine protection

### 2. Modular Provider System
- Multiple LLM provider implementations
- Unified interface for different providers
- Extensible architecture

### 3. Memory & Persistence
- SQLite + FTS + vector storage
- Cross-session recall
- Automatic edit checkpoints

### 4. Authentication & Security
- Multiple auth methods: Password, Passkey, API keys, Vault
- Managed deploy keys with host pinning
- Context-file threat scanning

## Feature Comparison

### Moltis vs Other Agents
| Feature | Moltis | Others |
|---------|--------|--------|
| Language | Rust | TypeScript/Go |
| Agent loop | ~5K LoC | 430K-3.4K LoC |
| Sandbox | Docker + Apple Container | App-level/Docker |
| Memory safety | Ownership, zero `unsafe` | GC/Ownership |
| Voice I/O | Built-in (15+ providers) | Plugin/none |
| MCP support | Yes (stdio + HTTP/SSE) | Limited |
| Skills | Yes (+ OpenClaw Store) | Varies |

## Architectural Insights for Clojure Agent

### 1. Security Patterns
- **Sandbox isolation**: Critical for tool execution safety
- **Local-only keys**: User data protection
- **Memory safety**: Clojure's immutable data structures provide similar benefits

### 2. Modular Design
- **Clear separation**: Agent loop, providers, tools, chat engine
- **Shared interfaces**: Service traits for loose coupling
- **Feature gating**: Optional components via feature flags

### 3. Persistence Strategy
- **SQLite**: Lightweight, embedded database
- **Full-text search**: For memory retrieval
- **Vector storage**: For semantic search

### 4. Tool Execution
- **Sandboxed containers**: Safety first approach
- **Unified tool interface**: Consistent API across tools
- **Remote execution**: SSH/node-backed options

## Implementation Considerations for Clojure

### Advantages of Clojure
1. **Immutability**: Natural memory safety
2. **REPL-driven development**: Faster iteration
3. **Java ecosystem**: Access to mature libraries
4. **core.async**: Built-in concurrency primitives

### Challenges to Address
1. **Sandboxing**: Need container execution framework
2. **Binary distribution**: Single JAR vs native image
3. **Memory footprint**: JVM vs Rust binary size
4. **Startup time**: JVM warmup considerations

## Recommended Patterns for Clojure Agent

### 1. Component Architecture
```clojure
; Similar to Moltis crate structure
:agent-loop    ; Core reasoning engine
:providers     ; LLM integrations  
:tools         ; Sandboxed execution
:memory        ; SQLite + vector storage
:gateway       ; HTTP/WebSocket API
:config        ; Configuration management
```

### 2. Security Model
- Use Docker/podman for sandboxing
- Local key storage with encryption
- Immutable data structures for safety
- Threat scanning for context files

### 3. Persistence Layer
- SQLite with HugSQL/next.jdbc
- Full-text search via SQLite FTS5
- Vector embeddings for semantic memory
- Automatic checkpoint/rollback

### 4. Tool Execution
- Container-based sandboxing
- Unified tool interface
- Resource limits and timeouts
- Remote execution support

## Key Takeaways

1. **Security is foundational**: Sandbox everything, protect keys
2. **Modularity enables maintenance**: Clear separation of concerns
3. **Persistence is multi-faceted**: Need FTS, vectors, and relational
4. **Single binary is valuable**: Consider GraalVM native image
5. **Testing is critical**: 3,100+ tests for confidence

## References
- https://github.com/moltis-org/moltis
- README.md architecture section
- Crate structure and LoC metrics
- Security and design principles