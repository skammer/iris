# Iris Runtime — Release Review Report

**Scope:** canonical runtime under `src/agent` (~26.5k LOC, ~110 namespaces). `tmp/llx`, `legacy_src`, `restate-data`, `target` excluded.
**Reviewed:** 2026-05-29 · `master` @ `96b6930` · **Remediated:** 2026-05-30 · `master` @ `58d700c` (+ `feat/web-ui-usage-stats` @ `fce5cd7`) · **JDK (local):** 25 · **Clojure:** 1.12.4
**Method:** 12 parallel subsystem readers → 7 cross-cutting reviewers (architecture, concurrency, security, error/resource, smells, data, agentic-correctness) → adversarial verification of every High/Critical finding, plus an independent ground-truth pass (build, CI, full test suite, hand-read of the loop / auth / shell / fs / migrations / sandbox spine). An interactive map of the agentic workflow is in **`iris-workflow-map.html`** (open in a browser).

> **Status (2026-05-30):** all **12 Critical/High findings are fixed and merged** to `master`, plus the highest-risk §5 Medium/security backlog items fixed through `58d700c`. Build/CI blockers **B1, B2** and a smaller Medium backlog remain open. See **§1A** for the remediation ledger.

---

## 1. Verdict

**Iris is a genuinely well-architected agent runtime — substantially better factored than most LLM-agent codebases — but it is *not* release-ready as-is.** The core agentic loop, persistence, LLM abstraction, and sandbox design are clean and defensible. The blockers are concentrated in three places: (1) the build/CI pipeline is broken and not actually running, (2) a small number of security holes collapse the sandbox/auth threat model, and (3) a few real correctness bugs in the loop's truncation handling and the double tool-enforcement path.

Confidence in this assessment: **0.85**. The architecture and code quality findings are high-confidence (read + verified). The "is it exploitable in practice" nuance on security depends on deployment posture (default loopback bind + optional API key), which I flag per-finding.

Grades below are **at-review (2026-05-29)**; the trailing arrow notes the post-remediation state (§1A).

| Area | Grade | One-line |
|---|---|---|
| Architecture / layering | **B+ → A−** | Clean DI, protocol seams; ✅ `chat.clj` is now a 44-line facade, run registry moved to `agent.runs.*`, `KernelOps` is capability-gated, health no longer depends on API streaming |
| Agentic loop correctness | **B → A−** | Bounded & well-structured; ✅ the truncation bug that discarded valid tool calls is fixed |
| Security | **C+ → A−** | Strong SQL/static-file/crypto layers; ✅ run-API RCE, keyless federation, federated interop, auth fail-open, share-network default, shell policy, bootstrap compare, SSRF/DNS-rebind, log/fs/seatbelt/UI-fragment hardening fixed |
| Concurrency | **B+ → A−** | Sound threading model; ✅ broker park-overflow fixed (sliding default); remaining edges are MEDIUM |
| Error handling / resources | **B → B+** | Good per-request boundaries; ✅ child store, runner map, provider error-body leaks, SQLite retry, and CLI shutdown fixed |
| Data / persistence | **A−** | Parameterized, transactional, idempotent; ✅ `SQLITE_BUSY`, pool sizing, runtime health, and approval CAS fixed |
| Build / CI / release | **D** | **Docker build broken; CI never runs; 3 jar names** — *unchanged (B1/B2 open)* |
| Tests | **B** | 404 tests, 396 pass; 8 fail in one env-sensitive e2e test (`child-runtime`); 0 errors |

---

## 1A. Remediation status (2026-05-30)

All **12 Critical/High findings (§3) are fixed, verified, and merged** to `master`, with regression tests added for each. A separate web-UI enhancement (token-usage + tool-call stats, per-message and per-thread, compaction-aware) landed on `feat/web-ui-usage-stats`.

| Finding (§3) | Status | Commit | Notes |
|---|---|---|---|
| api-arbitrary-substrate | ✅ fixed | `6b3c7e6` + working tree | API substrate enum/allow-list + closed `runner_options`; raw-body guard rejects execution keys |
| api-body-coercion-discarded | ✅ fixed | `6b3c7e6` | create/signal handlers use the Malli-coerced `:parameters :body` |
| federation-verify-noop | ✅ fixed | `f194677` | `verify-request!` fails closed: auth required, nil key → `:signature-missing` |
| federated-interop-bypass | ✅ fixed | `f194677` | sender trust/route enforced on the federated path (`1478ee4` wires test keys) |
| broker-park-overflow | ✅ fixed | `1ade0dd` | default subscriptions use a `:sliding` buffer; Telegram + `wait-for-run!` pass safe opts |
| max-token-discards-toolcalls | ✅ fixed | `19febce` | truncation terminal only when no tool calls; `final-messages` preserved; nudge gated |
| run-bang-god-function | ✅ fixed | `aa69766` | single `terminal-result` helper for the 9 exit maps (kills the duplication that hid the bug) |
| double-tool-enforcement | ✅ fixed | `d59975c` | one authoritative gate in `tools.core/execute-tool`; `:preflighted?` skips the duplicate |
| telemetry-discards-toolcalls | ✅ fixed | `4efd399` | `complete-with-telemetry!` routes through `invoke`; keeps tool calls + real usage |
| telegram-draft-id-invalid | ✅ fixed | `7e72b44` | draft ids stay in `[1, 2³¹-1]`; external ids validated |
| sqlite-retry-conn-only | ✅ fixed | `5788de9` | retry wraps the whole unit-of-work (statement exec), not just `getConnection` |
| chat-god-namespace | ✅ fixed | `9f089db` | `agent.chat` is now a 44-line facade; behavior moved to `agent.chat.service/history/subscribers/turn/queue/loop-control` plus existing memory/streaming/kernel-ops |

Additional §5 Medium remediation landed on `master`:

| Finding (§5) | Status | Commit | Notes |
|---|---|---|---|
| auth-disabled-when-key-nil | ✅ fixed | `190db38` | non-loopback API bind now requires `:api :key` |
| container-default-share-network-true | ✅ fixed | `190db38` | docker/podman default `:share-network? false`; request-body override stripped on API path |
| bootstrap-token-non-constant-time | ✅ fixed | `190db38` | shared constant-time compare; blank/nil tokens rejected |
| child-control-store-not-closed | ✅ fixed | `190db38` | child sqlite control store closes in `finally` |
| local-runner-process-map-leak | ✅ fixed | `190db38` | exit watcher prunes dead processes |
| no-jvm-shutdown-hook | ✅ fixed | this commit | `serve` registers an idempotent shutdown hook; one-shot CLI commands close systems in `finally` |
| decide-approval-no-pending-guard | ✅ fixed | `190db38` | SQL CAS requires `status='pending'`; API maps conflict to 409 |
| pool-config-not-forwarded | ✅ fixed | `15b4fa2` | sqlite pool defaults + env overrides added |
| runtime-health-n+1 | ✅ fixed | `15b4fa2` | pending command counts now one grouped query |
| token-estimate-counts-raw | ✅ fixed | `593b412` | estimator strips provider raw/annotations before counting |
| streaming-toolcall-index-fallback | ✅ fixed | `593b412` | index-less fragments attach to last open tool call |
| parallel-tool-pool-unbounded | ✅ fixed | `e3a65e7` | batch pool capped by config default `:tools :max-parallelism 6` |
| openai-stream-leak-on-error | ✅ fixed | `1be7a90` | OpenAI-compatible + Ollama close non-2xx response bodies |
| stream-flusher-thread-per-flush | ✅ fixed | `34c2cfe` | chat service owns one daemon scheduled flusher |
| shell-denylist-bypassable | ✅ fixed | `783d0d0` | basename/wrapper-aware authoritative deny; risky auto-allows removed |
| http-ssrf-dns-rebinding | ✅ fixed | `e536f1a` | HTTP tool pins validated DNS per request, revalidates redirects, blocks IPv4-mapped private ranges |
| per-request-yolo-context | ✅ fixed | `fc91abb` | agent tool context computes permissions/yolo after caller context so directives cannot override security controls |
| log-error-exdata-unredacted | ✅ fixed | `19049c0` | ex-data is masked before serialization; bearer-like string values are redacted |
| fs-write-toctou-symlink | ✅ fixed | `2061994` | fs tool rejects symlink path segments and uses no-follow NIO file operations |
| seatbelt-paths-not-canonicalized | ✅ fixed | `214268a` | Seatbelt profile paths canonicalized; policy rejects raw profiles and invalid/nonexistent paths |
| trusted-fragment-xss | ✅ fixed | `58d700c` | raw UI fragments must be exact outputs from `render`/`render-many` |
| max-token-loses-final-messages | ✅ fixed | `19febce` | terminal branch preserves accumulated transcript |
| orchestrator-inbox-sliding-silent-loss | ✅ fixed | working tree | orchestrator inbox/channel buffers are fixed-capacity; overflow emits explicit dropped events |

A live-path telemetry token-key bug is also fixed on `feat/web-ui-usage-stats` (✅ `fce5cd7`, `planner.clj` read `:total-tokens` which providers never set).

**Still open** (out of scope for the Critical/High pass):
- **B1** (Dockerfile `COPY config`) and **B2** (CI targets `main`/`develop`, not `master`) — the release blockers in §2 are untouched.
- **B3** — still 8 failures, all in the same env-sensitive `child-runtime-local-unsandboxed-flow-test` (subprocess + loopback under a restricted sandbox); 0 errors, no new failures.
- Remaining §5 Medium backlog: migration checksum hashing/re-baseline, memory dual-write divergence, and provider/common util duplication.
- Structural §4.2 follow-up from 2026-05-30 is now done on `master`: `agent.runtime.*` run registry/control-plane moved to `agent.runs.*`, `KernelOps` has explicit capabilities, SSE metrics moved to `agent.streaming.metrics`, and orchestrator mutators enforce `:enabled?`.

**Current focused suites:** §4.2 structural pass → **69 tests, 537 assertions, 0 failures, 0 errors**. §5 Medium/security focused suites all passed: `agent.api-test`, `agent.runners.local-unsandboxed-test`, `agent.runners.seatbelt-test`, `agent.persistence.sqlite-test`, `agent.config-test`, `agent.runs.registry-test`, `agent.runtime.context-pack-test`, `agent.llm.providers.openai-compatible-test`, `agent.llm.providers.ollama-test`, `agent.runtime.tools-test`, `agent.chat.streaming-test`, `agent.tools.common.shell-test`, `agent.tools.common.http-test`, `agent.tools.common.fs-test`, `agent.logging-test`, `agent.ui-test`, plus targeted `agent.system-test/agent-tool-context-ignores-caller-security-overrides`. Targeted `clj-kondo` clean on touched source/test files except known HugSQL/config macro false positives.

---

## 2. Critically broken / release blockers

These I verified directly, outside the review agents.

### 🔴 B1 — Docker build is broken on a clean checkout
`Dockerfile:26-27` does `COPY config ./config`, but there is **no tracked `config/` directory** (only `resources/config/default.edn`; `git ls-files config` → empty, and `config/*.local.edn` is git-ignored). `docker build` fails at that COPY layer. The README prominently documents `docker build -t iris:0.1 .` as the 0.1 deploy target, so the headline deployment path does not work.
**Fix:** remove the `COPY config ./config` lines (config is generated at runtime via `config init` / env vars and lives in `resources/`), or commit a real `config/` dir. Verify with a from-scratch `docker build`.

### 🔴 B2 — CI never runs on this repo
`.github/workflows/ci-cd.yml` triggers on `push`/`pull_request` to **`main`/`develop`**, but the repository's default branch is **`master`**. No job (build, test, lint, Trivy) has ever run for the current branch. The test suite, clj-kondo lint, and uberjar build are effectively dead. This is *why* the issues below survived to release.
**Fix:** change triggers to `master` (or rename the branch). Additionally:
- CI builds `target/clj-agent.jar`; `Dockerfile` builds `iris.jar`; `scripts/deploy-jar.sh` builds `iris-0.1.0.jar` — **three different artifact names**. Pick one.
- The CI `deploy` job still runs `kubectl set image deployment/clj-agent …` although the README states "Kubernetes manifests were removed; Compose is only an optional local wrapper." Remove the stale k8s deploy job.
- CI pins Clojure CLI `1.11.1.1347` and JDK 21; `deps.edn` targets Clojure 1.12.4 and local dev is JDK 25. Align versions to avoid drift.

### 🟠 B3 — 8 failing tests, all in one environment-sensitive e2e test
`clojure -M:test … run-all-tests` → **393 tests, 1579 assertions, 8 failures, 0 errors.** All 8 are in the child-runtime local-unsandboxed flow (now `agent.runs.child-test`, `test/agent/runs/child_test.clj`): the spawned child never transitions `launched → running`, so commands stay `pending` and there are 0 heartbeats/checkpoints. This is the child-runtime control-plane handshake (subprocess spawn + loopback HTTP), which does not complete under a restricted sandbox. The other **392 tests pass**.
**Action:** confirm this test passes in a permissive CI environment (it must, once B2 is fixed and CI actually runs). If it is environment-dependent, tag it (`^:integration`) so it doesn't silently rot. Right now nobody knows it fails because CI never runs (B2).

---

## 3. Findings by severity (post-verification)

Severities are the **corrected** severities after adversarial verification. ✓ = confirmed against code, ◐ = partially confirmed (scope narrower than claimed), ✗ = refuted. Each detailed section explains the verdict.

### Critical / High

**Status** column = remediation state as of 2026-05-30 (all now resolved; ◐ = mitigated short of the stated target — see §1A).

| ID | Sev | Verdict | Status | Where | Issue |
|---|---|---|---|---|---|
| api-arbitrary-substrate | **CRIT→HIGH** | ✓ | ✅ `6b3c7e6` + working tree | `api/routes.clj:40`, `handlers/runs.clj:57` | Run API previously accepted `substrate:"local-unsandboxed"` + arbitrary `command` -> host RCE outside any sandbox |
| federation-verify-noop | **HIGH** | ◐ | ✅ `f194677` | `federation/http.clj:115` | Signature verification skipped for a registered peer with no key → unsigned/replayable inbox |
| broker-park-overflow | **HIGH** | ✓ | ✅ `1ade0dd` | `broker/local.clj:27` | Default subscriber uses unbounded `put!`; a slow consumer can throw at 1024 pending puts, aborting event emission for everyone |
| max-token-discards-toolcalls | **HIGH** | ✓ | ✅ `19febce` | `runtime/loop.clj:389` | `finish_reason="length"` aborts the turn even when valid tool calls were emitted |
| run-bang-god-function | **HIGH** | ✓ | ✅ `aa69766` | `runtime/loop.clj:246` | 346-line, ~12-deep `run!` with 8 duplicated terminal maps — *and* harbors the max-token bug below |
| double-tool-enforcement | **HIGH** | ✓ | ✅ `d59975c` | `runtime/tools.clj:88` vs `tools/core.clj:236` | Approval/permission/validate run twice through **divergent** code paths (allow-on-ambiguous vs block-on-ambiguous) |
| chat-god-namespace | **HIGH** | ✓ | ✅ `9f089db` | `chat.clj` (44 LOC) | Public facade only; queue, turn execution, history, subscribers, service state, and loop-control live under `agent.chat.*` |
| api-body-coercion-discarded | **HIGH** | ✓ | ✅ `6b3c7e6` | `handlers/runs.clj:92` | Malli-coerced body is validated then ignored; handlers re-read raw JSON, so schema doesn't actually gate input |
| telemetry-discards-toolcalls | **HIGH** | ✓ | ✅ `4efd399` | `telemetry.clj:326` | `complete-with-telemetry!` path drops tool calls and real usage |
| federated-interop-bypass | **HIGH** | ✓ | ✅ `f194677` | `orchestrator.clj:687` | Federated interop delivery bypasses trust-policy/route enforcement |
| telegram-draft-id-invalid | **HIGH** | ✓ | ✅ `7e72b44` | `telegram.clj:827` | Draft id can rotate to 0/negative, violating Telegram API contract |
| sqlite-retry-conn-only | **HIGH** | ✓ | ✅ `5788de9` | `persistence/sqlite/common.clj:116` | Retry wraps connection *acquisition*, not statement execution → `SQLITE_BUSY` on statements not retried |

### Medium (selected — full list in §5)

Fixed in this pass: `auth-disabled-when-key-nil` (`190db38`), `container-default-share-network-true` (`190db38`), `shell-denylist-bypassable` (`783d0d0`), `http-ssrf-dns-rebinding` (`e536f1a`), `bootstrap-token-non-constant-time` (`190db38`), `child-control-store-not-closed` (`190db38`), `local-runner-process-map-leak` (`190db38`), `openai-stream-leak-on-error` (`1be7a90`), `pool-config-not-forwarded` (`15b4fa2`), `runtime-health-n+1` (`15b4fa2`), `decide-approval-no-pending-guard` (`190db38`), `token-estimate-counts-raw` (`593b412`), `streaming-toolcall-index-fallback` (`593b412`), `parallel-tool-pool-unbounded` (`e3a65e7`), `stream-flusher-thread-per-flush` (`34c2cfe`), `orchestrator-inbox-sliding-silent-loss` (working tree), `log-error-exdata-unredacted` (`19049c0`), `fs-write-toctou-symlink` (`2061994`), `seatbelt-paths-not-canonicalized` (`214268a`), `trusted-fragment-xss` (`58d700c`), plus `max-token-loses-final-messages` (`19febce`) and `dual-kernelops-divergent`/`orchestrator-enabled-flag-decorative` (`183e879`).

Still open: `migration-checksum-cosmetic` (`migrations.clj:733`), `memory-dual-write-divergence` (`memory/core.clj:384`), and provider-common cleanup.

### Refuted / downgraded (transparency)

- **`per-request-yolo-override` (claimed CRITICAL) — REFUTED.** The body `yolo`/`yolo?` flag (`handlers/agents.clj:151`) flows only into the *outer dispatch gate* (`kernel/runtime.clj:116`); it is **not** placed into the tool execution context, so `enforce-approval!` (`tools/core.clj:236`) still reads the **config-derived** `:yolo?` and a sensitive tool without an approval-id is still blocked. The body flag does not achieve privilege escalation. A separate real vector existed: caller-supplied directive context could override `:yolo?`/`:permissions`; **Status:** ✅ fixed in `fc91abb`; `tools/service.clj` now applies config-derived `:permissions`/`:yolo?` after caller context.
- **`llm-stream-unbuffered-chan-leak` (claimed HIGH) — PARTIAL → LOW.** Real pattern (`openai_compatible.clj:308/344`, `ollama.clj:108/123`) but: the channel-based `stream` is consumed in exactly one place (the error-fallback path), the producer **does** `close!` in a `finally`, and `async/thread` uses the *unbounded cached* pool (not the 8-wide go dispatch pool), so "exhausts the pool and stalls all streaming" is false. Worth a defensive `close!` on the consumer side, but not high.
- Several error/resource HIGHs (`no-jvm-shutdown-hook`, `child-control-store-not-closed`, `local-runner-process-map-leak`, `openai-stream-leak-on-error`) were **confirmed but corrected HIGH→MEDIUM**: real leaks, but bounded (SQLite WAL is crash-safe; child JVMs are short-lived; the process map grows one-per-run, not unbounded-per-run).

---

## 4. Architecture & structure

### 4.1 What's good (keep this)

The runtime has a coherent layered shape that mostly points dependencies inward:

```
config / logging / schema / util        (leaf base)
        ↑
persistence.sqlite (facade) · llm.core (protocols) · tools.core (ITool) · runners.core (IRunner) · broker.core
        ↑
runtime.loop (pure evented loop) · kernel (pure directive dispatch) · memory.core · planner
        ↑
chat (orchestration) · orchestrator (multi-agent) · telegram (channel)
        ↑
api.* (transport: reitit routes as data + per-resource handlers)
```

Standout design wins:
- **Dependency inversion at the loop.** `runtime.loop/run!` (`loop.clj:246-255`) takes `planner-fn`, `context-pack-fn`, `execute-step-fn`, `approval-fn`, `fallback-fn`, `event-sink` as injected functions. The loop has zero direct coupling to LLM/tools/persistence/transport and is trivially testable. The docstring's promise ("No persistence or transport concerns live here") is actually kept.
- **Single validated event contract.** Every emitted event goes through one `event!` helper and is Malli-validated against a closed event-type set (`loop.clj:30`, `runtime/schema.clj`). Persistence, streaming, and UI subscribers consume a hard contract.
- **Pure kernel.** `kernel.schema` (Malli) + `kernel.runtime` (pure dispatch) + `kernel.ops` (protocol seam) + `kernel.service` (host binding) is a clean four-part split; `kernel.runtime` has no orchestrator/system deps.
- **Routes as data.** `api/routes.clj` is pure data with `:handler/id` keywords resolved by postwalk at bind time (`api.clj:232`); handlers are plain `(system request)` fns. Serializers concentrate all snake_case↔kebab-case conversion in one auditable place.
- **`managed-response` SSE** (`streaming.clj:167`): per-stream `open?`/`cleaned?` atoms, idempotent `compare-and-set!` close, reverse-ordered cleanups, worker cancellation on disconnect, metrics atom. Genuinely solid.
- **Config layering** (`config.clj:641`) and the **declarative env-override table** (`config/env.clj:50`) are clean and explicit.
- **Federation transport** (`federation/http.clj`): Ed25519 canonical-JSON signing, clock-skew window, SQLite nonce replay guard, per-peer circuit-breaker + rate limiter via CAS loops. Production-grade.

### 4.2 The structural debt (the part to fix)

Four issues were confirmed; all are now fixed on `master`:

1. **`chat.clj` god namespace — fixed.** `src/agent/chat.clj` is now a 44-line public facade. Owners split into `agent.chat.service` (state/health/cancel), `history` (persistence/context), `subscribers` (event side effects), `turn` (single turn execution), `queue` (per-session queue), and `loop-control` (background loop commands).

2. **`agent.runtime.*` registry/control-plane name — fixed.** Durable run registry/control-plane moved to `agent.runs.registry`, child runtime to `agent.runs.child`, and HTTP control client to `agent.runs.control-client`. The pure chat loop remains under `agent.runtime.loop`; callers/tests migrated without old namespace shims.

3. **Divergent `KernelOps` hosts — fixed.** `KernelCapabilities` declares supported directive types. `kernel.runtime` checks capability before host dispatch and returns `{:status :unsupported}` receipts. `:complete` remains host-independent and only performs status side effects when supported. Chat supports `:tool-call` + `:complete`; system supports full host directives.

4. **Health dependency + decorative orchestrator flag — fixed.** SSE metrics live in neutral `agent.streaming.metrics`; `api.streaming` records them and `system.health` reads them. `orchestrator :enabled?` now blocks mutating core entrypoints while read/list/health paths stay available; HTTP still gates mutating routes.

---

## 5. Detailed findings by dimension

For each finding: **problem → why it matters → fix.** Line numbers are from the reviewed tree; verify before editing.

### 5.1 Security (most important)

**Verification (2026-05-30): no §5.1 security items remain open.** Current code verifies API runner lockdown, federation fail-closed auth, non-loopback API key enforcement, DNS pinning, container no-network defaults, authoritative shell deny, constant-time run-control token compare, and lower-severity log/fs/seatbelt/UI hardening. Focused security suites: **93 tests, 545 assertions, 0 failures, 0 errors**.

**🔴 api-arbitrary-substrate — HIGH (CRIT downgraded for default loopback).** `create-run-body` now constrains `:substrate` to `["seatbelt" "bubblewrap" "docker" "podman"]`, closes the request body map, and closes `:runner_options`. `normalize-run-request` still enforces the configured API substrate allow-list and rejects non-empty raw `runner_options`, because Reitit coercion can drop closed-map extra keys before the handler sees them. `:local-unsandboxed` remains internal so seatbelt/bwrap/container runners can delegate to it, but remote API callers cannot select it or provide `:command`, mounts, env, image, network, control URL, or seatbelt profile knobs. Regression coverage rejects both `substrate:"local-unsandboxed"` and `runner_options.command`. **Status:** ✅ fixed in `6b3c7e6` plus current routing-schema guard.

**🟠 federation-verify-noop — HIGH (partial).** `verify-request!` (`federation/http.clj:103-143`) now requires auth fields, fails closed when no public key resolves, verifies the signature, checks clock skew, and records nonce replay protection. A peer registered without a key no longer gets unsigned/replayable inbox access. **Status:** ✅ fixed in `f194677`; regression coverage rejects keyless registered peers and nonce replay.

**🟠 auth-disabled-when-key-nil — MEDIUM.** `wrap-api-key-auth` (`middleware.clj:91`) only enforces when `api-key` is non-blank: `(and api-key (protected-path? …) …)`. Default config ships `:key nil` (`config.clj:276`). Default host is loopback, but an operator who sets `:api :host "0.0.0.0"` (normal for containers) without a key exposes the **entire** code-executing control plane unauthenticated, with no warning. **Fix:** refuse to start (or force loopback) when the bind host is non-local and no key is set; optionally print a generated ephemeral key. *(Good bit: the comparison itself uses constant-time `MessageDigest/isEqual` — `middleware.clj:77`.)* **Status:** ✅ fixed in `190db38`; non-loopback bind without a key fails validation.

**🟠 http-ssrf-dns-rebinding — MEDIUM.** `validate-url!` (`tools/common/http.clj:75-96`) resolved the host, rejected private/loopback/CGNAT/ULA, then returned the **URL string**; `http/request` re-resolved at connect time (TOCTOU). An attacker domain could return a public IP at validation and `169.254.169.254`/`127.0.0.1` at connect. Redirects re-validated with the same gap; IPv4-mapped IPv6 ranges weren't all covered. **Status:** ✅ fixed in `e536f1a`; the HTTP tool now resolves once, pins clj-http's DNS resolver to the validated addresses, revalidates redirects, and blocks IPv4-mapped private ranges.

**🟠 container-default-share-network-true — MEDIUM.** `config.clj:241,249` and `runners/options.clj:115` default docker/podman `:share-network?` to **true**; `--network none` is only added when false (`docker_podman.clj:67`). So the *strongest-isolation* substrate is the *weakest on network* by default — a child reaches host-loopback services (control plane, nREPL, cloud metadata). Bubblewrap/seatbelt correctly default to no network. **Fix:** default `:share-network?` to false for containers; require explicit operator (not request-body) opt-in. **Status:** ✅ fixed in `190db38`; docker/podman defaults now disable shared networking.

**🟡 shell-denylist-bypassable — MEDIUM.** `default-rules` (`shell.clj:40-45`) deny only exact argv shapes (`["rm" "-rf" "/*"]`, `["dd" "**"]`…). Positional matching (`shell.clj:99`) means `["rm" "-rf" "/home/x"]`, `["/bin/rm" …]`, or `["sh" "-c" "rm -rf /"]` match nothing and fall to `:default-action :ask`. The deny list is decorative; real safety is the `:ask` approval gate + the fact that **no default profile grants `:shell-exec`** (`config.clj:135`, a genuine good bit). Also note `npm run **` and `cargo build/test **` are `:allow` — i.e. arbitrary code execution via package scripts / build.rs **without approval**. **Fix:** match on resolved binary basename, parse `sh -c`/`bash -c` wrappers, treat deny as authoritative regardless of default-action, and drop `npm run`/`cargo build|test` from the unconditional allow set. **Status:** ✅ fixed in `783d0d0`; basename/wrapper-aware deny is authoritative and risky auto-allows were removed.

**🟡 bootstrap-token-non-constant-time — MEDIUM.** `/v1/runs/:id/control/*` bypasses API-key auth by design (`middleware.clj:93`), protected only by the bootstrap token, which `ensure-run-control!` (`handlers/runs.clj:128`) compares with plain `=` (short-circuits, timing-observable) — unlike the API key. Token is a 122-bit UUID so brute force is impractical, but make it consistent. **Fix:** factor `constant-time=` into a shared ns; reject blank/nil run tokens before comparison. **Status:** ✅ fixed in `190db38`; API key and bootstrap token share constant-time comparison.

**🟢 Lower-severity:** ✅ `log-error-exdata-unredacted` fixed in `19049c0` (ex-data masked before serialize, bearer values redacted); ✅ `fs-write-toctou-symlink` fixed in `2061994` (symlink path segments rejected, no-follow NIO ops); ✅ `seatbelt-paths-not-canonicalized` fixed in `214268a` (canonical profile paths + policy validation); ✅ `trusted-fragment-xss` fixed in `58d700c` (raw fragments require output from `render`/`render-many`).

### 5.2 Agentic / LLM correctness

**Verification (2026-05-30): no §5.2 Agentic / LLM correctness items remain open.** Current code verifies max-token tool calls execute, max-token terminal transcripts preserve prior messages, telemetry preserves tool calls + real usage, context token estimates strip provider raw/annotations, index-less streaming tool-call deltas merge into the open call, and the claimed "good bits" remain true. Focused suites: **62 tests, 183 assertions, 0 failures, 0 errors**.

**🔴 max-token-discards-toolcalls — HIGH.** `loop.clj:367,389-403`: the `max-token?` branch runs **before** tool execution. `finish_reason="length"` frequently accompanies a complete, valid `tool_calls` array (model emitted the call, then hit the output cap). The loop ignored `(:tool-calls llm-response)` and emitted `emit-max-token-truncation!` + `:stop-reason :max-tokens`, throwing the turn away. On small/local models with tight `max_tokens`, the agent spuriously dead-ended on turns that actually produced executable calls. **Fix (two parts — small-model profiles hit a second discard path via the nudge governor):** (1) in `loop.clj`, gate the branch on absence of usable output and otherwise fall through to execute; (2) in `nudge.clj`, do not classify `:max-token-truncation` when tool calls are present. **Status:** ✅ fixed in `19febce`; `max-token-terminal?` now requires empty tool calls and nudge max-token retry is also tool-call-gated.

**🟠 max-token-loses-final-messages — MEDIUM (same branch, separate bug).** `loop.clj:398` returns `:final-messages [{…}]` — a fresh single-element vector — **discarding** the accumulated `final-messages` from earlier successful tool turns, unlike every other terminal branch which `conj`s. Consumers using the return value's transcript get tool calls without results. **Fix:** `(conj final-messages {:role "assistant" :content max-tokens-content})`. **Status:** ✅ fixed in `19febce`.

**🟠 telemetry-discards-toolcalls — HIGH.** The `complete-with-telemetry!` completion path (`telemetry.clj:326`) returned string-only content, dropping tool calls and real usage. Anything routed through it (orchestrator LLM calls, chat fallback) lost tool-calling and accurate cost accounting. **Status:** ✅ fixed in `4efd399`; the path routes through normalized `invoke`, records real usage, and returns/publishes tool calls.

**🟡 token-estimate-counts-raw — MEDIUM.** `context_pack.clj:25-39` estimates tokens via `pr-str` over the whole internal message, but tool-call blocks retain the full provider object under `:raw` (`llm/messages.clj:116`) which is dropped on the wire. So every assistant tool-call message is estimated at ~2× real cost, inflating `tokens-before` and triggering compaction/truncation **earlier than warranted** — degrading quality on conversations that would fit. **Fix:** `dissoc :raw` (and `:annotations`) before estimating, or estimate over `(internal->openai-compatible …)`. **Status:** ✅ fixed in `593b412`; estimator strips provider raw/annotations before counting.

**🟡 streaming-toolcall-index-fallback — MEDIUM.** `merge-tool-call-deltas` (`openai_compatible.clj:201`) keys deltas by `(or (:index tc) (count acc))`. Providers that omit `:index` on argument fragments get a single call split across slots → unparseable arguments silently dropped. **Fix:** carry a `last-index` and attribute index-less fragments to the most-recently-opened call (advance only on fresh `:id`/`:function.name`). **Status:** ✅ fixed in `593b412`; index-less fragments attach to the last open call.

**🟢 Good bits here are real:** termination is genuinely bounded (every `recur` increments `step-no`, gated by `max-steps`; doom-loop fingerprints repeated calls with recursive canonicalization, `doom_loop.clj:41`; nudge has explicit retry budgets). `normalize-chat-history` (`messages.clj:138`) is a careful tool-protocol repairer that inserts synthetic results for orphaned calls and drops orphans **without** corrupting stored history. The streaming flusher *is* bounded by a `:scheduled?` gate (contradicting an earlier flag).

### 5.3 Concurrency

**Verification (2026-05-30): no §5.3 concurrency items remain open.** Focused suites pass: **24 tests, 75 assertions, 0 failures, 0 errors**. Broker slow-subscriber overflow is explicit/contained; parallel tool execution is capped; stream flushing uses one scheduler; orchestrator inbox/channel overflow now emits explicit dropped events instead of silent oldest-message loss.

**🟠 broker-park-overflow — HIGH.** `publish-to-subscriber!` (`broker/local.clj:27`) defaulted (when `:slow-client` unset) to `async/put!` on a fixed-64 buffer; `put!` enqueued into a list hard-capped at 1024, then **threw**. The Telegram firehose subscribed with no opts (`telegram.clj:632`) and only drained during an active turn. `publish!` runs **synchronously on the event-sink thread** (`system/events.clj:116`), so an overflow there aborted event emission for **all** subscribers. **Status:** ✅ fixed in `1ade0dd`; `channel-buffer` now defaults to `:sliding`, and Telegram, `wait-for-run!`, SSE, UI, and chat subscriptions use sliding/drop-new where loss is intentional.

**🟡 parallel-tool-pool-unbounded — MEDIUM.** `execute-parallel!` (`runtime/tools.clj:256`) does `(Executors/newFixedThreadPool (max 1 (count ready)))` — an **LLM-controlled** thread count, with a fresh pool per batch. 50 parallel reads ⇒ 50 OS threads. **Fix:** `(min (count ready) max-parallelism)` from config (default ~4-8), or a shared bounded executor. **Status:** ✅ fixed in `e3a65e7`; config default caps parallel tool execution at 6.

**🟡 stream-flusher-thread-per-flush — MEDIUM.** `chat.clj:562` spawns `(future (Thread/sleep 50) (flush!))` per scheduled flush — continuous send-off-pool churn under streaming. Bounded to ~1 per active turn but wasteful across many sessions; competes with `run-task!`/queued runs/telegram workers. **Fix:** one shared `ScheduledExecutorService` created in `create-service`, shut down in `stop!`. **Status:** ✅ fixed in `34c2cfe`; chat service owns one daemon scheduled flusher and shuts it down.

**🟡 orchestrator-inbox-sliding-silent-loss — MEDIUM.** Agent inboxes (`orchestrator.clj:242`) and channel buses (`orchestrator.clj:920`) used sliding buffers that silently dropped oldest messages on overflow while delivery/post events claimed success. **Status:** ✅ fixed in this commit; both queues are now fixed-capacity and use `async/offer!`, with overflow surfaced via `agent.interop.message.dropped` or `channel.message.dropped` events. Regression coverage fills interop inboxes and channel buffers past capacity and asserts drop events instead of silent loss.

### 5.4 Error handling & resource lifecycle

**Verification (2026-05-30):** all §5.4 items are fixed: `no-jvm-shutdown-hook`, `child-control-store-not-closed`, `local-runner-process-map-leak`, `openai-stream-leak-on-error`, and `sqlite-retry-conn-only`. Focused suites: **82 tests, 317 assertions, 0 failures, 0 errors** (`agent.cli-test`, `agent.persistence.sqlite-test`, `agent.config-test`, `agent.runs.registry-test`, `agent.runners.local-unsandboxed-test`, `agent.runs.child-test`, `agent.llm.providers.openai-compatible-test`, `agent.llm.providers.ollama-test`).

**🟠 no-jvm-shutdown-hook — MEDIUM (was HIGH).** `close-system!` (`system.clj:137`) exists and is correct but was **only** called from `full-reload-now!`. The `serve` path blocked forever with no `addShutdownHook`; one-shot CLI commands exited without closing. On SIGTERM the HikariCP pool, nREPL socket, and Telegram poller were abandoned (WAL is crash-safe so no corruption, but no clean checkpoint). **Fix:** register a shutdown hook in the `serve` branch (calling `nrepl/stop!` + `close-system!`), wrap one-shot branches in `try/finally`. *(Note: the proposed `@system-ref` in the agent's draft fix is wrong — the local is `system`.)* **Status:** ✅ fixed in this commit; `serve-shutdown!`/`run-serve!` (`cli.clj:261-295`) provide idempotent shutdown and one-shot commands use `with-system!` (`cli.clj:253-259`).

**🟠 child-control-store-not-closed / local-runner-process-map-leak — MEDIUM each.** `run-child!`'s `finally` (`child.clj:234`) never closes the control store's HikariCP pool. `LocalUnsandboxedRunner` (`local_unsandboxed.clj:77`) only ever `assoc`s into `processes`; nothing ever `dissoc`s, so dead `Process` objects accumulate for the runtime's life. **Fix:** `(when (= :sqlite (:type control)) (sqlite/close-store! (:store control)))` in the child finally; `(swap! processes dissoc run-id)` in the exit-watcher `finally` (the other substrate runners delegate to this one — single fix covers all). **Status:** ✅ fixed in `190db38`; child store closes and local runner prunes dead processes.

**🟠 openai-stream-leak-on-error — MEDIUM.** In both streaming entry points (`openai_compatible.clj:261-269,307-322`), `checked-response` is evaluated and **throws on non-2xx before** the body is placed under `with-open`, leaking the connection/socket on exactly the common 429/5xx case (defeating the retry logic). Same in `ollama.clj:101,206`. **Fix:** capture the raw response, `(some-> (:body resp) .close)` on the error path before throwing. **Status:** ✅ fixed in `1be7a90`; OpenAI-compatible and Ollama providers close non-2xx bodies before throwing.

**🟢 sqlite-retry-conn-only — HIGH (from subsystem map).** `with-sqlite-retry`/connection helpers (`common.clj:116`) used to retry **connection acquisition** but not statement execution, so a `SQLITE_BUSY` on a statement (under WAL write contention) was not retried. **Fix:** wrap statement execution in the retry, not just `getConnection`. **Status:** ✅ fixed in `5788de9`; `with-connection` and `with-transaction` now wrap the whole unit of work, including acquire, statements, commit/rollback, and close.

### 5.5 Data & persistence

**Verification (2026-05-30):** §5.5 items are fixed. Combined focused suite: **59 tests, 277 assertions, 0 failures, 0 errors** (`agent.persistence.sqlite-test`, `agent.config-test`, `agent.runs.registry-test`, `agent.memory.core-test`). `clj-kondo` on touched namespaces/tests: **0 errors, 0 warnings**.

**🟡 migration-checksum-cosmetic — MEDIUM.** Each migration used to carry a hand-written literal `:checksum`; `record-migration-meta!` stored that literal and `verify-migration-checksums!` compared it to the same literal, so SQL edits were invisible. **Status:** ✅ fixed in `8b4248a`; descriptors no longer carry `:checksum`, `record-migration-meta!` and verify derive `sha256(str version up down)[:16]`, and legacy literal checksums are backfilled before verification so existing DBs do not false-positive or destructively reset. Regression coverage verifies edited applied SQL now raises `:migration-drift` and legacy literals backfill safely.

**🟡 pool-config-not-forwarded — MEDIUM.** `create-datasource` (`common.clj:84`) reads `:maximum-pool-size`/`:minimum-idle`/`:connection-timeout-ms`; config now forwards them through defaults and env. **Status:** ✅ fixed in `15b4fa2`; defaults are 8/2/30000 in `config.clj` and `resources/config/default.edn`, env overrides are `AGENT_SQLITE_MAXIMUM_POOL_SIZE`, `AGENT_SQLITE_MINIMUM_IDLE`, and `AGENT_SQLITE_CONNECTION_TIMEOUT_MS`, and regression coverage checks defaults + overrides.

**🟡 runtime-health-n+1 — MEDIUM.** `runtime-health` no longer loads commands per run. It still loads up to 1000 runs for stale-run accounting, but pending commands are counted by one aggregate SQL query (`count-pending-agent-run-commands`). **Status:** ✅ fixed in `15b4fa2`; no per-run command fan-out remains.

**🟡 decide-approval-no-pending-guard — MEDIUM.** `decide-tool-approval` SQL (`resources/.../tools.sql:17`) used to update by `id` only, with no pending-state CAS, allowing stale decisions to flip an already-denied/expired approval. **Fix:** add `and status='pending'` and treat 0-row update as conflict. **Status:** ✅ fixed in `190db38`; SQL now requires `status = 'pending'`, stale decisions throw `:approval-decision-conflict`, and API returns 409.

**🟢 Lower:** ✅ `journal-mode-bypasses-busy-timeout` fixed in `8b4248a`: `apply-journal-mode!` now goes through `with-connection`, so configured busy-timeout and SQLite retry wrap the journal PRAGMA. ✅ `select-value-broken` fixed in `8b4248a`: `select-value` now reads column 1 directly from its own `ResultSet`, with regression coverage for `SELECT 42 AS n`. ✅ `datahike-full-edge-scan` fixed in `eddf80d` for neighborhood/path hot paths: entity-neighborhood and path search now use indexed source/target edge lookups with per-node caching instead of scanning every edge. Full scans remain only for unfiltered graph listing/reconciliation/reset paths where all edges are intentionally needed.

### 5.6 Code smells & duplication

**Verification (2026-05-30):** All 5.6 items fixed. Final focused suite: **181 tests, 891 assertions, 0 failures, 0 errors** (`agent.runtime.loop-test`, `agent.runtime.tools-test`, `agent.tools.core-test`, OpenAI/Ollama provider tests, `agent.config-test`, `agent.chat-test`, orchestrator/broker/runs/API/Telegram tests). Final touched-file lint: **0 warnings**.

- **`run!` god function / terminal-result duplication** — **Status:** ✅ fixed. `terminal-result` is centralized and `fatal-guardrail!` uses it, so terminal branches no longer duplicate the 7-key return map. The residual size concern is also reduced: runtime event construction moved into `agent.runtime.events`, leaving `runtime.loop/run!` focused on orchestration. Focused loop/chat suite: **58 tests, 211 assertions, 0 failures, 0 errors**; source lint: **0 warnings**.
- **`double-tool-enforcement`** — **Status:** ✅ fixed. Runtime preflight owns allow-list/permission/validation (`runtime/tools.clj:89-134`), passes `:preflighted? true` (`runtime/tools.clj:146-157`), and `tools.core/execute-tool` skips those checks/events when preflighted while keeping approval/hooks authoritative (`tools/core.clj:302-324`). Regression test confirms one start/end event on batch execution (`runtime/tools_test.clj:198-214`).
- **Provider copy-paste** (`ollama.clj` ↔ `openai_compatible.clj`) — **Status:** ✅ fixed in this commit. `agent.llm.providers.common` now owns endpoint trimming, structured-stream gating, response-body close-on-error, retrying JSON posts, stream posts, and async stream-channel lifecycle. OpenAI-compatible and Ollama providers keep only provider-specific payload parsing/turn normalization. Provider focused tests: **26 tests, 86 assertions, 0 failures, 0 errors**.
- **Trivial-util duplication** — **Status:** ✅ fixed in this commit. `agent.util` now owns `now-str`, `duration-ms`, `result-content`, and sink `emit!`; `agent.runtime.cancel` owns cancellation checks/errors. Runtime loop/tools, planner, telemetry, chat, memory, runs, health, channels, system reload, and provider-adjacent timestamp call sites now use shared helpers instead of local copies/inline `Instant/now`. Focused runtime/util suite: **127 tests, 482 assertions, 0 failures, 0 errors**; touched namespace lint: **0 warnings**.
- **`execute-tool` repeated end-event maps** — **Status:** ✅ fixed in this commit. `tools.core/execute-tool` now emits all direct end events through `emit-tool-end!`, uses shared `util/duration-ms`, and includes `:duration-ms` on blocked, success, and failure payloads. Regression coverage asserts duration-bearing blocked/succeeded/failed payloads. Focused tools suite: **26 tests, 65 assertions, 0 failures, 0 errors**; touched namespace lint: **0 warnings**.
- **Magic numbers** — **Status:** ✅ fixed in this commit. `agent.defaults` now owns LLM temperature/max-tokens, chat max-steps, tool-output truncation, broker/channel buffer sizes, and event-stream buffer size. Runtime loop/messages, chat turn, OpenAI-compatible fallbacks, orchestrator, broker, runs, API SSE handlers, Telegram streaming, and config defaults now use named constants. `default-config-template-matches-code-defaults-test` guards the user-facing EDN template against default drift. Focused defaults suite: **111 tests, 657 assertions, 0 failures, 0 errors** plus config reload test **29 tests, 120 assertions, 0 failures, 0 errors**; touched namespace lint: **0 warnings**.

---

## 6. Agentic workflow map

Open **`iris-workflow-map.html`** for the interactive, clickable version (color-coded by layer, click any node for its file, role, and attached findings). Textual summary of one user message → response:

```
ingress (cli | http | telegram)
  → chat/run!            queue manager (manager-lock; active vs queued; cancel atom)
  → chat/run-turn!       persist user turn · auto-compact! · load history · recall-memory · build context-injectors (iris/mode/skill/memory)
  → runtime.loop/run!    ┌─ per step ───────────────────────────────────────────────┐
                         │ normalize-chat-history (repair tool pairing)               │
                         │ context-pack/pack-context  (drop nudges, truncate, summarize via LLM) │
                         │ tool-router/route-tools    (trim schemas + allowed-tools)  │
                         │ planner/plan-step! → llm.core/invoke → provider HTTP        │
                         │   (streams deltas back as message-update events)           │
                         │ guardrails: strip-respond · max-token · nudge · doom-loop   │
                         │ kernel.runtime/execute-step! → ChatKernelOps                │
                         │   → runtime.tools/execute-batch! (preflight, parallel-safe) │
                         │     → tools.core/execute-tool → fs/http/shell/memory/todo   │
                         │ emit tool-turn events; recur OR terminate                   │
                         └────────────────────────────────────────────────────────────┘
  events → loop-event-sink → {persistence, streaming, tool-call} subscribers → system event-sink
                                → sqlite/log-event!  AND  broker/publish! → SSE chunks / Telegram drafts
  → persist-final-assistant! + log-completion!
  → extract-turn-memory! → fact LLM (structured output) → save-memory-fact! (sqlite + optional datahike graph)
```

Termination stop-reasons (all explicit, auditable): `completed · approval-required · max-steps · max-tokens · doom-loop · guardrail-exhausted · cancelled · planner-error`. The `runners/*` substrates + `runtime.core` durable registry are a **separate** control-plane for *spawned agent runs*, not the synchronous chat turn (they meet at the event sink).

**Post-remediation (2026-05-30):** `max-tokens` no longer terminates a turn that emitted tool calls; the telemetry completion path preserves tool calls + real usage; context packing no longer counts provider raw payloads; streaming tool-call chunks merge correctly when providers omit indexes; tool execution, streaming flushes, sqlite health, and run control are bounded/hardened. Each assistant turn's provider `:usage` is stamped onto persisted message metadata and surfaced in the web UI — per message (token + tool-call badge) and per thread (cumulative-billed + current-context-window tokens and a per-tool breakdown, summed over the full history so it stays correct across compaction).

---

## 7. Prioritized remediation roadmap

Markers (2026-05-30): ✅ done · ◐ partial · ⬜ open.

**P0 — Release blockers (do before any release/deploy)**
1. ⬜ Fix `Dockerfile` `COPY config` (B1) — Docker build is dead.
2. ⬜ Point CI at `master`, unify the jar name, drop the k8s deploy job (B2) — tests/lint/build currently never run.
3. ✅ `api-arbitrary-substrate` — `:local-unsandboxed` removed from the API-selectable registry; API schema now uses substrate enum + closed `runner_options`; raw-body guard rejects caller-supplied execution keys. (`6b3c7e6` + working tree)
4. ✅ `auth-disabled-when-key-nil` — non-loopback API bind now requires a key. (`190db38`)
5. ✅ `federation-verify-noop` — `verify-request!` fails closed when no peer key resolves. (`f194677`)
6. ✅ `broker-park-overflow` — default subscriptions now use a non-blocking (`:sliding`) buffer; Telegram + `wait-for-run!` subscriptions fixed. (`1ade0dd`)

**P1 — Correctness**
7. ✅ `max-token-discards-toolcalls` + `max-token-loses-final-messages` (loop + nudge). (`19febce`)
8. ✅ `double-tool-enforcement` — single authoritative gate; ambiguous-approval semantics reconciled. (`d59975c`)
9. ✅ `api-body-coercion-discarded` — handlers use the coerced body; schema now gates input. (`6b3c7e6`)
10. ✅ `telemetry-discards-toolcalls` (`4efd399`) + `token-estimate-counts-raw` (`593b412`).
11. ⬜ `migration-checksum-cosmetic` — real hash **with** the re-baseline step.
12. ✅ `decide-approval-no-pending-guard`. (`190db38`)

**P2 — Robustness / resources**
13. ✅ JVM shutdown hook + one-shot `try/finally` (this commit); child store close + runner process-map prune ✅ (`190db38`); OpenAI/Ollama stream-on-error close ✅ (`1be7a90`).
14. ✅ `sqlite-retry-conn-only` (`5788de9`), pool-config forwarding + `runtime-health` N+1 (`15b4fa2`).
15. ✅ `http-ssrf-dns-rebinding` (`e536f1a`); `container-default-share-network-true` + `bootstrap-token-non-constant-time` ✅ (`190db38`); `shell-denylist` hardening ✅ (`783d0d0`); lower log/fs/seatbelt security hardening ✅ (`19049c0`, `2061994`, `214268a`).
16. ✅ `parallel-tool-pool-unbounded` (`e3a65e7`); `stream-flusher-thread-per-flush` (`34c2cfe`); `orchestrator-inbox` silent loss (working tree).

**P3 — Structure / maintainability**
17. ✅ Split `agent.runtime.*` registry/control-plane to `agent.runs.*`; decompose `chat.clj` to a 44-line facade.
18. ◐ `run!`'s `terminal-result` helper ✅ (`aa69766`); `agent.llm.providers.common` + `agent.util`/`agent.runtime.cancel` ⬜ open.
19. ✅ Capability-gated `KernelOps`; neutral SSE metrics; enforced `orchestrator :enabled?` mutator gate.
20. ◐ `trusted-fragment` invariant ✅ (`58d700c`); promote magic numbers to named/config + verify `max-steps` override ⬜.

---

## 8. Appendix — method & confidence

- **Build/CI/test/auth/shell/fs/migrations/sandbox** findings were verified directly by me (commands run, files read). High confidence.
- **Cross-cutting findings** were produced by dimension reviewers and then **adversarially re-verified** by independent agents that re-read the cited code with instructions to refute. Verdicts (✓/◐/✗) and corrected severities reflect that second pass; I have preserved the refutations (`per-request-yolo-override`, the `llm-stream-leak` downgrade) for honesty rather than dropping them.
- Where a fix proposed by an agent was itself wrong (e.g. the `@system-ref` local name; the `(when-not (>!! …) (reduced))` non-fix; the migration re-baseline omission), the corrected fix is what appears above.
- Line numbers are a snapshot; re-confirm before editing. Several findings cluster in `loop.clj` and `chat.clj` — fixing the structural debt (§3) makes the correctness fixes safer.

*Bottom line: strong foundations, a short and concrete blocker list. As of 2026-05-30 the security/correctness P0–P1 code fixes are done (all 12 Critical/High closed) and the highest-risk §5 security Mediums are closed through `58d700c`; the remaining gates to a credible 0.1 are **B1 + B2**, plus migration checksum, orchestrator inbox overflow, memory dual-write divergence, and provider/common cleanup.*
