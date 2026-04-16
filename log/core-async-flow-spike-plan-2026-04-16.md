# core.async.flow Spike Plan
Date: 2026-04-16

## Verified current state

Active rewritten runtime under `src/agent` does **not** use `core.async.flow`.

Current code usage exists only in:

- `flow.clj`
- `legacy_src/agent/multi_head.clj`
- `legacy_src/agent/kg_integration.clj`

## Adoption decision

Current answer:

- **not worth adopting as base runtime now**
- **worth a narrow orchestration spike later**

## Why not now

1. rewritten runtime is still settling around config/api/tools/orchestrator
2. current execution model is still simple enough for explicit state maps
3. `core.async.flow` is still relatively new/alpha
4. debugging + cancellation + observability would get harder before base event model exists

## Where it could help later

Good fit:

- orchestration DAGs
- branching/retry tool pipelines
- long-running workflows
- event routing when channels become numerous

Bad fit:

- basic CRUD API endpoints
- simple completion requests
- direct single-step tool calls
- current minimal orchestrator actions

## Spike goal

Answer one question:

Does `core.async.flow` make complex agent orchestration clearer than an explicit state machine?

## Spike scope

Build one isolated experiment only.

Suggested workflow:

1. receive orchestration task
2. plan step
3. run 2 parallel tool steps
4. branch on one result
5. retry one failed step
6. synthesize final output
7. emit durable events for each stage

## Required capabilities

Spike must demonstrate:

1. start/resume/cancel
2. per-step status visibility
3. retry semantics
4. deterministic tests
5. observable event trail

## Non-goals

Do not use spike to:

- replace HTTP API
- replace all tool execution
- replace persistence layer
- rebuild whole orchestrator

## Evaluation criteria

Adopt only if spike is materially better on:

### 1. Clarity

- topology easier to understand than explicit state machine
- step boundaries obvious

### 2. Debuggability

- easy to inspect current node/state
- failures attributable to one step

### 3. Cancellation

- canceling run does not leave orphan work

### 4. Observability

- events map cleanly to UI/logging

### 5. Testability

- deterministic enough for repeatable tests

## Rejection criteria

Reject if any are true:

1. flow graph is harder to reason about than explicit maps + queues
2. state inspection is poor
3. retries/cancellation need awkward workarounds
4. event persistence becomes less clear
5. most runtime logic still has to live outside flow anyway

## Recommended sequence

Do this **after**:

1. event log model exists
2. tool execution hooks exist
3. orchestrator emits typed lifecycle events

Then build spike in isolated namespace, not core path.

## Suggested implementation shape

Create experimental namespace only, e.g.:

- `src/agent/experiments/orchestration_flow.clj`
- `test/agent/experiments/orchestration_flow_test.clj`

Inputs:

- task request
- tool registry
- retry policy
- event sink

Outputs:

- final result
- full stage event log

## Final recommendation

Current decision:

- keep rewritten runtime explicit
- defer `core.async.flow` to orchestration experiment
- treat adoption as opt-in for one subsystem, not whole architecture
