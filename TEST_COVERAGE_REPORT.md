# Test Coverage Report

## How coverage looks today
- Scope reviewed: static inventory from `src/` and `test/`.
- Source namespaces: **94**
- Test files: **49**
- Source namespaces with direct `*_test` counterpart: **44**
- Source namespaces missing direct test file: **50**
- Coverage tooling: no configured `cloverage`/`cider`/coverage plugin in `deps.edn` or build pipeline.
- Test execution entrypoint is `agent.test-runner/run-all-tests` (single namespace list in `test/agent/test_runner.clj`).
- `api-smoke`, `release-smoke`, and `docker-podman-e2e` are integration/smoke tests, not detailed behavioral tests.
- `release-smoke` and docker/podman E2E only run when env/runtime is available (Docker/Podman present; release smoke needs `AGENT_RELEASE_SMOKE=1`).

## Missing direct test coverage (50 namespaces)
- `agent.api.errors`
- `agent.api.handlers.channel_adapters`
- `agent.api.handlers.chat`
- `agent.api.handlers.events`
- `agent.api.handlers.health`
- `agent.api.handlers.memory`
- `agent.api.handlers.providers`
- `agent.api.handlers.public`
- `agent.api.handlers.runs`
- `agent.api.handlers.sessions`
- `agent.api.handlers.skills`
- `agent.api.handlers.telemetry`
- `agent.api.handlers.tool_approvals`
- `agent.api.handlers.tools`
- `agent.api.handlers.ui`
- `agent.api.helpers`
- `agent.api.middleware`
- `agent.api.responses`
- `agent.api.routes`
- `agent.api.schemas`
- `agent.api.serializers`
- `agent.api.streaming`
- `agent.api.validation`
- `agent.broker.core`
- `agent.core`
- `agent.kernel.ops`
- `agent.kernel.runtime`
- `agent.kernel.schema`
- `agent.nrepl`
- `agent.persistence.sqlite.common`
- `agent.persistence.sqlite.events`
- `agent.persistence.sqlite.memory`
- `agent.persistence.sqlite.migrations`
- `agent.persistence.sqlite.runs`
- `agent.persistence.sqlite.schema`
- `agent.persistence.sqlite.sessions`
- `agent.persistence.sqlite.tools`
- `agent.prompts`
- `agent.runners.core`
- `agent.runners.options`
- `agent.runners.policy`
- `agent.runtime.control_client`
- `agent.runtime.doom_loop`
- `agent.tools.approvals`
- `agent.tools.display`

## Existing tests that are intentional non-1:1 namespaces (5)
- `agent.api-smoke`
- `agent.chat-harness`
- `agent.persistence.session_entries`
- `agent.release-smoke`
- `agent.runners.docker-podman-e2e`

## Definitely needs additional tests (priority)
1. High
- `agent.core`: CLI bootstrap, argument parsing/dispatch, startup paths.
- `agent.api.handlers.*` and `agent.api.*` internals: route wiring, request/response transforms, validation, middleware effects.
- `agent.persistence.sqlite.*` internals: migration/DDL/query helpers and per-table edge behavior.
- `agent.runtime.doom_loop` and `agent.runtime.control_client`: runtime control flow and command handling paths.
- `agent.kernel.ops`, `agent.kernel.runtime`, `agent.kernel.schema`: tool-loop dispatch and validation paths.

2. Medium
- `agent.runners.core`, `agent.runners.options`, `agent.runners.policy`: runner config/selection/feature-policy behavior.
- `agent.nrepl`: enablement + lifecycle + error handling.
- `agent.tools.approvals`, `agent.tools.display`: high-impact external tool behaviors.
- `agent.prompts`: prompt composition and interpolation invariants.

3. Low
- `agent.broker.core`, `agent.api.{errors,responses,serializers,helpers,middleware,routes,schemas,streaming,validation}` for tighter contract coverage around edge input and error shapes.

## Recommendations
- Add coverage tooling (e.g. `:test` alias + `cloverage` profile) and generate an LCOV/HTML report for repeatable metrics.
- Prefer adding direct namespace tests first for the 50 missing items above; then expand assertions in existing broad tests (`api-smoke`, `chat-test`, `telemetry-test`) for behavior depth.
