# Clojure AI Agent Review Report

Date: 2026-04-15

## TL;DR

This repo is not a finished agent system. It is a mixed artifact: part research vault, part design sketch, part partially-written implementation, part aspirational documentation. The strongest part is the research/documentation corpus. The weakest part is executable software quality.

Current state:

- Architecture intent: strong
- Research breadth: good
- Code cohesion: poor
- Buildability: broken
- Test reliability: poor
- Production readiness: no

Confidence: 0.93

Key caveats:

- I reviewed source files, scripts, docs, and referenced upstream projects.
- I attempted to validate the local build; `clojure` fails before tests due to broken `deps.edn`.
- Some external sources were easier to verify than others. Noumenon specifically was not easily fetchable via current web tooling, so its discussion below is more limited than the GitHub-based inspirations.

## Method

Reviewed:

- `README.md`
- `deps.edn`
- core source under `src/agent/**`
- tests under `test/agent/**`
- infra: `Dockerfile`, `docker-compose.yml`, `k8s/deployment.yaml`, `.github/workflows/ci-cd.yml`
- local research/docs: `obsidian/**`, `log/**`, `PROJECT_SUMMARY.md`, `TODO.md`, `API.md`, `USAGE.md`
- upstream materials referenced from `README.md`

Verified upstream sources:

- `core.async.flow` guide: https://github.com/clojure/core.async/blob/master/doc/flow-guide.md
- `litellm-clj`: https://github.com/unravel-team/litellm-clj
- `claw0`: https://github.com/shareAI-lab/claw0
- `pi-mono`: https://github.com/badlogic/pi-mono
- `moltis`: https://github.com/moltis-org/moltis
- `ironclaw`: https://github.com/nearai/ironclaw
- RDFox reasoning docs: https://docs.oxfordsemantic.tech/5.7/reasoning.html#negation-as-failure

## Executive Assessment

The repo has a clear north star:

- flow-based orchestration
- provider-agnostic LLM layer
- knowledge-graph-augmented reasoning
- multi-head / collegial decision making
- eventual multi-agent orchestration
- production concerns: monitoring, security, deployment

But implementation quality is far behind stated status.

The biggest structural problem is split-brain architecture:

1. Old flat namespaces:
   - `src/agent/llm.clj`
   - `src/agent/knowledge_graph.clj`
   - `src/agent/kg_integration.clj`
   - `src/agent/multi_head.clj`

2. Newer protocol-oriented namespaces:
   - `src/agent/llm/core.clj`
   - `src/agent/llm/providers/*.clj`
   - `src/agent/knowledge_graph/core.clj`
   - `src/agent/knowledge_graph/backends/*.clj`
   - `src/agent/tools/**`
   - `src/agent/distributed/**`

These two worlds are not cleanly integrated. Docs often describe the newer architecture while tests and examples still depend on older modules, and some files mix both styles.

## What Is Good

### 1. Problem framing is strong

`README.md` sets sensible target areas: core, llm, memory, tools, config, api. That is the right decomposition for an agent runtime.

### 2. Research corpus is useful

`obsidian/` and `log/` are probably the repo’s highest-value asset right now. They capture:

- relevant inspiration systems
- desired patterns
- phased thinking
- design rationale

That material can support a rewrite or cleanup.

### 3. Protocol-first direction is correct

The newer `*.core` namespaces move toward:

- clear contracts
- swappable providers/backends
- capability discovery
- config/health abstractions

That is the correct long-term shape for a serious agent framework.

### 4. Ambition is aligned with real agent systems

The target feature set is not random. It maps to what real systems actually need:

- sessions
- tools
- channels
- persistence
- observability
- safety boundaries
- orchestrator/worker separation

## Actual Architecture In Repo

### High-level shape

What exists today is effectively 4 parallel layers:

1. Research layer
   - `obsidian/`
   - `log/`

2. Legacy prototype layer
   - `src/agent/llm.clj`
   - `src/agent/knowledge_graph.clj`
   - `src/agent/kg_integration.clj`
   - `src/agent/multi_head.clj`
   - `flow.clj`

3. Newer abstraction layer
   - `src/agent/llm/core.clj`
   - `src/agent/llm/providers/*`
   - `src/agent/knowledge_graph/core.clj`
   - `src/agent/tools/*`
   - `src/agent/distributed/*`
   - `src/agent/monitoring.clj`
   - `src/agent/performance.clj`
   - `src/agent/security.clj`

4. Documentation / deployment theater
   - `API.md`
   - `USAGE.md`
   - `PROJECT_SUMMARY.md`
   - `build.sh`
   - Docker / Compose / K8s / CI files

### Intended runtime

The intended runtime seems to be:

- user prompt enters a `core.async.flow` pipeline
- LLM provider generates output
- KG extraction stores facts
- multi-head decision system can arbitrate choices
- tools + distributed agents extend capabilities
- monitoring/security/performance wrap the system

### Actual runtime

There is no trustworthy end-to-end runtime.

Reasons:

- entrypoint namespace/path mismatch
- broken dependency file
- major compile-time defects
- tests not grounded in running code
- deployment files reference missing assets

## Critical Flaws

### 1. Build is broken at classpath stage

`deps.edn` has an extra closing brace at line 18. Local verification failed with:

- `Error building classpath`
- `NullPointerException` from tools.deps while reading deps

File:

- `deps.edn:18`

Impact:

- nothing compiles
- tests cannot run
- CI cannot pass
- Docker build cannot pass

### 2. Declared entrypoint does not exist at expected path

`flow.clj` declares `(ns agent.core ...)`, but the file is not at `src/agent/core.clj`.

Files:

- `flow.clj:1`
- `build.sh:43`
- `Dockerfile:18`
- `.github/workflows/ci-cd.yml:64`

Impact:

- compile/package assumptions do not match filesystem reality
- any AOT or namespace loading expecting `agent.core` will fail

### 3. Build/test scripts reference nonexistent pieces

Problems:

- `build.sh` calls nonexistent test runner namespace `agent.test-runner`
- `build.sh` and CI use nonexistent `:uberjar` alias
- `test_runner.sh` hard-codes `/home/example/projects/clj-agent`

Files:

- `build.sh:49`
- `build.sh:61`
- `test_runner.sh:8`
- `.github/workflows/ci-cd.yml:54`
- `.github/workflows/ci-cd.yml:64`

Impact:

- even after fixing `deps.edn`, automation still fails

### 4. Namespace split-brain / duplicated implementations

`src/agent/multi_head.clj` is 476 lines and contains effectively duplicated implementations of the same system. The file defines protocols/records once, then repeats them again later.

Files:

- `src/agent/multi_head.clj:14`
- `src/agent/multi_head.clj:247`

Impact:

- high chance of stale half-edited copies
- impossible to trust which version docs/tests mean
- future changes will fork behavior again

### 5. Legacy and new abstractions are not reconciled

Examples:

- old `agent.llm` vs new `agent.llm.core` + providers
- old `agent.knowledge-graph` vs new `agent.knowledge-graph.core`
- tests/mock fixtures mostly target old namespaces

Impact:

- architecture docs and code drift constantly
- contributors cannot tell canonical API

## Code-Level Defects

### Multi-head decision module

Major issues:

- unsafe/wrong JSON parsing with `clojure.edn/read-string` for model output: `src/agent/multi_head.clj:49-68`
- later duplicate version uses `clojure.data.json/read-str` but `clojure.data.json` is never required and not in deps: `src/agent/multi_head.clj` second half
- protocol functions called with wrong arity inside record methods:
  - `resolve-conflict` call missing `this`: `src/agent/multi_head.clj:106`
  - `consensus-level` call missing `this`: `src/agent/multi_head.clj:109`
- eager provider creation with empty config guarantees failure unless env is perfect:
  - `src/agent/multi_head.clj:194-196`
- choice resolution explodes on empty counts:
  - `apply max` on empty values at `src/agent/multi_head.clj:116`

Assessment:

- good concept
- not executable as written
- not safe for LLM-structured output

### Knowledge graph module

Major issues:

- Datalog queries use literal symbols instead of parameters:
  - `find-entities`: `src/agent/knowledge_graph.clj:36-40`
  - `get-facts`: `src/agent/knowledge_graph.clj:42-46`
- `infer` calls undefined `apply-rule`: `src/agent/knowledge_graph.clj:54`
- helper functions call protocol `query` with unsupported extra args:
  - `query-pattern`: `src/agent/knowledge_graph.clj:77-83`
  - `find-related`: `src/agent/knowledge_graph.clj:94-99`

Assessment:

- present implementation is closer to pseudocode than reliable KG logic

### KG integration module

Major issues:

- file contains stray top-level example code not wrapped in `comment`, including references to undefined `kg-flow`
- this code will execute/parse as real top-level forms

File:

- `src/agent/kg_integration.clj:139-164`

Impact:

- namespace load is likely broken

### Security module

Major issues:

- undefined symbol `context` inside permission check:
  - `src/agent/security.clj:89`
- fake sandbox still calls `eval`:
  - `src/agent/security.clj:143-168`
- token validation reads from nonexistent `:tokens` storage:
  - `src/agent/security.clj:124-128`
- `SecurityManager` record defines ad hoc methods without a declared protocol/interface:
  - `src/agent/security.clj:202-231`

Assessment:

- security section is the most dangerous kind of incomplete: it looks like security, but the core execution primitive is still `eval`

### Distributed coordination module

Major issues:

- imports undeclared deps:
  - `clojure.tools.logging`
  - `manifold.stream`
  - `manifold.deferred`
- broadcast message shape is inconsistent:
  - enqueue: `src/agent/distributed/coordinator.clj:158`
  - dequeue/destructure: `src/agent/distributed/coordinator.clj:225-238`

Assessment:

- more sketch than functioning coordinator

### Monitoring/performance/tooling modules

Problems:

- import undeclared dependencies, e.g. Prometheus Java client and `clojure.tools.logging`
- these namespaces increase breadth but not working depth

Files:

- `src/agent/monitoring.clj:17-23`
- `src/agent/performance.clj:18`

### LLM provider layer

Mixed quality:

- newer provider abstraction is directionally better
- but old `agent.llm` still coexists
- default models are outdated in places
- OpenAI/Anthropic streaming implementations are partial/simplified
- missing explicit OpenRouter/Ollama/vLLM support despite README priority

Files:

- `src/agent/llm.clj`
- `src/agent/llm/providers/openai.clj`
- `src/agent/llm/providers/anthropic.clj`

## Test Quality Review

The repo has many test files, but test count here is misleading.

### Positive

- tests show desired behavior surface
- mocks and scenarios document intended usage
- there is some attempt at provider-level isolation

### Negative

- no reliable green test path
- test runner namespace does not exist
- some tests assert nonexistent APIs
- some mocks do not match real implementations

Examples:

- `test/agent/llm/core_test.clj` expects nonexistent `validate-messages?`, `ProviderError`, `ConfigurationError`, `ConnectionError`: `test/agent/llm/core_test.clj:45-52`
- mock LLM returns maps, while real old provider returns strings:
  - mock: `test/agent/test_framework.clj:17-20`
  - real: `src/agent/llm.clj:34`
- integration tests contain comments like “mock doesn't actually store”: `test/agent/integration_tests.clj:32`

Conclusion:

- tests currently document aspiration, not correctness

## Documentation Reliability

This is a major issue.

`PROJECT_SUMMARY.md` and `TODO.md` claim:

- Phase 3 complete
- project ready for use
- core features fully implemented
- production deployment possible

But source reality contradicts that.

Files:

- `PROJECT_SUMMARY.md`
- `TODO.md`

This matters because trust in documentation is currently lower than trust in code. That is dangerous for any future contributor.

## Obviously Missing Parts

Relative to `README.md:42-55`, these are missing or not real yet:

### 1. Real configuration system

Missing:

- config loading
- env overlays
- secrets strategy
- provider routing config
- system composition config

Reality:

- docs mention config
- code does not implement a coherent config subsystem

### 2. Real API service

Deps include Ring/Reitit, docs mention API, infra expects `/health`, but there is no actual HTTP service composition or stable entrypoint.

### 3. Real persistence layer

README asks for SQLite-backed extensive logging. Actual code has:

- no SQLite integration
- no migrations
- no log schema
- no durable session store

### 4. Skills / standard agent file support

README explicitly asks for support for `skills`, `agents.md`, `sould.md` etc.

Reality:

- no parser
- no loader
- no runtime behavior around these files

### 5. Channel integrations

README asks for Telegram/Discord/etc.

Reality:

- no channel implementation
- only documentation/inspiration references

### 6. Subagent orchestration

README asks for subagent calling and continued communication.

Reality:

- distributed modules are partial sketches
- no working spawn/lifecycle/message protocol

### 7. Memory worthy of “knowledge graph augmented reasoning”

Current KG path is:

- regex keyword extraction
- simplistic triples
- pseudo-inference

That is not enough to justify the repo’s reasoning claims.

## Infra / Deployment Review

### Dockerfile

Problems:

- copies `resources` and `config`, but neither exists locally
- builds `agent.core` via nonexistent/invalid packaging setup

Files:

- `Dockerfile:10-18`
- `Dockerfile:37-39`

### docker-compose

Problems:

- references missing directories/files: `config`, `logs`, `monitoring`, `nginx`, `init-db.sql`
- deploys PostgreSQL/Redis stack disconnected from actual codebase
- health checks assume HTTP app that does not exist

Files:

- `docker-compose.yml:21-23`
- `docker-compose.yml:44`
- `docker-compose.yml:79`
- `docker-compose.yml:102-103`
- `docker-compose.yml:121`
- `docker-compose.yml:132`
- `docker-compose.yml:145-146`

### Kubernetes

Problems:

- `build.sh` expects separate manifest files that are absent
- `k8s/deployment.yaml` is monolithic and uses generic env names like `NODE_ENV`
- secret/config values look placeholder-level

Files:

- `build.sh:148-152`
- `k8s/deployment.yaml`

### CI/CD

Problems:

- CI encodes broken build assumptions
- will fail on `deps.edn`, missing runner, missing uberjar alias

Files:

- `.github/workflows/ci-cd.yml:50-64`

## Review Against Inspirations

## 1. `core.async.flow`

Verified guide says flow definitions center on `:procs`, `:conns`, and lifecycle operations like `create-flow`, `start`, `pause`, `resume`, `inject`. It also explicitly recommends passing step fns by var for reloadability. Source: `core.async.flow` guide lines 301-337.

Where this repo aligns:

- it uses `flow/map->step`
- it models multi-step pipelines
- it uses vars in some places

Where it misses:

- pipelines mostly wrap synchronous business logic rather than building true asynchronous process topology
- no supervision strategy
- no robust use of report/error channels
- no clear composition root for flows

Verdict:

- inspiration chosen well
- adoption shallow

## 2. `litellm-clj`

Verified repo describes itself as a unified Clojure interface for multiple LLM providers, with streaming via `core.async`, runtime router support, and support for OpenAI, Anthropic, OpenRouter, Ollama, Mistral, etc. Source: `litellm-clj` README lines 275-318 and 372-405.

What this repo should have taken:

- one canonical provider API
- runtime routing/config registry
- first-class OpenAI-compatible provider support
- OpenRouter/Ollama/vLLM support from day 1

Current repo instead:

- duplicates provider abstraction
- hardcodes providers in multiple places
- has no router/config story
- does not actually deliver the provider breadth requested in README

Verdict:

- strong missed opportunity

## 3. `claw0`

Verified repo is a teaching repo, not a production framework. It incrementally builds an agent gateway from loop → tools → sessions → channels → gateway → intelligence → heartbeat → delivery → resilience → concurrency. Source: `claw0` README lines 240-330.

Important lesson from `claw0`:

- keep architecture layered and pedagogically incremental
- sessions, channels, delivery, and concurrency are separate concerns
- “soul, memory, skills, prompt assembly” are part of intelligence, not afterthoughts

Current repo misses that discipline:

- it jumps into many advanced modules before the base runtime is solid
- there is no clear incremental path from single-agent loop to gateway

Verdict:

- README cites `claw0`, but implementation did not inherit its stepwise discipline

## 4. `pi-mono`

As of 2026-04-15, `pi-mono` is a large monorepo for an agent toolkit: coding agent CLI, unified LLM API, TUI/web UI libraries, Slack bot, and vLLM tooling. It exposes clear package boundaries and strong repo hygiene. Source: `pi-mono` repo lines 290-327.

Important lessons:

- explicit package boundaries
- unified dev/build workflow
- one monorepo, many focused modules

Current repo is the opposite:

- boundaries are implied, not enforced
- no canonical package/module ownership
- duplicate namespace generations coexist

Verdict:

- good inspiration at repo-structure level
- not yet internalized

## 5. `moltis`

Verified `moltis` markets “one binary — sandboxed, secure, yours”, with sandboxed container execution, memory, channels like Telegram/Discord, MCP support, and a relatively compact agent loop within a much larger modular Rust codebase. Source: `moltis` repo lines 389-426.

Important lessons:

- security is a real boundary, not a comment
- infrastructure claims should be backed by working internals
- channels, memory, deploy, and tools belong in one coherent runtime

Current repo misses this badly:

- “sandbox” still uses `eval`
- infra files are mostly disconnected from app reality
- memory/persistence not implemented

Verdict:

- strongest contrast case in this review

## 6. `ironclaw`

Verified `ironclaw` emphasizes:

- local encrypted data
- WASM sandbox
- credential injection
- multi-channel operation
- Docker sandbox / orchestrator-worker split
- dynamic tool building
- MCP support
- hybrid search memory

Source: `ironclaw` README lines 10-22.

Important lessons:

- orchestrator/worker needs concrete boundaries
- tool security matters as much as tool availability
- hybrid memory and channels are core platform features

Current repo:

- has sketches of coordination
- no actual secure tool runtime
- no hybrid memory
- no channels

Verdict:

- inspiration is correct
- implementation is still pre-foundation

## 7. RDFox / knowledge graph reasoning

Verified RDFox docs highlight that negation-as-failure is nonmonotonic and requires care because new facts can invalidate previous deductions. Source: RDFox docs lines 152-153 and later stratification discussion.

Why this matters:

- KG reasoning is not “store some triples and regex facts”
- once you claim inference, you need semantics, rule management, and invalidation strategy

Current repo:

- does not have a reasoning model rigorous enough to claim serious KG augmentation

Verdict:

- research direction good
- implementation not close

## Architecture Recommendations

### Recommendation 1: Pick one architecture and delete the other

Do this first.

Keep only one of:

- legacy flat namespaces
- newer protocol/core/provider split

My recommendation:

- keep newer protocol-oriented structure
- delete or archive old flat modules after porting any useful code

### Recommendation 2: Create one real composition root

Add:

- `src/agent/core.clj`

It should own:

- config loading
- provider registry
- memory/KG backend selection
- tool registry
- flow construction
- HTTP server start/stop

### Recommendation 3: Build one vertical slice before breadth

Implement only this first:

1. config
2. LLM provider routing
3. session persistence
4. one tool
5. one HTTP API
6. one flow
7. one end-to-end test

Until that slice is green, do not add more subsystems.

### Recommendation 4: Replace “security theater” with real boundaries

Immediately remove or quarantine:

- any `eval`-based sandbox claim

Then choose one real strategy:

- SCI with strict capability limits for Clojure eval use cases
- external subprocess/container sandbox for tool execution
- WASM if that becomes a true platform goal

### Recommendation 5: Add durable persistence early

Based on README goals, best next step is SQLite for:

- sessions
- message history
- tool audit logs
- checkpoints
- task queue

Then add graph/vector strategy separately. Do not force KG to solve all persistence problems.

### Recommendation 6: Treat KG as advanced memory, not base memory

Recommended memory stack:

- base: SQLite relational event/session store
- retrieval: FTS and maybe embeddings
- advanced: KG for entity/relation memory
- reasoning: explicit rule layer, separate from storage

### Recommendation 7: Rewrite tests around contracts, not fantasies

Need:

- compile/load smoke tests
- provider contract tests
- one real integration path
- one end-to-end “prompt -> tool -> persistence -> response” test

Delete or rewrite tests that assert nonexistent APIs.

## Suggested Rewrite Order

### Phase 0: Make repo honest

- fix `deps.edn`
- fix entrypoint path
- remove false “ready for use” claims from docs
- mark incomplete modules explicitly

### Phase 1: Stabilize core

- define config
- define provider registry
- define session store
- define tool registry
- define single flow composition root

### Phase 2: Deliver minimal usable agent

- one OpenAI-compatible provider
- one mock provider
- one HTTP tool
- one CLI or HTTP API
- SQLite-backed history/logging

### Phase 3: Add real agent features

- session compaction
- streaming
- structured outputs
- skills / `AGENTS.md` / system file loading
- one orchestrator + one worker model

### Phase 4: Add advanced research features

- KG backend
- multi-head decisions
- channel integrations
- distributed coordination

## What To Keep

Keep:

- `obsidian/`
- `log/`
- newer protocol-oriented module direction
- examples as idea bank, not as proof of correctness

Potentially keep after cleanup:

- `src/agent/llm/core.clj`
- `src/agent/llm/providers/*`
- `src/agent/tools/core.clj`

Archive or rewrite:

- `src/agent/llm.clj`
- `src/agent/knowledge_graph.clj`
- `src/agent/kg_integration.clj`
- `src/agent/multi_head.clj`
- `src/agent/security.clj`
- infra files that reference missing assets

## Final Verdict

This project is promising as a research and architecture notebook, not as a working agent runtime.

Best characterization:

- documentation-rich prototype
- implementation-poor
- architecture direction mostly right
- execution discipline weak

If cleaned up with ruthless scope control, it could become a solid Clojure agent framework. But that requires treating current code as a draft, not as “Phase 3 complete”.

## Sources

Local:

- `README.md`
- `deps.edn`
- `src/agent/**`
- `test/agent/**`
- `Dockerfile`
- `docker-compose.yml`
- `k8s/deployment.yaml`
- `.github/workflows/ci-cd.yml`

External:

- https://github.com/clojure/core.async/blob/master/doc/flow-guide.md
- https://github.com/unravel-team/litellm-clj
- https://github.com/shareAI-lab/claw0
- https://github.com/badlogic/pi-mono
- https://github.com/moltis-org/moltis
- https://github.com/nearai/ironclaw
- https://docs.oxfordsemantic.tech/5.7/reasoning.html#negation-as-failure
