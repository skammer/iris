# Refactoring Plan & Findings — June 2026

Deep review of the iris codebase (16-agent sweep over all 147 src namespaces, 2026-06-09), followed by a phased, behavior-preserving refactor. Living copies with wiki-links live in the Obsidian vault: `obsidian/architecture/codebase-map.md`, `obsidian/decision-logs/refactoring-2026-06-findings.md`, `obsidian/decision-logs/refactoring-2026-06-plan.md`.

Baseline before refactor: 500 tests / 1859 assertions, 1 pre-existing flaky failure (`agent.chat-harness-test/harness-cancels-active-session-and-clears-working-state-test`, timing-sensitive). clj-kondo: 99 errors (all HugSQL `*-sqlvec` false positives) + 23 warnings.

---

## Part 1 — Findings

### Myths dispelled

- **No dead namespaces.** All 147 src namespaces are reachable from `agent.core` via static requires.
- **`agent.loop` is not a legacy `agent.runtime.loop`.** It's the `/loop` self-iteration command — a name collision, not duplication.
- **`agent.chat` / `agent.kernel` / `agent.telegram` top namespaces are thin facades**, not superseded copies of their directories.
- **System-map threading is clean** — explicit construction + system-ref re-deref; only 4 true global atoms (`agent.loop/loop-states`, `agent.logging/publisher-state`, `agent.nrepl/current-system`, `agent.ui.render` trusted-fragment registry).
- **Persistence facade discipline holds** — 20+ consumers all go through `agent.persistence.sqlite`, except a handful of API/chat call sites.
- **core.async: zero go blocks** (all real threads) — no blocking-in-go class of bugs.

### Real bugs found

1. **http.clj misplaced paren** (`tools/common/http.clj:166`) — `)` closed `create-tool-description` early, so `:operation`, `:approval-sensitive?`, `:action-key`, `:read-only-actions`, `:parallel-safe-actions` became ignored keys of `create-tool`. The `:parallel-safe-read-methods?` config knob was completely dead.
2. **Telegram poison update = head-of-line blocking forever** (`telegram.clj`) — a failing update was marked `:failed` in channel_inbox but the poll offset never advanced; the same update refetched indefinitely, halting the whole inbound queue. Concrete trigger: double-tap Deny on an approval card (`:approval-decision-conflict` unhandled on the deny path).
3. **Reasoning-key drift loses thinking blocks** — `llm.messages/response-thinking` checked `:thinking/:reasoning-content/:reasoning_content` but not `:reasoning`; the streaming path checked all four. Non-streaming responses carrying only `:reasoning` silently lost thinking.
4. **`cheshire {:canonical true}` is a no-op** — `runtime/loop.clj` and `tools/core.clj` relied on it for canonical input hashing; the same logical input could hash differently across doom-loop dedupe, approval input-hash, and loop approval alignment (approvals.clj normalized nils/keywords first, the others didn't).
5. **`run-chat-events!` can hang forever** (telegram) if `chat/run!` throws before the runtime loop emits agent-end, or the terminal broker event is dropped (sliding buffer).
6. **`api.validation/emit-system-event!` fallback bypassed the broker** — events written via the `(:store system)` fallback never reached SSE subscribers.
7. **Malli schema recompiled on every runtime event** including every streaming delta (`runtime/schema.clj validate!`) — hot-path waste.
8. **Test-runner drift**: `agent.cli-test` and `agent.api.event-compat-test` existed on disk but were not in the hardcoded runner list — they never ran in CI.

### Dead code (verified by grep, callers checked)

- **LLM (~250 LOC)**: `llm.service/complete|stream|embed`; clojure.spec block in `llm.core`; `ILLMProviderWithConfig` protocol + impls; `get-metrics` placeholders; registry `:provider-health` + no-op re-assocs; assorted unused arities; `:stream-events` accumulation; `messages/provider-tool-result->internal`.
- **Entry/system (~70 LOC)**: `health/register!`, `system.events/subscribe-events|unsubscribe-events|list-events`, `telemetry/observer-event-types|observer-metric-types`, `system/reload-status` + `:reload-status`, unused arities of `create-recorded-event-sink`/`create-orchestrator`.
- **API**: `agent.api.validation` deletable entirely (duplicated `sessions.service` + `system.events`, plus broker-bypass fallback); `helpers/body-value`; hand-rolled urlencoded parsing (ring.util.codec on classpath); `handlers/tools/split-command-optional`; `handlers/skills/parse-long*`; `handlers/runs` wrappers; `agent.federation.http` 11-line facade.
- **Chat**: `:stream?`/`:stream-state` fields on active-turn (never read); `persist-final-assistant!` (pure alias).
- **Config**: `:iris/context-files` knob (written into every generated config, read by nothing); `default-markdown-content` fallback (classpath resource always wins; had drifted).
- **Tools**: `tools.core/with-approval`, `read-only-call?` (test-only callers); `display/result-preview|block-preview`; `params-preview`/`args-preview` alias pair; `:prerequisites` metadata on fs tools (no consumer).
- **Persistence facade**: `init-store!`, `default-busy-timeout-ms` re-export, `upsert-channel-session-mapping!` wrapper.

### Duplication (cross-cutting)

- `sha256-hex` ×3 byte-identical (tools.core, tools.approvals, runtime.doom-loop).
- Canonical-JSON input encoding ×5 with 3 divergent semantics (bug 4; federation.crypto's copy is wire-format and stays frozen).
- `truncate` ×8 with drifted markers (some marker text is model-visible).
- `escape-html`(+truncated) ×3 (telegram.approvals, telegram.streaming, tools.display).
- `approval-expires-at` ×2 byte-identical (chat.turn, handlers.tool_approvals) + a near-miss in runs.registry.
- Reasoning/thinking key extraction ×3 with drift (bug 3).
- `bounded-limit` re-implemented in events handler despite `sqlite.common` canonical.
- Tool-call accessors (name/input parsing) ×4 across runtime namespaces with semantic drift.
- Persistence: enum validator ×4, count-* fns ×8 (9 with the bonus), idempotent-insert pattern ×4 in runs.clj, append-message!/append-entry! copy-paste.
- Tool-registry construction: same 9 positional args spelled at 3 call sites; `create-tool-registry` 6-arity ladder.
- API: ~15 hand-rolled try/catch ladders re-encoding the central domain-error table; 20 hand-written serializer fns; 7 verbatim broker-subscription option maps; chat SSE state machine copy-pasted between handlers/chat.clj and handlers/ui.clj with drift.
- history.clj activate/cancel + persist-user/persist-queued pairs; subscribers.clj two byte-identical branches.
- Telegram: 3 parallel "run a chat turn and stream to Telegram" implementations; api.clj send variants copy-pasted; ~12 hand-rolled `(or (:x-fn opts) default)` test seams.

### Structural issues (larger surgery, mostly deferred)

- `agent.telemetry` (513 LOC) mixes observer protocol + metrics collector + actual LLM invocation — layering inversion.
- `system.clj full-reload-now!` builds a complete system, then discards and rebuilds parts (tool registry ×2, telegram service ×2, health re-marks).
- `runtime.loop/run!` ~340 lines, 9 terminal branches (well-tested → decomposable).
- `chat.turn/run-turn!` wires 10 concerns (facade-only coverage — needs characterization tests first).
- Two parallel compaction engines (`runtime.compaction` vs `runtime.context-pack`) with duplicated thresholds.
- Both LLM providers triplicate endpoint/body/parse dispatch across complete/stream/invoke; `complete` is production-dead.
- `handlers/ui.clj` (564 LOC) duplicates approval flows and the chat SSE state machine; bypasses tool-service execution.
- Orchestrator (1046 LOC, atom-backed, env-gated experimental): lost-update race; in-memory federation result handlers vs durable outbox; unbounded growth.
- messages table vs session_entries payload: dual source of truth, reconciled at read time.
- MCP client subsystem production-unwired (test-only except one envelope helper).

### Test landscape

- Strong coverage (safe to refactor): `runtime.loop` (17 deftests + e2e), `llm.providers.*` (39 deftests), `persistence.sqlite` facade, `chat` facade (44 deftests via fake provider), `telegram` adapter.
- Facade-only coverage (refactor behind facade only): `chat.*` internals, sqlite sub-namespaces, api handlers.
- Dark spots: `agent.security`, `agent.util`, `runtime.tokens`, `streaming.metrics`, `telegram.{commands,media,sessions}`, `federation.{auth,crypto}` (only via facade).
- Brittle: sleep-based sync in telegram/logging/chat tests; config isolation depends on the runner's bindings.

---

## Part 2 — The plan and its status

### Principles

1. **Behavior-preserving by default.** Every phase ends green on the full suite.
2. **Prefer deletion.** Dead code first; it shrinks every later diff.
3. **Respect coverage boundaries.** Facade-only internals get refactored behind their facades; dark namespaces don't get reshaped without characterization tests.
4. **One commit per coherent step**, full test run before each.

### Phase 1 — Bug fixes ✅ (committed `70b466f`)

- ☑ 1.1 http.clj paren fix — the 5 execution-safety options now reach `create-tool-description`.
- ☑ 1.2 Telegram poison update: offset advances past failed updates; deny double-tap answers "Already denied."
- ☑ 1.3 `response-thinking` reads `:reasoning` (+ raw fallbacks).
- ☑ 1.4 Orphaned `agent.cli-test` + `agent.api.event-compat-test` added to the runner (suite: 500 → 512 tests).
- ☑ 1.5 Events handler `bounded-limit` copy deleted; store owns clamping.

### Phase 2 — Dead code deletion ✅ (committed `927d861`, −394 LOC net)

- ☑ 2.1 LLM batch (kept `complete`/`stream` protocol methods for Phase 5).
- ☑ 2.2 `agent.api.validation` deleted; `ensure-session-exists!` → api.helpers over sessions.service; event emission → `system.events/log-event!`.
- ☑ 2.3 `agent.federation.http` facade deleted.
- ☑ 2.4 Entry/system dead fns deleted.
- ☑ 2.5 API handlers dead code; form parsing via ring.util.codec.
- ☑ 2.6 Chat dead surface.
- ☑ 2.7 Config `:iris/context-files` + dead markdown fallback removed.
- ☑ 2.8 Tools/display dead fns (`args-preview` is canonical).
- ☑ 2.9 Persistence facade dead surface.

### Phase 3 — Cross-cutting consolidation ✅ (this commit)

- ☑ 3.1 `agent.security/sha256-hex` — single digest helper (3 copies deleted).
- ☑ 3.2 `agent.security/canonical-json` — one true canonicalization (recursive key sort, keywords→names, nil-map-values dropped). `tools.approvals/input-hash`, `runtime.loop` approval alignment, and `tools.core` activity names now share one fingerprint. doom-loop keeps its richer typed pr-str canonicalization (internal dedupe), but uses the shared digest. federation.crypto stays frozen (wire format). Fixes bug 4. Note: in-flight approvals minted before this change hash-mismatch; TTL is 900s, impact nil.
- ☑ 3.3 `agent.util/truncate` with marker-fn; 7 call sites consolidated, each preserving its exact marker text (model-visible).
- ☑ 3.4 HTML escaping unified on `tools.display` (`escape-html`, `escape-html-truncated`); telegram copies deleted.
- ☑ 3.5 `tools.approvals/default-expires-at` — single home for approval TTL (3 sites).
- ☑ 3.6 `llm.messages/reasoning-text` — single extractor (providers.common and llm.core were require-cycles; messages is the cycle-free home all three sites reach).
- ☑ 3.7 Persistence: `common/valid-enum!` (4 copies), `common/count-rows` (9 copies), `sqlite.schema` ns dissolved (count-sessions → sessions, health-check → facade).
- ☑ 3.8 `agent.runtime.calls` — shared tool-call accessors (`tool-name-of`, `call-id`, `call-input` with malformed sentinel, `fs-mutation-tools`); duplicates in nudge/runtime.tools deleted.
- ☑ 3.9 Malli validators precompiled in `runtime.schema` (per-streaming-delta hot path).

### Phase 4 — Construction & error-path cleanup ✅ (commits `1cc39b6` + `fe4c779`)

- ☑ 4.1 `create-tool-registry` single options-map arity; `components/build-tool-registry` is the one construction site.
- ☑ 4.2 `safe-stop!` unifies teardown semantics; attach-telegram-service single copy; :chat in health defaults.
- ☑ 4.3 Full reload builds via `create-system-components` with live refs injected; build-discard-rebuild deleted.
- ☑ 4.4 `api.errors` table-driven; boundary translates domain errors centrally; equivalent handler catch ladders removed (custom ones kept).

### Phase 5 — Targeted structural wins (as budget allows)

- ☑ 5.1 history.clj copy-paste pairs + subscribers.clj identical branches (commit `21375a9`).
- ☑ 5.2 Terminal emitters extracted from `runtime.loop/run!` (commit `3dc4eec`); deeper plan-phase extraction left for a focused follow-up.
- ☐ 5.3 LLM providers: `invoke` as the single execution path; `complete`/`stream` become wrappers.
- ☐ 5.4 Telegram: delete the callback streaming path; single broker-event chat runner.

### Explicitly dropped (this round) — with reasons

- ✗ **MCP delete-or-wire**: product decision; left unwired, documented.
- ✗ **Orchestrator races/durability**: env-gated experimental, documented as non-durable; needs its own effort.
- ✗ **session_entries as single source of truth for messages**: data migration, needs its own design note.
- ✗ **Ragtime → hand-rolled migration runner**: working code, low pain.
- ✗ **`chat.turn/run-turn!` decomposition**: facade-only coverage; characterization tests first.
- ✗ **Telemetry 3-way split**: high blast radius; deferred to its own pass.
- ✗ **Serializer/handler-map table-driving, route-coerced body adoption**: mechanical-large; queued.
- ✗ **Skills registry caching**: behavior change; needs a staleness-tolerance decision.

### Verification protocol

Full suite after every phase:

```bash
env IRIS_CONFIG_DIR=target/test-iris-config IRIS_DATA_DIR=target/test-iris-data \
  clojure -J-Djava.io.tmpdir=$PWD/target/test-tmp -M:test \
  -e "(require 'agent.test-runner) (agent.test-runner/run-all-tests) (shutdown-agents)"
```

Expected: 0 new failures vs baseline. clj-kondo must not regress (modulo deleted-code warnings disappearing).
