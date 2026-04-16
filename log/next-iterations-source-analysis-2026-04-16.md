# Next Iterations Source Analysis
Date: 2026-04-16

## Scope

Source-level deep dive into:

1. `pi-mono`
2. `moltis`
3. exact `core.async.flow` usage in current rewritten runtime

Goal: turn inspiration into concrete implementation decisions for this repo.

## 1. `core.async.flow` usage verification

### Current repo reality

Direct code search shows `core.async.flow` appears only in:

- `flow.clj`
- `legacy_src/agent/multi_head.clj`
- `legacy_src/agent/kg_integration.clj`

It does **not** appear in active rewritten namespaces under `src/agent`.

It still appears in stale legacy-facing docs:

- `API.md`
- `PROJECT_SUMMARY.md`
- older Obsidian/research docs

### Practical conclusion

`core.async.flow` is currently:

- a historical design influence
- present in archived experiments
- absent from active runtime

So current decision remains:

- do **not** retrofit rewritten core around it now
- do consider a later orchestration-only spike
- do clean up stale docs that still describe it as active architecture

## 2. `pi-mono` source findings

Source roots inspected:

- `README.md`
- `packages/agent/*`
- `packages/mom/*`
- `packages/coding-agent/src/utils/tools-manager.ts`

### Architecture shape

`pi-mono` is a clean package split, not one giant agent package:

- `packages/ai`: provider abstraction + streaming + tool-call normalization
- `packages/agent`: generic stateful agent loop
- `packages/coding-agent`: productized coding-agent runtime and CLI
- `packages/mom`: Slack channel product on top of coding-agent pieces
- `packages/tui`: terminal UI primitives
- `packages/web-ui`: web UI components
- `packages/pods`: model deployment tooling

### Strong patterns worth copying

#### 1. Agent core is transcript-first and event-first

`packages/agent` centers on:

- explicit `AgentMessage[]`
- conversion to provider-native LLM messages only at boundary
- streamed lifecycle events for UI/runtime integration
- tool execution hooks before and after call
- queueing for steering and follow-up messages

This is strong architecture. It keeps:

- transcript model stable
- provider boundary narrow
- UI integration event-driven instead of polling

Implication for this repo:

- our rewritten runtime should move toward a first-class event stream, not only request/response API handlers
- orchestrator actions should emit durable event records with typed phases

#### 2. Tool execution policy is first-class

`pi-agent-core` supports:

- `parallel` vs `sequential` tool execution
- `beforeToolCall` blocking hook
- `afterToolCall` postprocessing hook
- assistant-message barrier before tool preflight

Implication for this repo:

- current tool registry is too thin
- next step should add execution policy/hook points/audit records, not only tool lookup + execute

#### 3. Channel adapter is separate product layer

`packages/mom` is not mixed into core agent package. It wraps:

- Slack transport
- per-channel queueing
- channel-specific workspace/memory/logging
- sandboxed command execution

Implication for this repo:

- Telegram/Discord/Slack adapters should live above core runtime
- core should expose stable session/channel/orchestrator APIs, not platform-specific logic

#### 4. Memory is layered, not monolithic

`mom` uses:

- `log.jsonl` as source-of-truth history
- `context.jsonl` as model-visible working context
- `MEMORY.md` for durable prompt memory
- greppable history outside active context window

This is simple and effective.

Implication for this repo:

- our current SQLite persistence is too transcript-only
- we need explicit distinction between:
  - immutable event/session log
  - prompt-visible working context
  - durable memory layer
  - searchable historical archive

#### 5. “Skills” are just filesystem-backed tools

`mom`/coding-agent skill model is pragmatic:

- discover tools from dirs
- expose formatted prompt help
- let agent create/modify them in workspace

Implication for this repo:

- personality/examples/channel work should converge on a simple skills directory contract before inventing a complex plugin system

### Weaknesses / tradeoffs in `pi-mono`

#### 1. Sandbox model in `mom` is narrow

`mom` sandbox is basically:

- host
- or docker container by name

That is pragmatic, but simpler than Moltis.

#### 2. Product surfaces are split across packages, but persistence story is less unified

The package split is clean, but the source reviewed suggests multiple layers compose transcript/state/workspace behavior outside a single durable runtime contract.

#### 3. Tool bootstrap in coding-agent is operationally convenient but supply-chain sensitive

`tools-manager.ts` auto-downloads binaries from GitHub releases. Great UX. More trust surface.

Implication for this repo:

- support tool bootstrap, but behind explicit policy and checksums

## 3. `moltis` source findings

Source roots inspected:

- `README.md`
- `Cargo.toml`
- `docs/src/channels.md`
- `docs/src/tool-registry.md`
- `docs/src/memory.md`
- `docs/src/sandbox.md`

### Architecture shape

`moltis` is a modular Rust workspace with explicit subsystem crates:

- core runtime
- gateway/auth
- tools
- sessions
- memory
- channels
- voice
- browser
- scheduling
- MCP/skills/hooks

This is more platform/system architecture than simple agent package design.

### Strong patterns worth copying

#### 1. Channels have explicit capability model

`docs/src/channels.md` defines per-channel:

- inbound mode
- public URL requirement
- capabilities like streaming/threads/voice/reactions/location/OTP

This is strong.

Implication for this repo:

- our pluggable channel layer should use a capability contract, not ad hoc booleans hidden inside adapters
- channel config should separate transport mode from feature support

#### 2. Tool registry tracks provenance

`docs/src/tool-registry.md` uses typed origin metadata:

- builtin
- MCP with server name

It also supports lazy registry mode via `tool_search` to avoid sending huge schema sets every turn.

Implication for this repo:

- our tool registry should add source metadata now
- later add lazy tool discovery when tool count grows

#### 3. Memory is configurable by surface and backend

`docs/src/memory.md` distinguishes:

- prompt memory
- search memory
- agent write policy
- user profile write policy
- session export
- backend choice

This is much more mature than “just add a graph DB”.

Implication for this repo:

- before implementing KG backend, define memory surfaces and write policies
- KG choice should follow memory model, not lead it

#### 4. Sandbox is architecture, not helper util

`docs/src/sandbox.md` defines backend priority and semantics:

- Apple Container
- Podman
- Docker
- WASM
- restricted host fallback

This is a real security architecture.

Implication for this repo:

- our future shell/fs/browser tools need a sandbox backend interface
- channel/runtime/tool work should assume per-session isolation policy exists

#### 5. One-binary/local-first pressure improves boundaries

Because Moltis optimizes for one binary and self-hostability, it draws clear lines around:

- optional features
- storage
- auth
- gateway
- channels
- memory

Implication for this repo:

- keep rewritten core small and optionalize advanced subsystems

### Weaknesses / tradeoffs in `moltis`

#### 1. High subsystem count raises implementation bar

The architecture is good, but it is expensive. Copying scope directly would derail this repo.

#### 2. Some docs are ahead of minimal implementation needs

Good for product direction; dangerous if copied wholesale into an early-stage rewrite.

#### 3. Rust-first security choices do not map 1:1 into a JVM/Clojure stack

We should copy interface and policy ideas, not assume identical operational mechanisms.

## 4. Direct comparison against rewritten runtime

### Current rewritten runtime strengths

- simple, understandable core
- OpenRouter/Ollama-first provider support
- SQLite sessions/completions
- minimal HTTP API
- tool registry exists
- orchestrator exists

### Main gaps versus `pi-mono`

1. No first-class event stream/lifecycle transcript model
2. No tool execution hooks/policies
3. No working-context compaction layer
4. No filesystem-backed skills model
5. No channel adapter product layer yet

### Main gaps versus `moltis`

1. No channel capability contract
2. No sandbox backend interface
3. No memory surface model
4. No tool provenance/lazy discovery
5. No operator/web UI yet
6. No hooks/observability policy around tool/channel/orchestrator events

## 5. Recommended order from here

### Before knowledge graph implementation

Define memory surfaces first:

1. session/event log
2. prompt memory
3. searchable long-term memory
4. optional graph/semantic memory

### Before pluggable channels

Define channel contract first:

- inbound mode
- capability flags
- normalized event shape
- delivery semantics
- auth/secret boundaries

### Before many more tools

Expand tool runtime first:

- source metadata
- execution hooks
- audit logging
- timeout/retry semantics
- sandbox policy attachment

### Before `core.async.flow`

Build one orchestration spike:

- multi-step DAG
- retry
- branching
- cancellation
- event visibility

If it is clearer than explicit state machine code, keep it. If not, drop it.

## 6. Concrete next implementation candidates

Best next coding targets now:

1. Add event log model to rewritten runtime
2. Add tool source metadata + before/after execution hooks
3. Add skills directory contract
4. Add channel adapter protocol with capability flags
5. Only then prototype memory/KG backend

## 7. Decision pressure

If forced to choose direction today:

- copy `pi-mono` for agent-loop/event/transcript patterns
- copy `moltis` for channel/tool/memory/sandbox interfaces
- do **not** copy `moltis` scope
- do **not** make KG first before memory-surface design
