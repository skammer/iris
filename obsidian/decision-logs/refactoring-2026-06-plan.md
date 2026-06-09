# Refactoring Plan — June 2026

#decision
Inputs: [[refactoring-2026-06-findings]], [[codebase-map]]. Status legend: ☐ planned / ☑ done / ✗ dropped (reason inline).

## Principles

1. **Behavior-preserving by default.** Every phase ends green on the full suite (baseline: 500 tests / 1859 assertions; 1 known flaky chat-harness cancellation test).
2. **Prefer deletion.** Dead code first; it shrinks every later diff.
3. **Respect coverage boundaries.** Internals with facade-only coverage (chat.*, sqlite sub-nss) get refactored behind their facades; dark namespaces don't get reshaped without characterization tests.
4. **One commit per coherent step**, full test run before each.

## Phase 1 — Bug fixes (small, surgical)

- ☐ 1.1 Fix http.clj misplaced paren so the 5 execution-safety options reach `create-tool-description`; verify `:parallel-safe-read-methods?` works (GET/HEAD parallel-safe).
- ☐ 1.2 Telegram poison update: advance poll offset past failed updates (row already preserved as :failed in channel_inbox); tolerate `:approval-decision-conflict` on the deny path ("Already denied.").
- ☐ 1.3 `llm.messages/response-thinking`: add missing `:reasoning` key (align with streaming path).
- ☐ 1.4 Add orphaned `agent.cli-test` + `agent.api.event-compat-test` to the test runner (verify they pass first).
- ☐ 1.5 Delete events-handler private `bounded-limit` copy; rely on the store's canonical clamp.

## Phase 2 — Dead code deletion (~450 LOC)

- ☐ 2.1 LLM batch: service complete/stream/embed, spec block, ILLMProviderWithConfig (+rewrite 2 system_test assertions against factory), get-metrics, registry dead bits, unused arities, `:stream-events`, provider-tool-result->internal. Keep `complete`/`stream` protocol methods themselves for now (tests exercise them; full collapse is Phase 5 candidate).
- ☐ 2.2 Delete `agent.api.validation`: ensure-session-exists! → thin helper over `sessions.service/session-exists?`; emit-system-event! call sites → `system.events/log-event!` (kills the broker-bypass fallback); normalize-chat-request → handlers.chat; normalize-trust-policies-body → handlers.agents.
- ☐ 2.3 Delete `agent.federation.http` facade; repoint handlers/federation + 2 tests at crypto/auth/forwarder.
- ☐ 2.4 Entry/system dead fns (health/register!, system.events subscribe/unsubscribe/list-events, telemetry observer-*-types, system/reload-status + :reload-status key, unused arities).
- ☐ 2.5 API handlers dead code (split-command-optional, parse-long*, runs wrappers, helpers/body-value; ring.util.codec for form parsing).
- ☐ 2.6 Chat dead surface: active-turn `:stream?`/`:stream-state`, persist-final-assistant! alias.
- ☐ 2.7 Config: drop `:iris/context-files` knob + `default-markdown-content` dead fallback.
- ☐ 2.8 Tools/display dead fns (with-approval, read-only-call? → update test callers, result/block-preview, params/args-preview alias, :prerequisites metadata).
- ☐ 2.9 Persistence facade dead surface (init-store! merge, busy-timeout re-export, upsert wrapper).

## Phase 3 — Cross-cutting consolidation

- ☐ 3.1 `agent.security/sha256-hex` as the one digest helper; delete 3 copies.
- ☐ 3.2 Canonical input encoding: one shared fn (approvals' normalize-then-encode semantics, since approval hashes are the contract); route loop/tools.core/doom-loop through it; leave federation.crypto frozen (wire format). Fixes bug 4.
- ☐ 3.3 `agent.util/truncate` parameterized by marker; replace the byte-identical copies (history, runtime.messages, loop, context-pack) preserving each call site's exact marker text; leave telegram/display variants only if marker semantics differ.
- ☐ 3.4 HTML escaping unified on `tools.display`; telegram nss consume it.
- ☐ 3.5 `approval-expires-at` single home (tools.approvals); reuse in chat.turn + handlers.
- ☐ 3.6 Reasoning-key extraction: one fn in `llm.providers.common`, used by ollama/stream/messages.
- ☐ 3.7 Persistence: `common/valid-enum!` (×4 deleted), `common/count-rows` (×8 collapsed), dissolve `sqlite.schema` ns (count-sessions → sessions, health-check → facade).
- ☐ 3.8 `agent.runtime.calls`: shared tool-call accessors (normalize-tool-name, call-input, canonical-input); delete 4 drifted copies across loop/nudge/tools/tool-router/doom-loop.
- ☐ 3.9 Precompile malli validators in `runtime.schema` (hot-path: every streaming delta).

## Phase 4 — Construction & error-path cleanup

- ☐ 4.1 `create-tool-registry` takes a single options map; delete the 6-arity ladder; `components/build-tool-registry` helper; collapse the 3 duplicated call sites.
- ☐ 4.2 `system.clj`: shared safe-stop! teardown (unify close-system!/stop-runtime-edges! semantics); move attach-telegram-service into components.clj (single copy); single component-id list.
- ☐ 4.3 Full reload: pass old system-ref/reload-state/health-registry/control into `create-system-components` instead of build-discard-rebuild. (Covered by system_test.)
- ☐ 4.4 `api.errors`: table-driven domain-error mapping; extend error boundary to translate domain ex-data centrally; strip the ~15 per-handler try/catch ladders incrementally (api_test as the net).

## Phase 5 — Targeted structural wins (do as budget allows, riskiest last)

- ☐ 5.1 history.clj copy-paste pairs + subscribers.clj identical branches (low risk, behind chat facade).
- ☐ 5.2 Decompose `runtime.loop/run!` into phase fns + single terminal emitter (17 deftests guard it).
- ☐ 5.3 LLM providers: make `invoke` the single execution path; `complete`/`stream` become wrappers (kills the triplicated dispatch); update provider tests.
- ☐ 5.4 Telegram: delete the callback streaming path; single broker-event chat runner (telegram_test guards).

## Explicitly dropped (this round) — with reasons

- ✗ **MCP delete-or-wire**: product decision; left unwired, documented in findings.
- ✗ **Orchestrator races/durability** (lost-update, federation result handlers): subsystem is env-gated experimental and documented as non-durable; fixing the race properly means per-agent CAS loops — separate effort.
- ✗ **session_entries as single source of truth for messages**: data migration, high risk, needs its own design note.
- ✗ **Ragtime → hand-rolled migration runner**: working code, low pain; not worth the churn now.
- ✗ **`chat.turn/run-turn!` decomposition**: facade-only coverage; needs characterization tests first (follow-up).
- ✗ **Telemetry 3-way split**: high blast radius (every subsystem requires it); deferred to its own pass.
- ✗ **Serializers/handler-map table-driving, route-coerced body adoption**: worthwhile but mechanical-large; queued behind this round.
- ✗ **Skills registry caching**: behavior change (mtime invalidation) — needs a decision on staleness tolerance.

## Verification protocol

Full suite after every phase: `env IRIS_CONFIG_DIR=target/test-iris-config IRIS_DATA_DIR=target/test-iris-data clojure -J-Djava.io.tmpdir=$PWD/target/test-tmp -M:test -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests) (shutdown-agents)"`. Expected: 0 new failures vs baseline (the chat-harness cancellation test is known-flaky). Smoke: `clojure -M -m agent.core "hello"` equivalent dry checks where feasible. clj-kondo should not regress (modulo deleted-code warnings disappearing).
