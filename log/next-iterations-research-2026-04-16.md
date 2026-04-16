# Next Iterations Research
Date: 2026-04-16

## Scope

First research tranche for the new top-priority backlog:

1. Knowledge graph options shortlist
2. `core.async.flow` fit for rewritten runtime
3. Concrete capability snapshots for `pi-mono` and `moltis`

## 1. `core.async.flow` status in current codebase

### Active runtime usage

`core.async.flow` is **not used** in the rewritten active runtime.

Active rewritten namespaces are currently:

- `src/agent/core.clj`
- `src/agent/api.clj`
- `src/agent/config.clj`
- `src/agent/orchestrator.clj`
- `src/agent/persistence/sqlite.clj`
- `src/agent/tools/*`
- `src/agent/llm/*`

Current flow-related code exists only in:

- `flow.clj`
- `legacy_src/agent/multi_head.clj`
- `legacy_src/agent/kg_integration.clj`

### Why it is not used in rewritten path

Observed reasons:

1. Rewrite work prioritized a small, executable kernel first:
   config → LLM → persistence → API → tools → orchestrator.
2. Current runtime has mostly request/response composition and explicit state maps; this is simpler than introducing graph runtime semantics early.
3. `core.async.flow` is still relatively new. The official `core.async` repo notes the first alpha release of `core.async.flow` in release `1.9.808-alpha1` on 2025-04-28, explicitly saying APIs are subject to change.
4. Execution graph debugging, cancellation, and state inspection are already non-trivial; adding flow semantics too early would increase moving parts before the base runtime settles.

### Is it worth adding?

Current answer: **probably yes later, not yet as a foundation replacement**.

Best candidate uses:

- execution graphs for complex orchestrations
- tool pipelines with retries/branching
- long-running multi-step agent workflows
- channel/event routing when topology becomes non-trivial

Weak candidate uses:

- simple chat completions
- CRUD-ish API handlers
- direct synchronous tool calls
- current in-memory orchestrator actions

### Recommendation

Do **not** refactor rewritten core around `core.async.flow` yet.

Instead:

1. Keep the current explicit runtime for the base system.
2. Run a narrow spike later for orchestration DAGs only.
3. Adopt only if the spike improves:
   - debuggability
   - replayability
   - cancellation
   - observability
   - test isolation

## 2. Capability snapshot: `pi-mono`

Source used: official GitHub README/package list.

### What it clearly has

Packages listed in the public README:

- `pi-ai`: unified multi-provider LLM API
- `pi-agent-core`: agent runtime with tool calling and state management
- `pi-coding-agent`: interactive coding agent CLI
- `pi-mom`: Slack bot delegating to coding agent
- `pi-tui`: terminal UI library
- `pi-web-ui`: web UI components for AI chat
- `pi-pods`: CLI for managing vLLM deployments on GPU pods

### Useful takeaways

1. It separates provider API, runtime, coding agent, channel surface, TUI, web UI, and deployment tooling into clean packages.
2. It already proves value in having a dedicated channel adapter (`pi-mom` for Slack) instead of mixing channels into core runtime.
3. It treats UI as libraries/components, not only as a server concern.
4. It treats LLM deployment operations as a first-class concern (`pi-pods`), not just model invocation.

### What to analyze next in source

1. Tool registry and tool-calling semantics
2. Agent state persistence/memory boundaries
3. How Slack bot hands off into runtime
4. How TUI/web UI consume events
5. How coding agent runtime differs from generic agent core

## 3. Capability snapshot: `moltis`

Source used: official GitHub README/repo page.

### What it clearly has

Public README claims:

- one-binary local-first deployment
- sandboxed container execution
- multi-provider LLMs
- voice
- memory + cross-session recall
- automatic edit checkpoints
- scheduling
- Telegram / Discord / WhatsApp / Teams
- browser automation
- MCP servers
- SSH or node-backed remote execution
- managed deploy keys with host pinning
- live Settings → Tools inventory
- threat scanning for context files

Public repo structure shows large modular Rust workspace with `apps`, `crates`, `docs`, `examples`, `website`, etc.

### Useful takeaways

1. Channels are not optional afterthoughts; they are core product surfaces.
2. Security posture is part of architecture, not a later hardening task.
3. Tool inventory/discovery is visible in UI, which aligns with our rewritten `GET /v1/tools` direction.
4. “One binary” packaging and local-first deployment strongly influence design choices.

### What to analyze next in source

1. Channel adapter contracts
2. Sandbox boundary and tool execution model
3. Memory/cross-session recall storage architecture
4. Scheduling/job model
5. Provider/runtime separation
6. Web UI event model

## 4. Knowledge graph options shortlist

Goal constraints from backlog:

- easy to deploy
- preferably embeddable
- powerful enough for agent memory/reasoning
- well supported

### Tier A: best first-pass local candidates

#### Datahike

Why it fits:

- in-process on JVM
- Datalog model aligns with Clojure
- built-in history/time-travel story
- operationally light relative to external graph servers

Concerns:

- community/support smaller than mainstream graph DBs
- graph UX is Datalog/document-oriented, not property-graph/Cypher oriented

Best use:

- default local structured memory if we want JVM-native and deploy-simple

#### ArcadeDB

Why it fits:

- embeddable in JVM
- active embedded mode
- graph + document + vector + full-text in one engine
- supports Cypher/Gremlin/GraphQL/SQL

Concerns:

- more operational/semantic surface area than we need
- not Clojure-native, needs Java interop wrapper and discipline

Best use:

- strongest “single embedded engine for graph + vector + search” candidate

### Tier B: strong semantic/RDF options

#### RDF4J LMDB Store

Why it fits:

- embedded RDF stack
- explicit storage/inference layering via Sail
- JVM-native ecosystem

Concerns:

- LMDB store documented as experimental
- native extension/runtime dependencies add friction

Best use:

- semantic/RDF path when inference matters more than easiest embedding

#### Apache Jena TDB2

Why it fits:

- mature RDF/SPARQL stack
- transactional single-machine store
- strong ecosystem/documentation

Concerns:

- direct access should be single-JVM
- more semantic-web heavy than most agent-memory needs
- less pleasant for “embedded app database” ergonomics than Datahike

Best use:

- robust RDF/SPARQL option when standards compliance matters

### Tier C: useful but weaker fit for current constraints

#### Asami

Why it fits:

- Clojure-friendly
- very easy to embed

Concerns:

- official docs describe it as a simple in-memory graph DB without a rich feature set
- current support/power story looks weaker than top candidates

Best use:

- lightweight prototype, not current default choice

#### XTDB

Why it fits:

- strong immutable/history/audit model
- excellent for decision history and records
- in-process APIs exist in stable 1.x docs

Concerns:

- not graph-first
- should be judged as temporal system-of-record, not as primary graph engine

Best use:

- complementary memory/event/history store, not main KG

### Tier D: currently lower priority

#### Kuzu

Strengths:

- embedded property graph
- Cypher
- vector + full-text built in

Problem:

- official repo was archived on 2025-10-10

Conclusion:

- do not choose as primary foundation now

#### Memgraph

Strengths:

- capable graph DB
- strong Cypher story

Problem:

- server-first / Docker-first operational model
- weaker fit for “prefer embeddable”

Conclusion:

- not first default for this project

## 5. Current shortlist recommendation

If choosing today:

1. **Default local-first candidate**: `Datahike`
2. **Best ambitious embedded candidate**: `ArcadeDB`
3. **Best semantic standards candidate**: `Jena TDB2` or `RDF4J`
4. **Best complementary historical store**: `XTDB`

### Decision framing

Choose by desired primary memory model:

- Clojure/Datalog-centric structured memory → `Datahike`
- multi-model embedded graph + vector + search → `ArcadeDB`
- RDF/SPARQL/inference-heavy → `Jena TDB2` / `RDF4J`
- temporal/audit substrate rather than graph-first → `XTDB`

## 6. Recommended next concrete research tasks

1. Deep-read `pi-mono` source for `pi-agent-core`, `pi-mom`, `pi-web-ui`
2. Deep-read `moltis` crates for channels, tools, sessions, gateway
3. Run a local capability spike for `Datahike` and `ArcadeDB`
4. Decide whether KG should be:
   - graph-first memory
   - event/history store + graph projection
   - hybrid (temporal store + graph overlay)
5. Write a `core.async.flow` spike plan specifically for orchestration DAGs

## Sources

- `core.async` GitHub repo / release notes
- `pi-mono` GitHub README
- `moltis` GitHub README
- Datahike site
- Asami cljdoc
- XTDB docs
- RDF4J docs
- Apache Jena TDB2 docs
- Kuzu GitHub README
- ArcadeDB docs
