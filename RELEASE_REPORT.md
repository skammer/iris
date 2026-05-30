# Iris Runtime — Release Review Report

**Scope:** canonical runtime under `src/agent` (~26.5k LOC, ~110 namespaces). `tmp/llx`, `legacy_src`, `restate-data`, `target` excluded.
**Date:** 2026-05-29 · **Branch:** `master` @ `96b6930` · **JDK (local):** 25 · **Clojure:** 1.12.4
**Method:** 12 parallel subsystem readers → 7 cross-cutting reviewers (architecture, concurrency, security, error/resource, smells, data, agentic-correctness) → adversarial verification of every High/Critical finding, plus an independent ground-truth pass (build, CI, full test suite, hand-read of the loop / auth / shell / fs / migrations / sandbox spine). An interactive map of the agentic workflow is in **`iris-workflow-map.html`** (open in a browser).

---

## 1. Verdict

**Iris is a genuinely well-architected agent runtime — substantially better factored than most LLM-agent codebases — but it is *not* release-ready as-is.** The core agentic loop, persistence, LLM abstraction, and sandbox design are clean and defensible. The blockers are concentrated in three places: (1) the build/CI pipeline is broken and not actually running, (2) a small number of security holes collapse the sandbox/auth threat model, and (3) a few real correctness bugs in the loop's truncation handling and the double tool-enforcement path.

Confidence in this assessment: **0.85**. The architecture and code quality findings are high-confidence (read + verified). The "is it exploitable in practice" nuance on security depends on deployment posture (default loopback bind + optional API key), which I flag per-finding.

| Area | Grade | One-line |
|---|---|---|
| Architecture / layering | **B+** | Clean DI, protocol seams; spoiled by 2 god namespaces + a mis-named package |
| Agentic loop correctness | **B** | Bounded & well-structured; one real truncation bug discards valid tool calls |
| Security | **C+** | Strong SQL/static-file/crypto layers; sandbox & auth have real holes |
| Concurrency | **B+** | Sound threading model; a couple of unbounded-buffer / leak edges |
| Error handling / resources | **B** | Good per-request boundaries; weak process-lifecycle (no shutdown hook) |
| Data / persistence | **A−** | Parameterized, transactional, idempotent; checksum "drift" is cosmetic |
| Build / CI / release | **D** | **Docker build broken; CI never runs; 3 jar names** |
| Tests | **B** | 393 tests, 1571 pass; 8 fail in one env-sensitive e2e test |

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
`clojure -M:test … run-all-tests` → **393 tests, 1579 assertions, 8 failures, 0 errors.** All 8 are in `agent.runtime.child-test/child-runtime-local-unsandboxed-flow-test` (`test/agent/runtime/child_test.clj:43-78`): the spawned child never transitions `launched → running`, so commands stay `pending` and there are 0 heartbeats/checkpoints. This is the child-runtime control-plane handshake (subprocess spawn + loopback HTTP), which does not complete under a restricted sandbox. The other **392 tests pass**.
**Action:** confirm this test passes in a permissive CI environment (it must, once B2 is fixed and CI actually runs). If it is environment-dependent, tag it (`^:integration`) so it doesn't silently rot. Right now nobody knows it fails because CI never runs (B2).

---

## 3. Findings by severity (post-verification)

Severities are the **corrected** severities after adversarial verification. ✓ = confirmed against code, ◐ = partially confirmed (scope narrower than claimed), ✗ = refuted. Each detailed section explains the verdict.

### Critical / High

| ID | Sev | Verdict | Where | Issue |
|---|---|---|---|---|
| api-arbitrary-substrate | **CRIT→HIGH** | ✓ | `api/routes.clj:40`, `handlers/runs.clj:57` | Run API accepts `substrate:"local-unsandboxed"` + arbitrary `command` → host RCE outside any sandbox |
| federation-verify-noop | **HIGH** | ◐ | `federation/http.clj:115` | Signature verification skipped for a registered peer with no key → unsigned/replayable inbox |
| broker-park-overflow | **HIGH** | ✓ | `broker/local.clj:27` | Default subscriber uses unbounded `put!`; a slow consumer can throw at 1024 pending puts, aborting event emission for everyone |
| max-token-discards-toolcalls | **HIGH** | ✓ | `runtime/loop.clj:389` | `finish_reason="length"` aborts the turn even when valid tool calls were emitted |
| run-bang-god-function | **HIGH** | ✓ | `runtime/loop.clj:246` | 346-line, ~12-deep `run!` with 8 duplicated terminal maps — *and* harbors the max-token bug below |
| double-tool-enforcement | **HIGH** | ✓ | `runtime/tools.clj:88` vs `tools/core.clj:236` | Approval/permission/validate run twice through **divergent** code paths (allow-on-ambiguous vs block-on-ambiguous) |
| chat-god-namespace | **HIGH** | ✓ | `chat.clj` (985 LOC) | Queue + streaming + persistence + memory + kernel-ops + fallback in one namespace |
| api-body-coercion-discarded | **HIGH** | ✓ | `handlers/runs.clj:92` | Malli-coerced body is validated then ignored; handlers re-read raw JSON, so schema doesn't actually gate input |
| telemetry-discards-toolcalls | **HIGH** | ✓ | `telemetry.clj:326` | `complete-with-telemetry!` path drops tool calls and real usage |
| federated-interop-bypass | **HIGH** | ✓ | `orchestrator.clj:687` | Federated interop delivery bypasses trust-policy/route enforcement |
| telegram-draft-id-invalid | **HIGH** | ✓ | `telegram.clj:827` | Draft id can rotate to 0/negative, violating Telegram API contract |
| sqlite-retry-conn-only | **HIGH** | ✓ | `persistence/sqlite/common.clj:116` | Retry wraps connection *acquisition*, not statement execution → `SQLITE_BUSY` on statements not retried |

### Medium (selected — full list in §5)

`auth-disabled-when-key-nil` (`middleware.clj:91`, fail-open), `http-ssrf-dns-rebinding` (`tools/common/http.clj:85`), `container-default-share-network-true` (`config.clj:241`), `shell-denylist-bypassable` (`shell.clj:40`), `bootstrap-token-non-constant-time` (`handlers/runs.clj:128`), `no-jvm-shutdown-hook` (`cli.clj:286`), `child-control-store-not-closed` (`runtime/child.clj:234`), `local-runner-process-map-leak` (`local_unsandboxed.clj:77`), `openai-stream-leak-on-error` (`openai_compatible.clj:311`), `migration-checksum-cosmetic` (`migrations.clj:733`), `pool-config-not-forwarded` (`common.clj:84`), `runtime-health-n+1` (`runtime/core.clj:520`), `decide-approval-no-pending-guard` (`tools.sql:17`), `token-estimate-counts-raw` (`context_pack.clj:38`), `max-token-loses-final-messages` (`loop.clj:398`), `dual-kernelops-divergent` (`kernel/ops.clj` + two hosts), `parallel-tool-pool-unbounded` (`runtime/tools.clj:256`), `stream-flusher-thread-per-flush` (`chat.clj:562`), `memory-dual-write-divergence` (`memory/core.clj:384`), `orchestrator-enabled-flag-decorative` (`orchestrator.clj:122`), `trusted-fragment-xss` (`ui/render.clj:18`).

### Refuted / downgraded (transparency)

- **`per-request-yolo-override` (claimed CRITICAL) — REFUTED.** The body `yolo`/`yolo?` flag (`handlers/agents.clj:151`) flows only into the *outer dispatch gate* (`kernel/runtime.clj:116`); it is **not** placed into the tool execution context, so `enforce-approval!` (`tools/core.clj:236`) still reads the **config-derived** `:yolo?` and a sensitive tool without an approval-id is still blocked. The body flag does not achieve privilege escalation. *However*, a separate real vector exists: a caller-supplied directive `:payload :context {:yolo? true}` (schema `:context :any`, `kernel/schema.clj:34`) is merged over config in `tools/service.clj:178-182` and *would* flip approval off — harden by computing `:yolo?` **after** the merge. Also recommend removing the dead body flag to avoid confusion.
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

Four issues, all confirmed:

1. **`chat.clj` is a 985-line god namespace** (88 top-level defs, 19 internal requires). It is simultaneously the chat front-end, the session queue/cancellation state machine, the streaming flusher, the persistence subscriber, the memory recall/extract glue, *and* a `ChatKernelOps` implementation. Every concern ripples into the others; it is the highest-churn/highest-risk file. **Fix:** extract `agent.chat.streaming` (flusher + streaming-state, `chat.clj:529-568`), `agent.chat.memory` (recall/extract, `:343-472`), `agent.chat.kernel-ops` (`ChatKernelOps`, `:395-427`), and `agent.chat.queue` (the `active-turn`/`enqueue-item`/`start-next-queued!` state machine, ~`:811-953`). Target ≤300 lines for `chat.clj`.

2. **`agent.runtime.*` conflates two unrelated subsystems.** `runtime/loop.clj` is the *pure in-process chat loop*; `runtime/core.clj` is a *"Durable distributed run registry and control-plane"* requiring `persistence.sqlite`, `broker.core`, `runners.core`. The conventional `core.clj` "heart" name names the **registry**, not the loop. This is why `tools/service.clj` depends on `runtime.core` (durable activities) *and* `runtime.tools` (batch exec) for two different reasons. **Fix:** move the registry/control-plane (`runtime/core.clj` → `agent.runs.registry`, `runtime/child.clj` → `agent.runs.child`, `runtime/control_client.clj` → `agent.runs.control-client`; `agent.runs.service` already exists and already requires `runtime.core`). Keep the chat loop under `agent.runtime`. Update the 5 requirers (`ui.clj:14`, `tools/service.clj:4`, `runtime/child.clj:8`, `system/health.clj:15`, `runs/service.clj:11`). Behavior-neutral; cohesion-only.

3. **Two divergent `KernelOps` hosts.** One `KernelOps` protocol (`kernel/ops.clj`), but `SystemKernelOps` (`kernel/service.clj:12`) implements the full directive set via the orchestrator while `ChatKernelOps` (`chat.clj:395`) throws `:unsupported-directive` for `spawn`/`send` and **silently** no-ops `patch-agent-state!`/`set-agent-status!` (returning `{:status :ok}`/`:completed` as if they succeeded). Whether a schema-valid directive works is path-dependent. **Fix:** add a capability set to the ops value and have `kernel.runtime/execute-directive!` check capability before dispatch, returning a uniform `{:status :unsupported}` receipt instead of per-host throw/no-op. Do **not** delegate `ChatKernelOps`→`SystemKernelOps` (chat deliberately routes tool exec through chat permissions). Verified as latent (no current chat caller emits the unsupported directives), so HIGH→MEDIUM.

4. **Inverted dependency + a decorative flag.** `system/health.clj:5` (domain layer) requires `api.streaming` (transport layer) just to read `streaming/metrics` — move that counter to a transport-neutral location (`broker`/`telemetry`). And `orchestrator`'s `:enabled?` flag (`orchestrator.clj:122`) is stored and reported but **gates nothing** — either enforce it at the mutating entry points (`spawn-agent!`, `send-agent-message!`, `create-channel!`) or remove it.

---

## 5. Detailed findings by dimension

For each finding: **problem → why it matters → fix.** Line numbers are from the reviewed tree; verify before editing.

### 5.1 Security (most important)

**🔴 api-arbitrary-substrate — HIGH (CRIT downgraded for default loopback).** `create-run-body` (`routes.clj:40`) declares `:substrate` as open `:string` and `:runner_options` as open `:map`. `normalize-run-request` (`handlers/runs.clj:57-60`) passes both straight through. `:local-unsandboxed` is in the request-selectable registry (`runs/service.clj:54`), and `prepare-runner-options` (`runners/options.clj:130`) only injects a default `:command` *when none is supplied* — a caller-supplied command is preserved and run via raw `ProcessBuilder` (`local_unsandboxed.clj:57`) with no isolation. `POST /v1/runs {"substrate":"local-unsandboxed","runner_options":{"command":["/bin/sh","-c","…"]},"auto_launch":true}` ⇒ arbitrary host RCE. **Why:** collapses the entire seatbelt/bwrap/container threat model into a remote exec endpoint for anyone holding the (single, shared, possibly-absent) API key. Reachable unauthenticated on loopback by default (`:key nil`); network-exposed if bound to `0.0.0.0`. **Fix:** (1) split the runner registry into an internal set (keeps `:local-unsandboxed` as the delegate that bwrap/seatbelt/docker wrap) and an **API-selectable** set that excludes it; (2) add `:runners :api-selectable-substrates` config (default `[:seatbelt :bubblewrap :docker :podman]`) and reject others in `runtime/create-run-request`; (3) on the API path, strip caller-supplied execution-controlling keys (`:command :working-dir :binds :mounts :env :user :share-network? :image :control-url` + seatbelt profile keys) — honor them only from server config; (4) tighten the schema to `[:enum …]` + closed `:runner_options`.

**🟠 federation-verify-noop — HIGH (partial).** `verify-request!` (`federation/http.clj:103-136`) wraps **all** checks (missing-auth, skew, signature, nonce-replay) in `(when public-key* …)` and unconditionally returns `true`. A peer **registered without a key** (`orchestrator.clj:390` only adds `:keys` when a public key is supplied) resolves `public-key*` → nil → all checks skipped → unsigned, replayable message accepted. (A *truly unknown* peer is rejected later by `receive-federated-message!`'s `:peer-not-found` guard, so that sub-claim is refuted; the hole is the keyless-registered peer.) **Fix:** fail closed — require auth fields unconditionally, then if `public-key*` is nil `throw {:type :signature-missing}` (→ 401).

**🟠 auth-disabled-when-key-nil — MEDIUM.** `wrap-api-key-auth` (`middleware.clj:91`) only enforces when `api-key` is non-blank: `(and api-key (protected-path? …) …)`. Default config ships `:key nil` (`config.clj:276`). Default host is loopback, but an operator who sets `:api :host "0.0.0.0"` (normal for containers) without a key exposes the **entire** code-executing control plane unauthenticated, with no warning. **Fix:** refuse to start (or force loopback) when the bind host is non-local and no key is set; optionally print a generated ephemeral key. *(Good bit: the comparison itself uses constant-time `MessageDigest/isEqual` — `middleware.clj:77`.)*

**🟠 http-ssrf-dns-rebinding — MEDIUM.** `validate-url!` (`tools/common/http.clj:75-96`) resolves the host, rejects private/loopback/CGNAT/ULA, then returns the **URL string**; `http/request` re-resolves at connect time (TOCTOU). An attacker domain returns a public IP at validation and `169.254.169.254`/`127.0.0.1` at connect. Redirects re-validate with the same gap; IPv4-mapped IPv6 ranges aren't all covered. **Fix:** pin the connection to the validated IP (resolve once, connect by IP with original Host header), re-validate every redirect address, block `::ffff:0:0/96` mapped private ranges. *(Good bit: the static-IP blocklist is otherwise thorough.)*

**🟠 container-default-share-network-true — MEDIUM.** `config.clj:241,249` and `runners/options.clj:115` default docker/podman `:share-network?` to **true**; `--network none` is only added when false (`docker_podman.clj:67`). So the *strongest-isolation* substrate is the *weakest on network* by default — a child reaches host-loopback services (control plane, nREPL, cloud metadata). Bubblewrap/seatbelt correctly default to no network. **Fix:** default `:share-network?` to false for containers; require explicit operator (not request-body) opt-in.

**🟡 shell-denylist-bypassable — MEDIUM.** `default-rules` (`shell.clj:40-45`) deny only exact argv shapes (`["rm" "-rf" "/*"]`, `["dd" "**"]`…). Positional matching (`shell.clj:99`) means `["rm" "-rf" "/home/x"]`, `["/bin/rm" …]`, or `["sh" "-c" "rm -rf /"]` match nothing and fall to `:default-action :ask`. The deny list is decorative; real safety is the `:ask` approval gate + the fact that **no default profile grants `:shell-exec`** (`config.clj:135`, a genuine good bit). Also note `npm run **` and `cargo build/test **` are `:allow` — i.e. arbitrary code execution via package scripts / build.rs **without approval**. **Fix:** match on resolved binary basename, parse `sh -c`/`bash -c` wrappers, treat deny as authoritative regardless of default-action, and drop `npm run`/`cargo build|test` from the unconditional allow set.

**🟡 bootstrap-token-non-constant-time — MEDIUM.** `/v1/runs/:id/control/*` bypasses API-key auth by design (`middleware.clj:93`), protected only by the bootstrap token, which `ensure-run-control!` (`handlers/runs.clj:128`) compares with plain `=` (short-circuits, timing-observable) — unlike the API key. Token is a 122-bit UUID so brute force is impractical, but make it consistent. **Fix:** factor `constant-time=` into a shared ns; reject blank/nil run tokens before comparison.

**🟢 Lower-severity:** `log-error-exdata-unredacted` (`logging.clj:167`, `pr-str` of ex-data bypasses the secret-masker — add `"key"`/`"bearer"` fragments and mask before serialize), `fs-write-toctou-symlink` (`fs.clj:34`, narrow the default `["."]` root, re-validate realpath after open), `seatbelt-paths-not-canonicalized` (`seatbelt.clj:50`, use `getCanonicalPath`, constrain paths in `policy.clj` like bwrap binds), `trusted-fragment-xss` (`ui/render.clj:18`, any non-render string becomes raw HTML — enforce the invariant).

### 5.2 Agentic / LLM correctness

**🔴 max-token-discards-toolcalls — HIGH.** `loop.clj:367,389-403`: the `max-token?` branch runs **before** tool execution. `finish_reason="length"` frequently accompanies a complete, valid `tool_calls` array (model emitted the call, then hit the output cap). The loop ignores `(:tool-calls llm-response)` and emits `emit-max-token-truncation!` + `:stop-reason :max-tokens`, throwing the turn away. On small/local models with tight `max_tokens`, the agent spuriously dead-ends on turns that actually produced executable calls. **Fix (two parts — small-model profiles hit a second discard path via the nudge governor):** (1) in `loop.clj`, gate the branch on absence of usable output: `(and max-token? (empty? (:tool-calls llm-response)) (str/blank? (or (:content llm-response) "")))` and otherwise fall through to execute; (2) in `nudge.clj:133-136`, do not classify `:max-token-truncation` when tool calls are present.

**🟠 max-token-loses-final-messages — MEDIUM (same branch, separate bug).** `loop.clj:398` returns `:final-messages [{…}]` — a fresh single-element vector — **discarding** the accumulated `final-messages` from earlier successful tool turns, unlike every other terminal branch which `conj`s. Consumers using the return value's transcript get tool calls without results. **Fix:** `(conj final-messages {:role "assistant" :content max-tokens-content})`.

**🟠 telemetry-discards-toolcalls — HIGH.** The `complete-with-telemetry!` completion path (`telemetry.clj:326`) returns string-only content, dropping tool calls and real usage. Anything routed through it (orchestrator LLM calls, chat fallback) loses tool-calling and accurate cost accounting. **Fix:** route through the normalized `invoke` turn map; record usage from it.

**🟡 token-estimate-counts-raw — MEDIUM.** `context_pack.clj:25-39` estimates tokens via `pr-str` over the whole internal message, but tool-call blocks retain the full provider object under `:raw` (`llm/messages.clj:116`) which is dropped on the wire. So every assistant tool-call message is estimated at ~2× real cost, inflating `tokens-before` and triggering compaction/truncation **earlier than warranted** — degrading quality on conversations that would fit. **Fix:** `dissoc :raw` (and `:annotations`) before estimating, or estimate over `(internal->openai-compatible …)`.

**🟡 streaming-toolcall-index-fallback — MEDIUM.** `merge-tool-call-deltas` (`openai_compatible.clj:201`) keys deltas by `(or (:index tc) (count acc))`. Providers that omit `:index` on argument fragments get a single call split across slots → unparseable arguments silently dropped. **Fix:** carry a `last-index` and attribute index-less fragments to the most-recently-opened call (advance only on fresh `:id`/`:function.name`).

**🟢 Good bits here are real:** termination is genuinely bounded (every `recur` increments `step-no`, gated by `max-steps`; doom-loop fingerprints repeated calls with recursive canonicalization, `doom_loop.clj:41`; nudge has explicit retry budgets). `normalize-chat-history` (`messages.clj:138`) is a careful tool-protocol repairer that inserts synthetic results for orphaned calls and drops orphans **without** corrupting stored history. The streaming flusher *is* bounded by a `:scheduled?` gate (contradicting an earlier flag).

### 5.3 Concurrency

**🟠 broker-park-overflow — HIGH.** `publish-to-subscriber!` (`broker/local.clj:27`) defaults (when `:slow-client` unset) to `async/put!` on a fixed-64 buffer; `put!` enqueues into a list hard-capped at 1024 (verified in `core.async-1.6.681`), then **throws**. The Telegram firehose subscribes with no opts (`telegram.clj:632`) and only drains during an active turn. `publish!` runs **synchronously on the event-sink thread** (`system/events.clj:116`), so an overflow there aborts event emission for **all** subscribers. SSE handlers already pass `{:buffer-strategy :sliding :slow-client :drop-new}` — the firehose is the unguarded outlier (and `runtime/core.clj:402 wait-for-run!` has the same pattern). **Fix:** make `channel-buffer`'s default `:sliding` (so `put!` into a sliding/dropping buffer never accumulates pending puts and never throws), keep `:park` semantics on a non-blocking buffer; pass safe opts at the Telegram + `wait-for-run!` subscriptions. Do **not** use `:block`/`>!!` as the default (converts overflow into a sink-thread deadlock).

**🟡 parallel-tool-pool-unbounded — MEDIUM.** `execute-parallel!` (`runtime/tools.clj:256`) does `(Executors/newFixedThreadPool (max 1 (count ready)))` — an **LLM-controlled** thread count, with a fresh pool per batch. 50 parallel reads ⇒ 50 OS threads. **Fix:** `(min (count ready) max-parallelism)` from config (default ~4-8), or a shared bounded executor.

**🟡 stream-flusher-thread-per-flush — MEDIUM.** `chat.clj:562` spawns `(future (Thread/sleep 50) (flush!))` per scheduled flush — continuous send-off-pool churn under streaming. Bounded to ~1 per active turn but wasteful across many sessions; competes with `run-task!`/queued runs/telegram workers. **Fix:** one shared `ScheduledExecutorService` created in `create-service`, shut down in `stop!`.

**🟡 orchestrator-inbox-sliding-silent-loss — MEDIUM.** Agent inboxes (`sliding-buffer 64`, `orchestrator.clj:226`) and channel buses (`128`, `:841`) silently drop oldest messages on overflow while delivery events claim success. **Fix:** dropping-buffer or explicit overflow → emit `interop.message.dropped`.

### 5.4 Error handling & resource lifecycle

**🟠 no-jvm-shutdown-hook — MEDIUM (was HIGH).** `close-system!` (`system.clj:137`) exists and is correct but is **only** called from `full-reload-now!`. The `serve` path blocks on `@(promise)` (`cli.clj:295`) with no `addShutdownHook`; one-shot CLI commands (`cli.clj:300-324`) exit without closing. On SIGTERM the HikariCP pool, nREPL socket, and Telegram poller are abandoned (WAL is crash-safe so no corruption, but no clean checkpoint). **Fix:** register a shutdown hook in the `serve` branch (calling `nrepl/stop!` + `close-system!`), wrap one-shot branches in `try/finally`. *(Note: the proposed `@system-ref` in the agent's draft fix is wrong — the local is `system`.)*

**🟠 child-control-store-not-closed / local-runner-process-map-leak — MEDIUM each.** `run-child!`'s `finally` (`child.clj:234`) never closes the control store's HikariCP pool. `LocalUnsandboxedRunner` (`local_unsandboxed.clj:77`) only ever `assoc`s into `processes`; nothing ever `dissoc`s, so dead `Process` objects accumulate for the runtime's life. **Fix:** `(when (= :sqlite (:type control)) (sqlite/close-store! (:store control)))` in the child finally; `(swap! processes dissoc run-id)` in the exit-watcher `finally` (the other substrate runners delegate to this one — single fix covers all).

**🟠 openai-stream-leak-on-error — MEDIUM.** In both streaming entry points (`openai_compatible.clj:261-269,307-322`), `checked-response` is evaluated and **throws on non-2xx before** the body is placed under `with-open`, leaking the connection/socket on exactly the common 429/5xx case (defeating the retry logic). Same in `ollama.clj:101,206`. **Fix:** capture the raw response, `(some-> (:body resp) .close)` on the error path before throwing.

**🟢 sqlite-retry-conn-only — HIGH (from subsystem map).** `with-sqlite-retry`/connection helpers (`common.clj:116`) retry **connection acquisition** but the actual statement execution isn't wrapped, so a `SQLITE_BUSY` on a statement (under WAL write contention) isn't retried. **Fix:** wrap statement execution in the retry, not just `getConnection`.

### 5.5 Data & persistence

**🟡 migration-checksum-cosmetic — MEDIUM.** Each migration carries a **hand-written literal** `:checksum`; `record-migration-meta!` stores that literal and `verify-migration-checksums!` (`migrations.clj:733`) compares it to the same literal. **The SQL is never hashed.** So editing an applied migration's DDL goes undetected — and the advertised `destructive-reset-on-drift?` recovery guards an event that normal edits can't trigger. **Fix:** compute `(sha256 (str version up down))[:16]`; derive at record + verify. **CRITICAL caveat the fix must include:** existing DBs store the old literals — backfill/recompute `schema_migration_meta.checksum` for already-applied versions **before** verifying, or first boot false-positives and (with destructive-reset on) wipes the DB. *(Good bit: `effective-up-statements` idempotent ALTER guards and the trigger-based event-sourcing are excellent.)*

**🟡 pool-config-not-forwarded — MEDIUM.** `create-datasource` (`common.clj:84`) reads `:maximum-pool-size`/`:minimum-idle`/`:connection-timeout-ms` (defaults 4/1/30000), but nothing in the config path forwards them, so the pool is permanently **4 connections**. Combined with `runtime-health`'s N+1 (below) and autocommit readers, four concurrent readers can starve a `with-transaction` writer for up to 30s. **Fix:** add the keys to `:storage :sqlite` defaults and pass through; raise the default (WAL supports many readers).

**🟡 runtime-health-n+1 — MEDIUM.** `runtime/core.clj:520` loads up to 1000 runs then fires one `list-agent-run-commands` per run, each opening/closing a pooled connection. A liveness probe can fan out ~1000 queries against a 4-connection pool. **Fix:** one `select run_id, count(*) … group by run_id` (or a single total).

**🟡 decide-approval-no-pending-guard — MEDIUM.** `decide-tool-approval` SQL (`resources/.../tools.sql:17`) is `update … where id=:id` with **no `and status='pending'`** and no CAS, on a plain `with-connection`. A denied/expired approval can be silently flipped to approved; two decisions race last-writer-wins. Approvals gate shell/fs-writes. **Fix:** add `and status='pending'` (and optional expiry predicate); treat 0-row update as conflict (the existing `(when (zero? updated) (throw …))` then surfaces it).

**🟢 Lower:** `journal-mode-bypasses-busy-timeout` (`common.clj:144`, boot-only spurious `SQLITE_BUSY`), `select-value-broken` (`common.clj:225`, latent dead code — passes a row map where a `ResultSet` is expected), `datahike-full-edge-scan` (`datahike.clj:358`, O(edges) per query — fine for the labeled "Prototype", a scaling cliff later).

### 5.6 Code smells & duplication

- **`run!` god function** (`loop.clj:246`, §3) — the 8 duplicated 7-key terminal-result maps mean every contract change must touch 8 sites (and one was already missed → the max-token bug). **Fix:** extract one `terminal-result` helper that emits `:agent-end` once and returns the canonical map merged with the branch-specific keys; reimplement `fatal-guardrail!` in terms of it.
- **`double-tool-enforcement`** (`runtime/tools.clj:88` vs `tools/core.clj:307-342`) — every tool call runs allow-list/permission/validate/approval **twice** through two paths that **disagree**: runtime allows on an ambiguous approval decision, core blocks on it. Also emits `tool-execution-start/end` twice with mismatched payloads (confuses telemetry/UI counters). **Fix:** pick one authoritative site (recommend `tools.core/execute-tool`), reconcile the ambiguous-decision semantics explicitly, and add a `:preflighted?` flag so runtime can skip re-validation; strip the duplicate gating + events from the other layer.
- **Provider copy-paste** (`ollama.clj` ↔ `openai_compatible.clj`) — `trim-trailing-slash`, `post-json`, `checked-response`, `stream-structured-output?`, and the `async/thread`+`with-open`+`close!` stream skeleton are byte-identical/structural copies. This is *how the streaming paths already drifted.* **Fix:** `agent.llm.providers.common`.
- **Trivial-util duplication** — `now-str` (5 defs + 13 inline `(str (Instant/now))`), `duration-ms` (≥7 copies), `cancelled?`/`throw-if-cancelled!` (two byte-identical copies in `loop.clj` and `runtime/tools.clj`), `event!`, `result-text`/`result-content`. **Fix:** one `agent.util` (+ `agent.runtime.cancel`); `runtime/tools.clj` should require `loop`'s already-public `cancelled?`.
- **`execute-tool` repeats the `:tool-execution-end` map 4×** and the nanoTime calc 2× (`tools/core.clj:293`); the blocked/failed paths omit `:duration-ms` the success path has. **Fix:** `emit-tool-end!` helper + compute duration once.
- **Magic numbers** — `max-steps 6` and `tool-output-max-chars 8000` buried in `:or` destructuring (`loop.clj:253`), `sliding-buffer 64/128`, `temperature 0.2`, `max_tokens 1024`. Promote to named `def`s / config. **Note: `max-steps 6` is low for a coding agent** — confirm `chat.clj` overrides it; if not, that caps every turn at 6 tool iterations.

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

---

## 7. Prioritized remediation roadmap

**P0 — Release blockers (do before any release/deploy)**
1. Fix `Dockerfile` `COPY config` (B1) — Docker build is dead.
2. Point CI at `master`, unify the jar name, drop the k8s deploy job (B2) — tests/lint/build currently never run.
3. `api-arbitrary-substrate` — remove `:local-unsandboxed` from the API-selectable registry + reject caller-supplied `:command`/binds/network on the API path.
4. `auth-disabled-when-key-nil` — fail closed (or force loopback) when bound non-locally without a key.
5. `federation-verify-noop` — fail closed when no peer key resolves.
6. `broker-park-overflow` — default subscriptions to a non-blocking (`:sliding`) buffer; fix the Telegram + `wait-for-run!` subscriptions.

**P1 — Correctness**
7. `max-token-discards-toolcalls` + `max-token-loses-final-messages` (loop + nudge).
8. `double-tool-enforcement` — single authoritative gate; reconcile ambiguous-approval semantics.
9. `api-body-coercion-discarded` — actually use the coerced body (don't re-read raw JSON) so schema validation gates input.
10. `telemetry-discards-toolcalls` and `token-estimate-counts-raw`.
11. `migration-checksum-cosmetic` — real hash **with** the re-baseline step.
12. `decide-approval-no-pending-guard`.

**P2 — Robustness / resources**
13. JVM shutdown hook + one-shot `try/finally`; child store close; runner process-map prune; OpenAI/Ollama stream-on-error close.
14. `sqlite-retry-conn-only`; pool-config forwarding; `runtime-health` N+1.
15. `http-ssrf-dns-rebinding` (pin to validated IP); `container-default-share-network-true`; `bootstrap-token-non-constant-time`; `shell-denylist` hardening (or document as approval-only + drop `npm run`/`cargo build|test` from auto-allow).
16. `parallel-tool-pool-unbounded`; `stream-flusher-thread-per-flush`; `orchestrator-inbox` silent loss.

**P3 — Structure / maintainability**
17. Split `agent.runtime.*` (registry → `agent.runs.*`); decompose `chat.clj` (≤300 lines).
18. Extract `run!`'s `terminal-result` helper; `agent.llm.providers.common`; `agent.util`/`agent.runtime.cancel`.
19. Capability-gated single `KernelOps` contract; move `system/health`'s streaming-metrics dependency; enforce or remove `orchestrator :enabled?`.
20. Promote magic numbers to named/config; verify `max-steps` override; `trusted-fragment` HTML-escaping invariant.

---

## 8. Appendix — method & confidence

- **Build/CI/test/auth/shell/fs/migrations/sandbox** findings were verified directly by me (commands run, files read). High confidence.
- **Cross-cutting findings** were produced by dimension reviewers and then **adversarially re-verified** by independent agents that re-read the cited code with instructions to refute. Verdicts (✓/◐/✗) and corrected severities reflect that second pass; I have preserved the refutations (`per-request-yolo-override`, the `llm-stream-leak` downgrade) for honesty rather than dropping them.
- Where a fix proposed by an agent was itself wrong (e.g. the `@system-ref` local name; the `(when-not (>!! …) (reduced))` non-fix; the migration re-baseline omission), the corrected fix is what appears above.
- Line numbers are a snapshot; re-confirm before editing. Several findings cluster in `loop.clj` and `chat.clj` — fixing the structural debt (§3) makes the correctness fixes safer.

*Bottom line: strong foundations, a short and concrete blocker list. Clear the P0 items and this is a credible 0.1.*
