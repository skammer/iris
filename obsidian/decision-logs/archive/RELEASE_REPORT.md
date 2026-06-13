# Iris Runtime - Release Review Report

**Reviewed:** 2026-05-30
**Tree:** `master` @ `4b6f581`, with current working-tree release/build edits
**Scope:** `src`, `test`, `resources`, `public`, `.github`, scripts, build/Docker files
**Size:** 38,677 LOC total; 35,789 Clojure LOC; 190 Clojure files

## Verdict

Iris has strong runtime foundations, but is **not release-ready** until CI lint and container startup are tightened.

Confidence: **0.86**

Key caveats:
- Docker daemon unavailable locally, so image build/run not verified.
- Review is static + local test/build verification, not production load/security testing.
- Existing modified non-report files were treated as current truth.

## Verification

| Check | Result |
|---|---|
| Full tests | Pass: 437 tests, 1742 assertions, 0 failures, 0 errors |
| Uberjar | Pass: `target/iris.jar`, 88M |
| Shell syntax | Pass: `build.sh`, `scripts/deploy-jar.sh`, `scripts/iris-isolated.sh`, `scripts/iris-isolated-rebuild.sh` |
| Full clj-kondo | **Fail:** 92 errors, 60 warnings |
| Docker daemon | **Unavailable:** socket missing at `/Users/example/.docker/run/docker.sock` |

## Assessment

| Area | Grade | Assessment |
|---|---:|---|
| Architecture | A- | Clean core seams: loop DI, kernel protocols, API handlers, persistence facade. Large edge namespaces remain. |
| Agent loop | A- | Bounded, evented, test-covered. Main risk now operational supervision, not loop logic. |
| Security | B+ | Major sandbox/auth issues appear closed; release still needs production auth smoke coverage. |
| Persistence | A- | SQLite/migrations/facts are mature. Lint tooling cannot understand generated SQL fns yet. |
| Build/CI | C | Tests/build pass locally, but CI lint command is currently red. |
| Tests | B+ | Broad suite passes. Coverage is not measured; 50 source namespaces lack direct test counterparts. |
| Operability | B- | Health/events are solid; Docker/default env and worker supervision need release hardening. |

## Pros

- `runtime.loop/run!` remains well-isolated: planner, context pack, tool execution, approvals, fallback, and event sink are injected.
- API route data + handler binding are clear; orchestrator API is disabled by default.
- SQLite layer is transactional, WAL-aware, and now has real migration checksum drift detection.
- Tool execution has a single authoritative approval point after preflight.
- Full test suite and uberjar build pass locally.

## Cons

- CI linter is not green; release workflow cannot be trusted until it passes.
- Docker image default env binds `0.0.0.0` but provides no API key, so default container startup conflicts with auth safety.
- Several namespaces remain too large for easy support: `orchestrator`, `telegram`, `ui`, `memory.core`, OpenAI provider, migrations, config.
- Worker execution still uses raw `future` in several long-lived/control paths.
- Coverage report is inventory-only; no cloverage/LCOV gate.

## Actionable Items

### P0 - Release Gates

#### 1. Fix CI lint

Impact: current GitHub Actions `Run linter` step will fail before release artifacts are trustworthy.

Evidence:
- Workflow runs `/tmp/clj-kondo/clj-kondo --lint src test`.
- Local same command failed: 92 errors, 60 warnings.
- No `.clj-kondo` config exists.
- Main false positives are HugSQL generated `*-sqlvec` vars in `agent.persistence.sqlite.*`.
- Real cleanup also needed: unresolved `LLMError` in `src/agent/llm/core.clj` comment block and unused requires/bindings.

Action:
- Add `.clj-kondo/config.edn` for HugSQL generated vars/hooks or namespace exclusions.
- Remove/fix stale example comment symbols in `agent.llm.core`.
- Make `clj-kondo --lint src test` pass locally before trusting CI.

Confidence: **0.96**

#### 2. Make Docker startup contract explicit and testable

Impact: `docker run iris` likely fails by default because API binds externally without a key.

Evidence:
- `Dockerfile` sets `AGENT_API_HOST=0.0.0.0`.
- `Dockerfile` does not set `AGENT_API_KEY`.
- `agent.api/start-server!` refuses non-loopback bind without `:api :key`.
- README examples pass `AGENT_API_KEY=change-me`, but image default command does not.

Action:
- Choose one release contract:
  - require `AGENT_API_KEY` at entrypoint with clear error, or
  - default image to loopback, or
  - generate/print a one-time key.
- Add CI smoke: build image, run with `AGENT_API_KEY`, assert `/health`.

Confidence: **0.88**

#### 3. Verify remote Actions after lint passes

Impact: local tests/build pass, but release path still needs real hosted Actions evidence.

Evidence:
- Workflow now targets `master`.
- Local tests and jar build pass.
- Lint failure means current remote build would still be red.
- Docker daemon unavailable locally, so Docker job not verified here.

Action:
- Run GitHub Actions on `master` after P0.1.
- Confirm build, linter, Trivy, artifact upload, and Docker build/push.

Confidence: **0.9**

### P1 - Security / Product Hardening

#### 4. Standardize API request body handling

Impact: some handlers validate route schemas but still read `:body-params`/raw bodies directly.

Evidence:
- `handlers/agents.clj`, `handlers/tools.clj`, and several other handlers still call `h/read-json-body`.
- Route schemas are mostly open maps, so this is not always exploitable, but behavior is inconsistent.

Action:
- Add one helper: prefer `[:parameters :body]`, preserve explicitly needed raw-key checks.
- Migrate all schema-backed handlers.
- Close externally exposed request maps where extra keys matter.

Confidence: **0.74**

#### 6. Add coverage gates for high-risk namespaces

Impact: passing tests do not prove enough behavior depth; 50 source namespaces lack direct test counterparts.

Evidence:
- `TEST_COVERAGE_REPORT.md` lists 94 source namespaces, 49 test files, 50 missing direct test files.
- No cloverage/LCOV tool configured.
- `release-smoke` and Docker/Podman E2E are env-gated.

Action:
- Add coverage tooling and publish HTML/LCOV in CI.
- Add direct tests first for `agent.api.handlers.*`, `agent.persistence.sqlite.*`, `agent.runners.options`, `agent.kernel.runtime`, `agent.memory.core`.
- Promote release smoke/Docker smoke into CI where supported.

Confidence: **0.9**

### P2 - Maintainability / Operability

#### 7. Split remaining large namespaces by owner

Impact: support cost and hidden coupling remain high in edge/control namespaces.

Evidence: largest source files now include:
- `src/agent/orchestrator.clj`: 1034 lines
- `src/agent/telegram.clj`: 938 lines
- `src/agent/ui.clj`: 880 lines
- `src/agent/memory/core.clj`: 835 lines
- `src/agent/llm/providers/openai_compatible.clj`: 834 lines
- `src/agent/persistence/sqlite/migrations.clj`: 806 lines
- `src/agent/config.clj`: 702 lines

Action:
- Split by behavior owner, not arbitrary size:
  - orchestrator: agents, interop, channels, federation
  - telegram: polling, drafts, media, chat bridge
  - ui: shell/router, sessions, tools, telemetry panels
  - memory: prompt docs, search/ranking, fact extraction
  - OpenAI provider: payloads, streaming parser, response normalization

Confidence: **0.87**

#### 8. Replace raw worker futures with supervised executors

Impact: cancellation, backpressure, naming, metrics, and shutdown behavior are uneven.

Evidence:
- Raw `future` remains in chat queue/loop control, API streaming, Telegram polling/tasks, run registry waiters, local runner output readers.
- Some paths now cancel futures, but there is no shared supervision policy.

Action:
- Introduce bounded named executors per subsystem.
- Track active tasks in health/metrics.
- Standardize cancellation and shutdown timeouts.

Confidence: **0.78**

#### 9. Make memory extraction operationally explicit

Impact: SQLite facts are reliable; post-turn extraction still needs clearer production policy.

Evidence:
- Fact extraction is enabled by default and runs after turns, adding latency/cost when provider is configured.

Action:
- Keep SQLite facts as source of truth.
- Add graph drift/last-failure to health.
- Add scheduled or startup reconciliation option.
- Make fact extraction cost/latency visible; consider opt-in per profile/deploy.

Confidence: **0.82**

#### 10. Add jitter and non-blocking retry strategy for LLM calls

Impact: concurrent retry storms can align after provider 429/5xx.

Evidence:
- `agent.llm.core/retry-with-backoff` uses `Thread/sleep` and exponential delay without jitter.

Action:
- Add bounded jitter.
- Prefer executor-aware scheduling for async/streaming callers.
- Preserve `Retry-After` priority.

Confidence: **0.8**

## Release Recommendation

Do **not** tag release until P0 is closed. After P0, release can proceed as beta/dev-ops preview if P1 risks are documented and UI unsafe run controls are gated.
