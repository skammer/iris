# Refactoring Plan — June 2026

#decision
Inputs: [[refactoring-2026-06-findings]], [[codebase-map]]. Status legend: ☐ planned / ☑ done / ✗ dropped (reason inline).

## Principles

1. **Behavior-preserving by default.** Every phase ends green on the full suite (baseline: 500 tests / 1859 assertions; 1 known flaky chat-harness cancellation test).
2. **Prefer deletion.** Dead code first; it shrinks every later diff.
3. **Respect coverage boundaries.** Internals with facade-only coverage (chat.*, sqlite sub-nss) get refactored behind their facades; dark namespaces don't get reshaped without characterization tests.
4. **One commit per coherent step**, full test run before each.

## Phase 1 — Bug fixes (small, surgical) — ✅ done, commit `70b466f`

- ☑ 1.1 Fix http.clj misplaced paren so the 5 execution-safety options reach `create-tool-description`; `:parallel-safe-read-methods?` works again.
- ☑ 1.2 Telegram poison update: poll offset advances past failed updates (row preserved as :failed in channel_inbox); deny double-tap answers "Already denied.".
- ☑ 1.3 `llm.messages/response-thinking`: added missing `:reasoning` key (aligned with streaming path).
- ☑ 1.4 Added orphaned `agent.cli-test` + `agent.api.event-compat-test` to the test runner (suite 500 → 512 tests).
- ☑ 1.5 Deleted events-handler private `bounded-limit` copy; store owns clamping.

## Phase 2 — Dead code deletion — ✅ done, commit `927d861` (−394 LOC net)

- ☑ 2.1 LLM batch: service complete/stream/embed, spec block, ILLMProviderWithConfig (+system_test assertions rewritten against record fields), get-metrics, registry dead bits, unused arities, `:stream-events`, provider-tool-result->internal. `complete`/`stream` protocol methods kept (Phase 5 candidate).
- ☑ 2.2 Deleted `agent.api.validation`: ensure-session-exists! → api.helpers over `sessions.service/session-exists?`; emit-system-event! call sites → `system.events/log-event!` (broker-bypass fallback gone); normalizers moved to sole-caller handlers.
- ☑ 2.3 Deleted `agent.federation.http` facade; consumers repointed at crypto/auth/forwarder.
- ☑ 2.4 Entry/system dead fns deleted.
- ☑ 2.5 API handlers dead code deleted; form parsing via ring.util.codec.
- ☑ 2.6 Chat dead surface deleted.
- ☑ 2.7 Config: `:iris/context-files` knob + `default-markdown-content` fallback removed (classpath templates are the single source).
- ☑ 2.8 Tools/display dead fns deleted (`args-preview` kept as canonical name).
- ☑ 2.9 Persistence facade dead surface deleted.

## Phase 3 — Cross-cutting consolidation — ✅ done

- ☑ 3.1 `agent.security/sha256-hex` is the one digest helper; 3 copies deleted.
- ☑ 3.2 `agent.security/canonical-json`: recursive key-sort, keywords→names, nil map values dropped. Shared by approvals/input-hash, runtime.loop approval alignment, tools.core activity names — fixes the fake-`{:canonical true}` bug. doom-loop keeps its richer typed pr-str canonicalization for dedupe but uses the shared digest; federation.crypto frozen (wire format). In-flight approvals minted pre-change hash-mismatch; TTL 900s, impact nil.
- ☑ 3.3 `agent.util/truncate` with marker-fn; 7 sites consolidated, exact markers preserved (model-visible text unchanged).
- ☑ 3.4 HTML escaping unified on `tools.display` (escape-html, escape-html-truncated); telegram copies deleted.
- ☑ 3.5 `tools.approvals/default-expires-at` single home; chat.turn + both handlers use it.
- ☑ 3.6 Reasoning-key extraction: `llm.messages/reasoning-text` (providers.common and llm.core were require cycles — messages is the cycle-free home reached by all three sites).
- ☑ 3.7 Persistence: `common/valid-enum!` (×4 deleted), `common/count-rows` (×9 collapsed), `sqlite.schema` ns dissolved (count-sessions → sessions, health-check → facade).
- ☑ 3.8 `agent.runtime.calls`: shared tool-call accessors (tool-name-of, call-id, call-input with malformed sentinel, fs-mutation-tools); drifted copies in nudge/runtime.tools deleted. doom-loop's string-name normalize kept (different fingerprint contract).
- ☑ 3.9 Malli validators precompiled in `runtime.schema` (per-streaming-delta hot path).

## Phase 4 — Construction & error-path cleanup — ✅ done, commits `1cc39b6` + `fe4c779`

- ☑ 4.1 `create-tool-registry` single options-map arity; 6-arity ladder deleted; `components/build-tool-registry` is the one construction site (3 drifting call sites collapsed).
- ☑ 4.2 `safe-stop!` unifies close-system!/stop-runtime-edges! failure semantics; attach-telegram-service single copy in components.clj; :chat added to health/default-components.
- ☑ 4.3 Full reload builds via `create-system-components` with live refs injected — build-discard-rebuild chain deleted (second tool registry, orphaned telegram service, re-mark doseq). system_test's full-reload test re-stubbed at the new construction point.
- ☑ 4.4 `api.errors` table-driven; error boundary translates domain ex-data centrally; exactly-equivalent handler catch ladders removed (memory vault, providers, sessions, tools execute, approvals decide); custom ladders kept (agents, ui HTML fragments).

## Phase 5 — Targeted structural wins (do as budget allows, riskiest last)

- ☑ 5.1 history.clj copy-paste pairs + subscribers.clj identical branches (commit `21375a9`).
- ☑ 5.2 Terminal emitters extracted from `runtime.loop/run!` (commit `3dc4eec`): one named fn per loop ending (max-steps/max-tokens/doom-loop/guardrail/completed/approval/cancelled); alias-def block deleted; file cljfmt'd. Deeper plan-phase extraction deliberately left — the loop/recur state threading is the remaining complexity and needs its own focused pass.
- ☑ 5.3 LLM providers: `invoke` is the single execution path. Per-provider `complete`/`stream` bodies deleted (−131 LOC); core gained `complete-via-invoke`/`stream-via-invoke`; ollama's channel stream now routes through `stream-response->turn`, fixing its silent thinking/tool-call/usage drops. Protocol methods kept (several test fakes implement them); `default-invoke` Object-extension kept (adapts complete-only fakes).
- ◐ 5.4 Telegram: the real bug fixed — `run-chat-events!` treats a result-ch `:error` as terminal, so an early chat/run! failure no longer spins the loop + typing indicator forever. The 3-way path collapse (callback/events/continuation) is deferred: most telegram tests drive the callback path via `:chat-fn`, so the collapse requires rewriting the test harness onto a broker — should be its own pass, not piggybacked on this one.

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
