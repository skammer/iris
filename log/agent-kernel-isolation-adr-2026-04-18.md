# Agent Kernel Isolation ADR

Date: 2026-04-18

## Decision

Adopt `cnos`-inspired separation between pure agent/orchestrator decision logic and effectful runtime execution, without adopting Git as storage/substrate.

## Chosen shape

1. Agent/orchestrator kernels should emit typed directives, not perform effects directly.
2. Orchestrators should be toolless by default and focus on delegation, waiting, and supervision.
3. Workers should receive explicit capability bundles:
   - capabilities
   - tool access
   - memory scopes
   - budgets
   - task
4. Tool execution should support capability-bundle enforcement via allowlisted tools.
5. Runtime remains source of truth for execution state:
   - SQLite
   - broker
   - runs/events/checkpoints
   - federation

## Implemented baseline

- `src/agent/kernel.clj`
  - pure directive contract
  - pure orchestrator worker-spawn step
- `src/agent/orchestrator.clj`
  - agent metadata now includes:
    - `kind`
    - `tool-access`
    - `memory-scopes`
    - `budgets`
    - `task`
- `src/agent/tools/core.clj`
  - execution context now supports `allowed-tools`
  - registry blocks tools outside bundle
- `src/agent/core.clj`
  - added `spawn-task-worker!`
- `src/agent/api.clj`
  - agent create/update now accept and expose capability-bundle metadata

## Implemented follow-up

- `src/agent/core.clj`
  - `execute-directive!`
  - `execute-step!`
  - receipt emission baseline
- `src/agent/kernel/runtime.clj`
  - shared directive executor for core/API
  - directive support:
    - `spawn-worker`
    - `await`
    - `tool-call`
    - `send-message`
    - `state-patch`
    - `complete`
- `src/agent/api.clj`
  - orchestrator spawn-only API surface:
    - `POST /v1/agents/:id/spawn-worker`
  - agent-scoped tool execution:
    - `POST /v1/agents/:id/tools/:tool/execute`
  - generic step execution:
    - `POST /v1/agents/:id/steps`
- `src/agent/tools/core.clj`
  - empty tool bundle now correctly means zero tool access
- `src/agent/orchestrator.clj`
  - worker LLM context now includes task/capability/tool/memory/budget preamble
- `src/agent/ui.clj`
  - operator board shows kernel receipt activity

## Why

- better isolation
- better repeatability
- easier replay/testing
- less ambient authority
- clearer orchestrator/worker split

## Explicit non-decision

Do not use Git as main storage/runtime substrate now.

## Next follow-ups

1. Route real worker tool execution through capability bundles, not only metadata/tests.
   Baseline now present via agent-scoped tool execution route and shared directive executor.
2. Make orchestrator spawn-only rule universal, not just preferred API shape.
3. Persist directive/receipt lineage with stronger first-class schema than generic event payloads.
4. Add profile system on top of capability bundles.
5. Move autonomous worker loops to directive-native planning/execution rather than ad hoc command handling.
