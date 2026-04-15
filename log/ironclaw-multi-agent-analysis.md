# IronClaw Multi-Agent Coordination Analysis
Date: 2026-04-15

## Overview
IronClaw is a secure personal AI assistant focused on multi-agent coordination and security. Built by nearai with 11.8k stars, 1.3k forks, and 1,048 commits.

## Core Philosophy
**"Your AI assistant should work for you, not against you."**

Key principles:
1. **Data sovereignty**: All data stored locally, encrypted, user-controlled
2. **Transparency**: Open source, auditable, no hidden telemetry
3. **Self-expanding**: Build new tools dynamically without vendor updates
4. **Defense in depth**: Multiple security layers against prompt injection/data exfiltration

## Multi-Agent Coordination Features

### 1. Orchestrator/Worker Pattern
- **Docker Sandbox**: Isolated container execution
- **Per-job tokens**: Unique authentication per execution
- **Orchestrator**: Coordinates multiple worker agents
- **Worker isolation**: Each job runs in separate container

### 2. Parallel Execution
- **Concurrent requests**: Handle multiple requests simultaneously
- **Isolated contexts**: Each agent operates in separate context
- **Resource management**: Controlled allocation across agents

### 3. Communication Channels
- **Multi-channel support**: REPL, HTTP webhooks, WASM channels (Telegram, Slack)
- **Web Gateway**: Browser UI with real-time SSE/WebSocket streaming
- **WASM channels**: Secure communication via WebAssembly

### 4. Coordination Mechanisms
- **Heartbeat System**: Proactive background execution for monitoring
- **Self-repair**: Automatic detection and recovery of stuck operations
- **Routines**: Cron schedules, event triggers, webhook handlers

## Security Architecture for Multi-Agent

### 1. WASM Sandbox
- **Untrusted tools**: Run in isolated WebAssembly containers
- **Capability-based permissions**: Fine-grained access control
- **Memory isolation**: Each agent in separate WASM instance

### 2. Credential Protection
- **Secrets never exposed**: Injected at host boundary
- **Leak detection**: Monitoring for credential exposure
- **Per-agent tokens**: Unique credentials per agent instance

### 3. Prompt Injection Defense
- **Pattern detection**: Identify injection attempts
- **Content sanitization**: Clean inputs before processing
- **Policy enforcement**: Strict execution policies

### 4. Network Security
- **Endpoint allowlisting**: HTTP requests only to approved hosts
- **Path restrictions**: Limited API access per agent
- **Traffic monitoring**: Log and audit all external calls

## Self-Expanding Capabilities

### Dynamic Tool Building
- **Describe what you need**: Natural language tool specification
- **WASM compilation**: Tools built as WebAssembly modules
- **Hot reload**: Add tools without restarting

### MCP Protocol Integration
- **Model Context Protocol**: Connect to external capability servers
- **Standardized interfaces**: Consistent tool integration
- **Extensible architecture**: Add new capabilities dynamically

### Plugin Architecture
- **WASM tools**: Drop-in new functionality
- **Channels**: Add communication methods
- **No restart required**: Dynamic loading

## Persistent Memory for Multi-Agent

### Hybrid Search
- **Full-text + vector search**: Using Reciprocal Rank Fusion
- **Cross-agent memory**: Shared knowledge base
- **Context retrieval**: Relevant information for each agent

### Workspace Management
- **Filesystem abstraction**: Flexible path-based storage
- **Shared workspace**: Common area for agent collaboration
- **Isolated storage**: Per-agent private storage

### Identity Management
- **Identity files**: Consistent personality across sessions
- **Agent preferences**: Individual configuration
- **Role definitions**: Specialized agent capabilities

## Technical Architecture

### Language & Dependencies
- **Language**: Rust (memory safety, performance)
- **Database**: PostgreSQL 15+ with pgvector extension
- **Authentication**: NEAR AI account via setup wizard

### Deployment Options
1. **Windows Installer**: MSI package
2. **PowerShell script**: Automated Windows install
3. **Shell script**: macOS, Linux, WSL
4. **Homebrew**: macOS/Linux package manager
5. **Source compilation**: Cargo build

## Multi-Agent Patterns for Clojure Implementation

### 1. Agent Coordination Patterns
```clojure
; Orchestrator pattern
(defn orchestrator [tasks]
  (->> tasks
       (map #(spawn-agent %))  ; Spawn worker agents
       (map await-result)      ; Wait for completion
       (reduce combine-results))) ; Combine results

; Worker isolation
(defn spawn-agent [task]
  (future
    (with-isolated-context task
      (execute-task task))))
```

### 2. Security Patterns
```clojure
; WASM sandbox equivalent
(defn sandboxed-execution [code]
  (with-sandbox {:type :wasm :memory-limit "128MB"}
    (execute-wasm code)))

; Credential injection
(defn secure-tool-call [tool credentials]
  (let [safe-creds (inject-at-boundary credentials)]
    (call-tool tool safe-creds)))
```

### 3. Communication Patterns
```clojure
; Multi-channel communication
(defmulti handle-message :channel-type)

(defmethod handle-message :telegram [msg]
  (process-telegram msg))

(defmethod handle-message :http [msg]
  (process-http msg))

; Real-time streaming
(defn stream-updates [channel]
  (go-loop []
    (when-let [update (<! channel)]
      (send-update update)
      (recur))))
```

### 4. Memory Sharing Patterns
```clojure
; Hybrid search
(defn search-memory [query]
  (let [text-results (full-text-search query)
        vector-results (vector-search query)]
    (reciprocal-rank-fusion text-results vector-results)))

; Shared workspace
(def shared-workspace
  (atom {:agents {}
         :memory {}
         :tasks {}}))
```

## Key Takeaways for Clojure Agent

### 1. Multi-Agent Coordination
- **Orchestrator/worker pattern**: Clear separation of concerns
- **Parallel execution**: Efficient resource utilization
- **Isolated contexts**: Prevent interference between agents

### 2. Security First
- **WASM sandboxing**: Consider ClojureScript/SCI for isolation
- **Credential protection**: Never expose secrets to untrusted code
- **Defense in depth**: Multiple security layers

### 3. Dynamic Capabilities
- **Self-expanding tools**: Runtime tool generation
- **MCP integration**: Standard protocol support
- **Hot reload**: Dynamic capability addition

### 4. Persistent Coordination
- **Shared memory**: Cross-agent knowledge base
- **Identity management**: Consistent agent personalities
- **Workspace abstraction**: Flexible storage for collaboration

## Implementation Recommendations

### Phase 1: Basic Coordination
1. Implement orchestrator/worker pattern
2. Add isolated execution contexts
3. Create basic communication channels

### Phase 2: Security Foundation
1. Implement sandboxed execution
2. Add credential protection
3. Deploy prompt injection defenses

### Phase 3: Advanced Features
1. Add dynamic tool building
2. Implement MCP protocol support
3. Create hybrid memory search

### Phase 4: Production Ready
1. Add self-repair mechanisms
2. Implement heartbeat monitoring
3. Create comprehensive testing

## References
- https://github.com/nearai/ironclaw
- README.md architecture and features
- Multi-agent coordination patterns
- Security architecture details