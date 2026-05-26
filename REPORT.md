# IRIS Codebase Review: Remaining Issues

Date: 2026-05-26

Scope: current `/Users/skammer/Code/tmp/clj-agent` after latest remediation pass.

Verification:

- Focused changed tests pass:
  `clojure -M:test -e "(require '[clojure.test :as t] 'agent.config-test 'agent.system-test 'agent.api-test 'agent.memory.core-test) (t/run-tests 'agent.config-test 'agent.system-test 'agent.api-test 'agent.memory.core-test)"`
- Result: 52 tests, 462 assertions, 0 failures, 0 errors.
- Full suite passes:
  `clojure -M:test -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests)"`
- Result: 376 tests, 1518 assertions, 0 failures, 0 errors.

## Executive Verdict

Security/control-plane bugs are fixed. Public process-local orchestrator APIs are now disabled by default. Latest remediation:

- chat state moved into a system-owned `chat-service`

Remaining support cost is architectural:

- runtime migration still has compatibility adapters and legacy event shapes
- `agent.system`, `agent.chat`, `agent.ui`, `agent.config`, and `agent.orchestrator` are still too large
- streaming/SSE still relies on repeated raw future/catch/close mechanics
- config loading still mutates disk and normalizes legacy shapes in live path
- memory graph failures are now visible, but graph reconciliation/noisy Datahike behavior remains unfinished

Weighted confidence: 0.88.

## Priority Findings

### 1. Chat runtime state now system-owned

Status: Fixed 2026-05-26.

Evidence after fix:

- `src/agent/chat.clj` creates explicit `chat-service` state for streaming, session runtimes, loop workers, and manager lock.
- `src/agent/system.clj` injects `:chat-service`, reports chat health, and stops/reloads chat state through system lifecycle.
- UI/API/Telegram now pass `system` into chat state/cancel/streaming access instead of one-arg namespace-global calls.

Remaining caveat:

- Chat still starts queue/loop work with raw `future`; broader runtime supervision remains part of later runtime cleanup.

Confidence: 0.9.

### 2. Runtime migration still has compatibility adapters and legacy event shapes

Evidence:

- `README.md` still says compatibility adapters live in `src`.
- `src/agent/chat.clj` still requires `agent.loop`.
- `src/agent/system.clj`, `src/agent/ui.clj`, and API handlers still require `agent.orchestrator`.
- `src/agent/api/streaming.clj` still says legacy stream writers will be deleted later.
- `src/agent/runtime/schema.clj` still keeps legacy event mapping.
- `src/agent/api/serializers.clj` still maps legacy events through `legacy-event->canonical`.

Reasoning:

There is no single runtime contract yet. New behavior still crosses old chat/loop/orchestrator/event adapters, so every runtime change risks fixing the wrong layer.

Fix direction:

- Declare `agent.runtime.*` event/directive schema as only internal contract.
- Move compatibility adapters into one boundary namespace.
- Delete legacy event emission once API/UI consume canonical events only.
- Keep explicitly experimental modules behind config until persistence/contracts exist.

Confidence: 0.9.

### 3. `agent.system` is still a God object

Evidence:

- `src/agent/system.clj` is still over 1300 lines.
- It builds config, SQLite, telemetry, trace, broker, runtime, memory, LLM, tools, skills, runners, orchestrator, Telegram, channel adapters, and API lifecycle.
- It exposes a broad facade for runtime, runners, orchestrator, chat, API lifecycle, config reload, and health.
- Run launch/control behavior still exists in both `src/agent/system.clj` and `src/agent/api/handlers/runs.clj`.

Reasoning:

System construction and domain behavior are fused. Reload, test setup, and runtime changes still require edits in a central high-blast-radius namespace.

Fix direction:

- Split into `agent.system.config`, `store`, `bus`, `tools`, `chat`, `runs`, `channels`, `api`.
- Make API handlers call run service functions, not duplicate launch/control mechanics.
- Keep `agent.system` as thin assembly only.

Confidence: 0.88.

### 4. Event contracts remain mixed

Evidence:

- `src/agent/runtime/loop.clj` emits validated runtime events.
- `src/agent/chat.clj` translates canonical runtime events into legacy chat events.
- `src/agent/api/handlers/chat.clj` accepts both old names and new names in stream filters.
- `src/agent/api/serializers.clj` still maps legacy events.
- `src/agent/runtime/schema.clj` still has legacy event maps.

Reasoning:

Consumers still need to understand multiple eras of event names and payloads. Debugging starts with "which event version did this path emit?"

Fix direction:

- Define one event envelope and finite event type set.
- Move legacy conversion to one external compatibility serializer.
- Add schema tests for every public stream event.

Confidence: 0.88.

### 5. Streaming/SSE handlers are still ad hoc

Evidence:

- `src/agent/api/handlers/ui.clj` starts futures, subscribes to events, catches `Throwable`, prints errors, and closes channels manually.
- `src/agent/api/handlers/chat.clj` starts futures for streaming chat.
- `src/agent/api/handlers/runs.clj` starts a future for run event streaming.
- `src/agent/api/responses.clj` still ignores stream writer exceptions.

Reasoning:

Streaming is repeated per handler with raw futures and broad catches. Terminal/error semantics and cleanup are not centralized.

Fix direction:

- Build one SSE service abstraction.
- Require structured terminal/error events.
- Track unsubscribe/close/dropped-event counts centrally.
- Remove handler-level `println` failures.

Confidence: 0.86.

### 6. Config load still bootstraps files and carries legacy normalization

Evidence:

- `src/agent/config.clj` starts `bootstrap-global-config!`, which creates config/context files.
- `src/agent/config.clj` calls bootstrap during `load-config`.
- `src/agent/config.clj` still normalizes legacy LLM provider shapes in live load path.
- `src/agent/config.clj` manually maps many env vars.

Reasoning:

Config loading should parse and validate. Here it also mutates disk and migrates legacy shapes, so tests/runtime need special isolation and config state is harder to reason about.

Fix direction:

- Split `init-config!` from pure `load-config`.
- Move legacy normalization to one migration command or remove it.
- Replace manual env parsing with declarative table/schema.

Confidence: 0.88.

### 7. Memory graph remains experimental without reconciliation

Evidence:

- `src/agent/memory/datahike.clj` still calls the Datahike backend a prototype.
- Graph failures are now recorded in health and `memory.graph.failed` events, but there is no reconciliation command for SQLite facts vs graph facts.
- Focused/full tests still print noisy Datahike debug logs.

Reasoning:

Failure visibility is fixed, but graph completeness is still not auditable. SQLite facts and graph facts can drift without an operator command to compare and repair them.

Fix direction:

- Keep graph backend experimental/off by default.
- Add reconciliation command: SQLite facts vs graph facts.
- Quiet Datahike test/runtime logging.

Confidence: 0.84.

### 8. Namespace size and hidden failure patterns remain high

Evidence:

- Large namespaces remain: `src/agent/ui.clj`, `src/agent/system.clj`, `src/agent/chat.clj`, `src/agent/orchestrator.clj`, `src/agent/config.clj`, `src/agent/telegram.clj`, `src/agent/runtime/loop.clj`.
- `src` still contains many `defonce`, raw `future`, broad `catch`, `Thread/sleep`, `legacy`, and `println` hits.
- `src/agent/ui.clj`, `src/agent/tools/common/http.clj`, `src/agent/mcp/core.clj`, and `src/agent/telegram.clj` still swallow exceptions in multiple paths.

Reasoning:

The codebase remains hard to support because ownership is not visually obvious. Large namespaces hide local invariants; broad catches hide causality.

Fix direction:

- Split by lifecycle boundary and behavior owner.
- Replace broad catches with typed errors + health/events.
- Keep comments on non-obvious contracts, not on obvious mechanics.

Confidence: 0.87.

## Recommended Order

1. Make chat a real system component.
2. Pick one event contract; delete legacy event adapters.
3. Split `agent.system` into lifecycle components.
4. Build shared SSE service.
5. Make config load pure.
6. Add memory graph reconciliation and quiet Datahike logs.
7. Split largest namespaces.

## Test Gaps

- Active chat cancellation/reload with system-owned chat service.
- Canonical-only event stream contract.
- Shared SSE terminal/error/cleanup behavior.
- Pure config load without filesystem writes.
- Memory graph reconciliation behavior.
- Typed-error coverage for broad-catch paths.

## Final Confidence

Overall confidence: 0.88.
