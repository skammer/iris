# IRIS Codebase Review: Remaining Issues

Date: 2026-05-27

Scope: current `/Users/skammer/Code/tmp/clj-agent` after latest remediation pass.

Verification:

- Latest focused event-contract tests pass:
  `IRIS_CONFIG_DIR=/private/tmp/iris-runtime-clean-config IRIS_DATA_DIR=/private/tmp/iris-runtime-clean-data clojure -M:test -e "(require 'agent.runtime.schema-test :reload 'agent.api.event-compat-test :reload 'agent.tools.core-test :reload 'agent.chat-test :reload 'agent.api-test :reload 'agent.telegram-test :reload) (clojure.test/run-tests 'agent.runtime.schema-test 'agent.api.event-compat-test 'agent.tools.core-test 'agent.chat-test 'agent.api-test 'agent.telegram-test)"`
- Result: 77 tests, 501 assertions, 0 failures, 0 errors.
- `git diff --check` passes.
- Full suite was not rerun after the event-contract cleanup.

## Executive Verdict

Security/control-plane bugs are fixed. Public process-local orchestrator APIs are now disabled by default. Latest remediation:

- chat state moved into a system-owned `chat-service`
- runtime/chat/API event contract is now canonical; historical event conversion moved to one API boundary

Remaining support cost is architectural:

- `agent.chat`, `agent.ui`, `agent.config`, and `agent.orchestrator` are still too large
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

### 2. Runtime migration compatibility adapters and legacy event shapes

Status: Fixed 2026-05-27 for event contract cleanup.

Evidence after fix:

- `README.md` no longer says compatibility adapters live in `src`.
- `src/agent/runtime/schema.clj` now contains canonical runtime schemas only.
- Historical event-name conversion lives in `src/agent/api/event_compat.clj`.
- `src/agent/chat.clj` no longer emits `chat.*`, `message.appended`, or `completion.completed` events.
- API/UI/Telegram stream filters now consume canonical event names.
- `src/agent/api/streaming.clj` no longer carries unused legacy JDK stream writers.

Reasoning:

There is now one runtime event contract for chat/runtime flow. Legacy persisted event rows are normalized only at the API serialization boundary.

Remaining caveat:

- `agent.loop` and `agent.orchestrator` still exist, but they are broader lifecycle/ownership cleanup, not part of the legacy event-shape issue.

Confidence: 0.91.

### 3. `agent.system` is still a God object

Status: Fixed 2026-05-27.

Evidence after fix:

- `src/agent/system.clj` is now 285 lines.
- `agent.system` is lifecycle-only: create/current/reload/start API/stop API/close.
- System construction moved into focused service namespaces under `agent.system.*`, `agent.llm.service`, `agent.tools.service`, `agent.runs.service`, `agent.kernel.service`, and `agent.sessions.service`.
- Run launch/control/reclaim behavior now lives in `agent.runs.service`; API handlers delegate to it.
- No `requiring-resolve 'agent.system` or `agent.system/` callers remain in `src` or `test`.

Reasoning:

System construction and domain behavior are now separated. The system namespace remains the assembly/lifecycle boundary, while runtime, tools, sessions, health, events, LLM, and kernel behavior have narrower owners.

Remaining caveat:

- Downstream non-repo callers of removed `agent.system/*` facade functions must migrate to service namespaces.

Confidence: 0.92.

### 4. Event contracts now canonical

Status: Fixed 2026-05-27.

Evidence after fix:

- `src/agent/runtime/loop.clj` emits validated canonical runtime events.
- `src/agent/chat.clj` persists runtime events without translating them into legacy chat events.
- `src/agent/api/handlers/chat.clj` and `src/agent/api/handlers/ui.clj` filter canonical event names only.
- `src/agent/api/serializers.clj` calls `agent.api.event-compat/canonicalize-event` only for historical stored rows.
- `test/agent/api/event_compat_test.clj` covers legacy persisted-row normalization.

Reasoning:

Runtime consumers no longer need to understand multiple event eras. Historical rows remain readable through one explicit compatibility boundary.

Remaining caveat:

- Public stream event tests cover current behavior, but a dedicated contract test matrix per public stream route would still be cleaner.

Confidence: 0.9.

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

1. Build shared SSE service.
2. Make config load pure.
3. Add memory graph reconciliation and quiet Datahike logs.
4. Split largest namespaces.

## Test Gaps

- Active chat cancellation/reload with system-owned chat service.
- Dedicated public stream contract matrix.
- Shared SSE terminal/error/cleanup behavior.
- Pure config load without filesystem writes.
- Memory graph reconciliation behavior.
- Typed-error coverage for broad-catch paths.

## Final Confidence

Overall confidence: 0.88.
