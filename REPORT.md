# IRIS Codebase Review: Remaining Issues

Date: 2026-05-26

Scope: current `/Users/skammer/Code/tmp/clj-agent` after remediation pass.

Verification:

- `clojure -M:test -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests)"`
- Result: 374 tests, 1508 assertions, 0 failures, 0 errors.
- Caveat: tests are green; remaining items are structural/supportability debt.

## Executive Verdict

Security/control-plane bugs from the first report are fixed. Remaining support cost is architectural:

- chat state is still process-global instead of a system component
- runtime migration still has compatibility adapters and legacy event shapes
- `agent.system`, `agent.chat`, `agent.ui`, `agent.config`, and `agent.orchestrator` are too large
- streaming/SSE and memory graph failures still rely on best-effort futures/catches
- orchestrator/federation APIs still expose process-local state as product API

Weighted confidence: 0.88.

## Priority Findings

### 1. `agent.chat` still owns process-global runtime state

Evidence:

- `src/agent/chat.clj:41-44` keeps `defonce` atoms for streaming state, session runtimes, loop workers, and manager lock.
- `src/agent/chat.clj` still starts queue/loop work with raw `future`.
- `src/agent/system.clj` now calls `chat/stop-all!` on reload/close, but chat manager state is not created, supervised, or injected as a normal system component.

Reasoning:

The stale-worker reload bug is mitigated, not architecturally solved. Chat runtime ownership still sits outside `create-system`. That keeps lifecycle, test isolation, cancellation, and reload semantics harder than needed.

Fix direction:

- Create explicit `chat-service` component in `create-system`.
- Move `streaming-state`, `session-runtimes`, and `loop-workers` into that component.
- Give it `start!`, `stop!`, `reload!`, `cancel-session!`, and `session-state` API.
- Inject it into API/UI/Telegram instead of reaching into namespace globals.

Confidence: 0.9.

### 2. Runtime migration still has compatibility adapters and legacy event shapes

Evidence:

- `README.md:16` now correctly says compatibility adapters still live in `src`.
- `src/agent/chat.clj` still requires `agent.loop`.
- `src/agent/system.clj:21`, `src/agent/ui.clj:8`, and `src/agent/api/handlers/agents.clj:12` still require `agent.orchestrator`.
- `src/agent/api/streaming.clj:2-4` still says legacy stream writers will be deleted later.
- `src/agent/runtime/schema.clj:243-278` still keeps legacy event mapping.
- `src/agent/api/serializers.clj:141` still maps legacy events through `legacy-event->canonical`.

Reasoning:

There is no single runtime contract yet. New behavior still crosses old chat/loop/orchestrator/event adapters, so every runtime change risks fixing the wrong layer.

Fix direction:

- Declare `agent.runtime.*` event/directive schema as only internal contract.
- Move compatibility adapters into one boundary namespace.
- Delete legacy event emission once API/UI consume canonical events only.
- Decide whether `agent.orchestrator` is product runtime or experimental module.

Confidence: 0.9.

### 3. `agent.system` is still a God object

Evidence:

- `src/agent/system.clj` is 1324 lines.
- It builds config, SQLite, telemetry, trace, broker, runtime, memory, LLM, tools, skills, runners, orchestrator, Telegram, channel adapters, API lifecycle.
- It exposes a broad facade for runtime, runners, orchestrator, chat, API lifecycle, config reload, and health.
- Run launch/control behavior still exists in both `src/agent/system.clj` and `src/agent/api/handlers/runs.clj`.

Reasoning:

System construction and domain behavior are fused. That makes reload, test setup, and runtime changes require edits in a central high-blast-radius namespace.

Fix direction:

- Split into `agent.system.config`, `store`, `bus`, `tools`, `chat`, `runs`, `channels`, `api`.
- Make API handlers call run service functions, not duplicate launch/control mechanics.
- Keep `agent.system` as thin assembly only.

Confidence: 0.88.

### 4. Event contracts remain mixed

Evidence:

- `src/agent/runtime/loop.clj:27-37` emits validated runtime events.
- `src/agent/chat.clj` translates canonical runtime events into legacy chat events.
- `src/agent/api/handlers/chat.clj` accepts both old names and new names in stream filters.
- `src/agent/api/serializers.clj:141` still maps legacy events.
- `src/agent/runtime/schema.clj:243-278` still has legacy event maps.

Reasoning:

Consumers still need to understand multiple eras of event names and payloads. Debugging starts with "which event version did this path emit?"

Fix direction:

- Define one event envelope and finite event type set.
- Move legacy conversion to one external compatibility serializer.
- Add schema tests for every public stream event.

Confidence: 0.88.

### 5. Streaming/SSE handlers are still ad hoc

Evidence:

- `src/agent/api/handlers/ui.clj:151-229` starts futures, subscribes to events, catches `Throwable`, prints errors, and closes channel manually.
- `src/agent/api/handlers/chat.clj:97-180` starts futures for streaming chat.
- `src/agent/api/handlers/runs.clj:276-294` starts a future for run event streaming.
- `src/agent/api/responses.clj:95-101` still ignores stream writer exceptions.

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

- `src/agent/config.clj:421` starts `bootstrap-global-config!`, which creates config/context files.
- `src/agent/config.clj:851` calls bootstrap during `load-config`.
- `src/agent/config.clj:450-490` still normalizes legacy LLM provider shapes in live load path.
- `src/agent/config.clj:635-830` manually maps many env vars.

Reasoning:

Config loading should parse and validate. Here it also mutates disk and migrates legacy shapes, so tests/runtime need special isolation and config state is harder to reason about.

Fix direction:

- Split `init-config!` from pure `load-config`.
- Move legacy normalization to one migration command or remove it.
- Replace manual env parsing with declarative table/schema.

Confidence: 0.88.

### 7. Memory graph remains best-effort and silent on failure

Evidence:

- `src/agent/memory/datahike.clj:2` calls the Datahike backend a prototype.
- `src/agent/memory/core.clj:339` catches graph query exceptions and returns `[]`.
- `src/agent/memory/core.clj:362` catches graph save exceptions and ignores them.
- `src/agent/memory/core.clj:385` catches graph remove exceptions and ignores them.
- Full test output still includes noisy Datahike debug logs.

Reasoning:

SQLite facts and graph facts can diverge silently. Search completeness becomes unknowable when graph errors disappear.

Fix direction:

- Keep graph backend experimental/off by default.
- Record graph failures in health and events.
- Add reconciliation command: SQLite facts vs graph facts.
- Quiet Datahike test logging.

Confidence: 0.86.

### 8. Public orchestrator/federation API is still process-local

Evidence:

- `src/agent/orchestrator.clj:2` says "Rewritten in-memory orchestrator/subagent runtime."
- `src/agent/orchestrator.clj:117-125` creates state as an atom.
- `src/agent/api/handlers/agents.clj` exposes agents, interop, worker spawn, federation, and channel operations over `/v1`.

Reasoning:

API shape implies durable product behavior, but restart/reload loses agents, channels, peers, deliveries, and messages.

Fix direction:

- Hide endpoints behind `:orchestrator {:enabled false}` until persistence exists, or mark as experimental in API response.
- Persist orchestrator entities and interop deliveries in SQLite.
- Add restart/reload tests for agents, channels, federation peers, and interop messages.

Confidence: 0.9.

### 9. Namespace size and hidden failure patterns remain high

Evidence:

- Large namespaces: `src/agent/ui.clj` 1371 lines, `src/agent/system.clj` 1324, `src/agent/chat.clj` 983, `src/agent/orchestrator.clj` 934, `src/agent/config.clj` 899, `src/agent/telegram.clj` 871, `src/agent/runtime/loop.clj` 744.
- `src` still contains many `defonce`, raw `future`, broad `catch`, `Thread/sleep`, `legacy`, and `println` hits.
- `src/agent/ui.clj`, `src/agent/memory/core.clj`, `src/agent/tools/common/http.clj`, `src/agent/mcp/core.clj`, and `src/agent/telegram.clj` still swallow exceptions in multiple paths.

Reasoning:

The codebase remains hard to support because ownership is not visually obvious. Large namespaces hide local invariants; broad catches hide causality.

Fix direction:

- Split by lifecycle boundary and behavior owner.
- Replace broad catches with typed errors + health/events.
- Keep comments on non-obvious contracts, not on obvious mechanics.

Confidence: 0.87.

### 10. Minor repo hygiene debt remains

Evidence:

- `scripts/iris-ioslated-rebuild.sh` still exists as typo compatibility shim to `scripts/iris-isolated-rebuild.sh`.

Reasoning:

Small duplicate entrypoints are low risk, but they keep stale names alive.

Fix direction:

- Delete typo shim or add explicit deprecation comment and test.

Confidence: 0.82.

## Recommended Order

1. Make chat a real system component.
2. Pick one event contract; delete legacy event adapters.
3. Split `agent.system` into lifecycle components.
4. Build shared SSE service.
5. Decide orchestrator fate: persist or hide.
6. Make config load pure.
7. Surface memory graph failures.
8. Split largest namespaces.

## Test Gaps

- Active chat cancellation/reload with system-owned chat service.
- Canonical-only event stream contract.
- Shared SSE terminal/error/cleanup behavior.
- Pure config load without filesystem writes.
- Orchestrator restart/reload behavior.
- Memory graph failure surfaces in health/events.

## Final Confidence

Overall confidence: 0.88.

Key caveats:

- Current full test suite passes.
- This report intentionally excludes fixed items from the first report.
- Remaining issues are mostly maintainability/architecture, not known failing tests.
