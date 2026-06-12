# Refactoring Review Findings — June 2026

#decision #issue
Source: 16-agent deep review (12 subsystem readers + 4 cross-cutting analysts), 2026-06-09. Map: [[codebase-map]]. Plan: [[refactoring-2026-06-plan]].
Baseline before refactor: 500 tests / 1859 assertions, 1 pre-existing flaky failure (`agent.chat-harness-test/harness-cancels-active-session-and-clears-working-state-test`, timing-sensitive). clj-kondo: 99 errors (all HugSQL `*-sqlvec` false positives) + 23 warnings.

## Myths dispelled

- **No dead namespaces.** All 147 src namespaces are reachable from `agent.core` via static requires.
- **`agent.loop` is not a legacy `agent.runtime.loop`.** It's the `/loop` self-iteration command — a name collision, not duplication.
- **`agent.chat` / `agent.kernel` / `agent.telegram` top namespaces are thin facades**, not superseded copies of their directories.
- **System-map threading is clean** — explicit construction + system-ref re-deref; only 4 true global atoms (`agent.loop/loop-states`, `agent.logging/publisher-state`, `agent.nrepl/current-system`, `agent.ui.render` trusted-fragment registry).
- **Persistence facade discipline holds** — 20+ consumers all go through `agent.persistence.sqlite`, except a handful of API/chat call sites noted below.
- **core.async: zero go blocks** (all real threads) — no blocking-in-go class of bugs.

## Real bugs found

1. **http.clj misplaced paren** (`tools/common/http.clj:166`) — `)` closes `create-tool-description` early, so `:operation`, `:approval-sensitive?`, `:action-key`, `:read-only-actions`, `:parallel-safe-actions` become ignored keys of `create-tool`. The `:parallel-safe-read-methods?` config knob is completely dead; GET/HEAD never marked parallel-safe.
2. **Telegram poison update = head-of-line blocking forever** (`telegram.clj:399-442`) — a failing update is marked `:failed` in channel_inbox but the poll offset never advances; the same update refetches indefinitely, halting the whole inbound queue. Concrete trigger: double-tap Deny on an approval card (`:approval-decision-conflict` is unhandled on the deny path).
3. **Reasoning-key drift loses thinking blocks** — `llm.messages/response-thinking` checks `:thinking/:reasoning-content/:reasoning_content` but not `:reasoning`; the streaming path checks all four. Non-streaming responses carrying only `:reasoning` silently lose thinking.
4. **`cheshire {:canonical true}` is a no-op** — `runtime/loop.clj` and `tools/core.clj` rely on it for canonical input hashing; same logical input can hash differently across doom-loop dedupe, approval input-hash, and loop approval alignment (approvals.clj normalizes nils/keywords first, the others don't).
5. **`run-chat-events!` can hang forever** (telegram) if `chat/run!` throws before the runtime loop emits agent-end, or if the terminal broker event is dropped (sliding buffer).
6. **`api.validation/emit-system-event!` fallback bypasses the broker** — events written via the `(:store system)` fallback never reach SSE subscribers.
7. **Malli schema recompiled on every runtime event** including every streaming delta (`runtime/schema.clj validate!` calls `m/validate` with a fresh compile each time) — hot-path waste.
8. **Test-runner drift**: `agent.cli-test` and `agent.api.event-compat-test` exist on disk but are not in the hardcoded runner list — they never run in CI.

## Dead code (verified by grep, with callers checked)

- **LLM batch (~250 LOC)**: `llm.service/complete|stream|embed`; clojure.spec block in `llm.core` (never validated against); `ILLMProviderWithConfig` protocol + both impls; `get-metrics` (static placeholders); registry `:provider-health` option + no-op re-assocs; assorted unused arities (`stream-response->turn` 2-arity, `throw-empty-content!` positional, post-json/post-stream transport-opts arities); `:stream-events` accumulation on streamed turns; `messages/provider-tool-result->internal`.
- **Entry/system (~70 LOC)**: `health/register!`, `system.events/subscribe-events|unsubscribe-events|list-events`, `telemetry/observer-event-types|observer-metric-types`, `system/reload-status` (+ unused `:reload-status` in system-control), unused arities of `create-recorded-event-sink`/`create-orchestrator`.
- **API**: `agent.api.validation` is deletable entirely (duplicates `sessions.service` + `system.events`, plus the broker-bypass fallback); `helpers/body-value`; hand-rolled urlencoded parsing (ring.util.codec is on classpath); `handlers/tools/split-command-optional`; `handlers/skills/parse-long*`; `agent.federation.http` 11-line facade (1 src consumer).
- **Chat**: `:stream?`/`:stream-state` fields on active-turn (never read); `persist-final-assistant!` (pure alias of `persist-completion!`).
- **Config**: `:iris/context-files` knob (written into every generated config, read by nothing); `default-markdown-content` fallback map (classpath resource always wins; has drifted).
- **Tools**: `tools.core/with-approval`, `read-only-call?` (test-only callers); `display/result-preview|block-preview` (+ private helpers); `params-preview`/`args-preview` alias pair; `:prerequisites` metadata on fs tools (no consumer).
- **Persistence facade**: `init-store!`, `default-busy-timeout-ms` re-export, `upsert-channel-session-mapping!` wrapper.

## Duplication (cross-cutting)

- `sha256-hex` ×3 byte-identical (tools.core, tools.approvals, runtime.doom-loop).
- Canonical-JSON input encoding ×5 with 3 divergent semantics (see bug 4; federation.crypto's copy is wire-format and must stay frozen).
- `truncate` ×8 with drifted markers (some marker text is model-visible).
- `escape-html`(+truncated) ×3 (telegram.approvals, telegram.streaming, tools.display).
- `approval-expires-at` ×2 byte-identical (chat.turn, handlers.tool_approvals).
- Reasoning/thinking key extraction ×3 with drift (bug 3).
- `bounded-limit` re-implemented in events handler despite `sqlite.common` canonical (and the store clamps anyway).
- Tool-call accessors (name/input parsing) ×4 across runtime nss with semantic drift.
- Persistence: enum validator ×4, count-* fns ×8, append-message!/append-entry! :message branch copy-paste.
- Tool-registry construction: same 9 positional args spelled at 3 call sites (components.clj, system.clj soft + full reload); `create-tool-registry` has a 6-arity ladder.
- API: ~15 hand-rolled try/catch ladders re-encoding the central domain-error table; 20 hand-written serializer fns; 7 verbatim broker-subscription option maps; chat SSE state machine copy-pasted between handlers/chat.clj and handlers/ui.clj with drift.
- history.clj: activate/cancel queued-message pair and persist-user/persist-queued-user pair structurally identical; subscribers.clj has two byte-identical branches.
- Telegram: 3 parallel "run a chat turn and stream to Telegram" implementations; api.clj send variants copy-pasted; ~12 hand-rolled `(or (:x-fn opts) default)` test seams.

## Structural issues (larger surgery, higher risk)

- `agent.telemetry` (513 LOC) mixes observer protocol + metrics collector + actual LLM invocation (`complete-with-telemetry!` calls `llm.core/invoke`) — layering inversion pulling telemetry → llm/runtime deps.
- `system.clj full-reload-now!` builds a complete system, then discards and rebuilds parts (tool registry ×2, telegram service ×2, health marks on a discarded registry, hardcoded re-mark doseq).
- `runtime.loop/run!` is ~340 lines, 9 terminal branches, 8 levels deep (but has 17 deftests — safe to decompose).
- `chat.turn/run-turn!` wires 10 concerns in one fn (facade-level coverage only — needs characterization tests first).
- Two parallel compaction engines (entry-level `runtime.compaction` vs message-level `runtime.context-pack`) with duplicated thresholds.
- Both LLM providers triplicate endpoint/body/parse dispatch across complete/stream/invoke; `complete` is production-dead (everything routes through `invoke`).
- `handlers/ui.clj` (564 LOC) duplicates approval flows and the chat SSE state machine; bypasses tool-service execution and overrides permissions.
- Orchestrator (1046 LOC, atom-backed, env-gated experimental): lost-update race after long LLM calls; in-memory federation result handlers vs durable outbox (stuck `queued` after restart); unbounded growth. *Explicitly experimental; documented as not durable.*
- Sessions table vs session_entries payload: dual source of truth for message bodies, reconciled at read time.
- MCP client subsystem (`agent.mcp.core`) is production-unwired (test-only except one envelope helper).

Full structured data (all 150 issues / 140 opportunities with file:line evidence): `target/review-result.json` (untracked working artifact).
