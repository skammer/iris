# Iris Loop

## Shape
- Pure evented loop: `src/agent/runtime/loop.clj`.
- Wrapper/runtime orchestration: `src/agent/chat.clj`.
- Loop state tracks step, planner messages, final messages, trace, usage, doom-loop state, nudge state.
- Per step: emit start events, repair history, pack context, route tools, call planner, apply guardrails, execute tools, decide complete/recur.
- Main loop is dependency-injected: planner, context packer, tool executor, approval fn, fallback fn, observer, event sink, cancellation token.

## Halting
- Stops on `:completed`, `:approval-required`, cancellation, max steps, guardrail/tool termination, planner failure fallback.
- `stop` maps to a cancellation token and produces controlled "Stopped." behavior.
- Doom-loop and token/context warnings nudge before hard failure.

## Restore
- SQLite tree/session store rebuilds context from branch path.
- Latest compaction summary is prepended; entries before compaction cut are skipped.
- `excluded-from-context` messages stay visible/persisted but are omitted from LLM context.
- `run!` queues turns per session; queued user turns are persisted excluded until activated.

## Tools
- `src/agent/runtime/tools.clj` preflights, enforces allowed tools, permissions, sensitive checks, approvals.
- Supports sequential and parallel batches while preserving ordered tool results.
- Tool termination only terminates if all ordered results say terminate.

## Subagents / Long Tasks
- `src/agent/runtime/child.clj`, `core.clj`, `runs.clj`.
- Runtime service supports create/register/heartbeat/checkpoint/enqueue-command/history/replay/idempotency/leases.
- Child workers poll commands, heartbeat, checkpoint, handle cancel/run-task.

## User Handoff
- Approval required can suspend loop.
- Queued messages are serialized per session and activated after current turn.
- Web/UI can cancel active session and inspect runs.

## Events / UI
- `src/agent/persistence/sqlite/events.clj` stores typed events with request/session linkage.
- `chat.clj` subscribes to runtime events; message-end persists messages; stream deltas flush to UI.
- UI renders routes `/chat/:id` and `/runs/:id`, compact tool rows plus details.

## Reconciliation
- History repair before planner call inserts/normalizes missing tool results.
- Context packer protects system/latest user/latest tool loop, drops stale nudges, compacts/truncates older tool results.
- Persistence wrapper reconciles branch context, compaction summaries, excluded messages, queued turns.

## Decision
- Strong boundary: pure loop emits events; outer chat/runtime owns persistence, transport, queues.
- Best reusable ideas: dependency-injected loop, event sink as contract, branch-aware restore, child-run protocol.

Confidence: 0.92

Caveats: static read only; no runtime trace executed.
