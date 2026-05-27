# IRIS Codebase Review: Remaining Issues

Date: 2026-05-27

Scope: current `/Users/skammer/Code/tmp/clj-agent` after latest remediation pass.

Verification:

- Latest large-namespace refactor seam tests pass:
  `IRIS_CONFIG_DIR=/private/tmp/iris-refactor-config IRIS_DATA_DIR=/private/tmp/iris-refactor-data clojure -M:test -e "(require 'agent.config-test :reload 'agent.ui-test :reload 'agent.runtime.loop-test :reload 'agent.chat-test :reload) (clojure.test/run-tests 'agent.config-test 'agent.ui-test 'agent.runtime.loop-test 'agent.chat-test)"`
- Result: 93 tests, 364 assertions, 0 failures, 0 errors.
- Latest changed-file lint passes:
  `clj-kondo --lint src/agent/config.clj src/agent/config/env.clj src/agent/runtime/loop.clj src/agent/runtime/messages.clj src/agent/ui.clj src/agent/ui/memory.clj`
- Result: 0 errors, 0 warnings.
- Latest focused API/UI/system tests pass:
  `IRIS_CONFIG_DIR=/private/tmp/iris-refactor-api-config IRIS_DATA_DIR=/private/tmp/iris-refactor-api-data clojure -M:test -e "(require 'agent.api-test :reload 'agent.api-smoke-test :reload 'agent.system-test :reload 'agent.ui-test :reload 'agent.chat-test :reload 'agent.runtime.child-test :reload) (clojure.test/run-tests 'agent.api-test 'agent.api-smoke-test 'agent.system-test 'agent.ui-test 'agent.chat-test 'agent.runtime.child-test)"`
- Result: 78 tests, 542 assertions, 0 failures, 0 errors.
- Latest memory reconciliation tests pass:
  `clojure -M:test -e "(require 'agent.memory.core-test :reload 'agent.cli-test :reload 'agent.tools.common.memory-test :reload) (clojure.test/run-tests 'agent.memory.core-test 'agent.cli-test 'agent.tools.common.memory-test)"`
- Result: 28 tests, 122 assertions, 0 failures, 0 errors.
- `git diff --check` passes.
- Full suite result: 383 tests, 1540 assertions, 1 failure in `agent.runners.docker-podman-e2e-test/docker-child-runtime-e2e-test` waiting for Docker child run status `running`.

## Executive Verdict

Security/control-plane bugs are fixed. Public process-local orchestrator APIs are now disabled by default. Latest remediation:

- chat state moved into a system-owned `chat-service`
- runtime/chat/API event contract is now canonical; historical event conversion moved to one API boundary
- SSE lifecycle/error/cleanup mechanics moved into one managed streaming service
- UI memory rendering, runtime message repair, and config env overrides moved into owner namespaces

Remaining support cost is architectural:

- `agent.chat`, `agent.ui`, `agent.config`, and `agent.orchestrator` are still too large
- config still concentrates defaults, migration, validation, and path finalization in one namespace
- memory graph remains experimental/off by default, but SQLite-vs-graph reconciliation and Datahike log noise are fixed

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

Status: Fixed 2026-05-27.

Evidence after fix:

- `src/agent/api/streaming.clj` now owns managed SSE lifecycle, worker tasks, cleanup, broker subscriptions, terminal/error helpers, and metrics.
- UI/chat/run/event stream handlers use `streaming/managed-response` or `streaming/once-response`.
- Handler-level raw futures, manual broker unsubscribe, manual http-kit close, and stream `println` failures were removed.
- `/health` exposes SSE metrics for opened/closed/completed/error/send-error/dropped/unsubscribe counts.
- `src/agent/api/responses.clj` logs stream writer failures instead of swallowing them.

Reasoning:

Streaming lifecycle is now centralized. Handlers still decide payload shape, but open/close/error/cleanup/subscription mechanics live in one service.

Remaining caveat:

- Dedicated public stream contract matrix is still useful.

Confidence: 0.9.

### 6. Config load no longer bootstraps files or normalizes legacy LLM config

Status: fixed.

Evidence:

- `src/agent/config.clj` now exposes explicit `init-config!` for writing missing global files.
- `src/agent/config.clj` `load-config` only reads, merges, applies env overrides, finalizes paths, and validates.
- Legacy LLM provider-shape conversion moved to `migrate-legacy-config` / `migrate-config-file`; live load rejects legacy keys.
- Env overrides are declared in `src/agent/config/env.clj`.
- `src/agent/cli.clj` adds `config init` and `config migrate path/to/config.edn`.

Reasoning:

Config loading now has a narrower contract. Disk initialization is an explicit CLI/runtime step, legacy migration is opt-in, and env behavior is auditable from one table.

Confidence: 0.9.

### 7. Memory graph remains experimental but now has reconciliation

Status: fixed.

Evidence:

- `src/agent/memory/core.clj` now treats SQLite facts as source of truth and exposes `reconcile-graph-memory`.
- `src/agent/cli.clj` adds `memory reconcile [--repair]`.
- Reconciliation reports missing, diverged, stale, and graph-only active graph edges, with dry-run default and repair mode.
- `src/agent/memory/datahike.clj` lists active graph facts for audit and suppresses noisy Datahike DEBUG/INFO logs.
- `test/agent/memory/core_test.clj` covers dry-run detection and repair.
- `test/agent/cli_test.clj` covers CLI dispatch.

Reasoning:

Graph memory remains a derived experimental surface, but drift is now observable and repairable. Graph-only facts are preserved by default to avoid deleting manually authored graph data.

Confidence: 0.88.

### 8. Namespace size and hidden failure patterns remain high

Status: partially fixed.

Evidence:

- Large namespaces remain: `src/agent/chat.clj`, `src/agent/orchestrator.clj`, `src/agent/telegram.clj`, `src/agent/memory/core.clj`, `src/agent/config.clj`, `src/agent/persistence/sqlite/migrations.clj`, and `src/agent/runtime/loop.clj`.
- Current large namespace line counts after this refactor: `src/agent/chat.clj` 985, `src/agent/orchestrator.clj` 937, `src/agent/telegram.clj` 919, `src/agent/memory/core.clj` 835, `src/agent/config.clj` 682 plus `src/agent/config/env.clj` 140 extracted, `src/agent/runtime/loop.clj` 591 plus `src/agent/runtime/messages.clj` 182 extracted, `src/agent/ui.clj` 879 plus `src/agent/ui/render.clj` 224 and `src/agent/ui/memory.clj` 289 extracted.
- `src` still contains many `defonce`, raw `future`, broad `catch`, `Thread/sleep`, `legacy`, and `println` hits.
- UI message/tool/run rendering helpers moved from `src/agent/ui.clj` to `src/agent/ui/render.clj`, reducing the biggest namespace by 218 lines while keeping route/panel ownership in `agent.ui`.
- UI memory workspace/results moved from `src/agent/ui.clj` to `src/agent/ui/memory.clj`; `agent.ui` keeps compatibility facade vars for API handlers/tests.
- Runtime tool-protocol history repair and synthetic tool-result construction moved from `src/agent/runtime/loop.clj` to `src/agent/runtime/messages.clj`; `agent.runtime.loop` keeps its public constants/functions as aliases.
- Config environment parsing and override dispatch moved from `src/agent/config.clj` to `src/agent/config/env.clj`; `agent.config/load-config` remains the single public load path.
- Chat stream callbacks, tool-call callbacks, persistence subscribers, and auto-compaction failures now emit `:chat.operation.failed` instead of disappearing inside catch blocks.
- Telegram draft, tool-summary, and typing delivery failures now emit `:telegram.operation.failed` events instead of disappearing silently.
- MCP initialized-notification failure is preserved on the returned client as `:initialized-notification-error`.
- HTTP responses declaring JSON now raise typed `:invalid-json-response` when parsing fails; non-JSON bodies remain raw.
- Tests now cover chat callback/compaction failure events, Telegram draft/typing failure events, MCP initialized notification failure retention, and invalid JSON HTTP responses.
- Remaining broad-catch/best-effort sites include UI display fallback parsing, expected orchestrator local-vs-federated lookup fallback, runtime terminal fallback handling, config console reporter output, and Telegram polling/media error paths.

Reasoning:

The codebase remains hard to support because ownership is not visually obvious. Large namespaces hide local invariants; broad catches hide causality. This pass split UI memory rendering, runtime message/protocol repair, and config env override ownership while preserving public facades. Earlier work split one UI rendering seam and made the highest-risk callback, compaction, Telegram delivery, MCP initialization, and HTTP JSON parse failures observable. It did not split `chat`, `orchestrator`, `telegram`, or memory core.

Fix direction:

- Split by lifecycle boundary and behavior owner.
- Replace broad catches with typed errors + health/events.
- Keep comments on non-obvious contracts, not on obvious mechanics.

Confidence: 0.88.

## Recommended Order

1. Make config load pure.
2. Split largest namespaces.

## Test Gaps

- Active chat cancellation/reload with system-owned chat service.
- Dedicated public stream contract matrix.
- Pure config load without filesystem writes.
- Typed-error coverage for broad-catch paths.

## Final Confidence

Overall confidence: 0.88.
