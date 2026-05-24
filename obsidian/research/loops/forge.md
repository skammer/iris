# Forge Loop

## Shape
- Workflow runner in `tmp/forge/src/forge/core/runner.py`.
- Loop is workflow/task oriented, not chat-session oriented.
- `WorkflowRunner` builds initial messages, validates model response, executes tools, repeats until terminal tool or max iterations.
- Guardrail stack: `ResponseValidator`, `StepEnforcer`, `ErrorTracker`, context manager.

## Halting
- Terminal tool success returns final result.
- Max iterations raises `MaxIterationsError`.
- Cancellation event checked per iteration.
- Repeated inference/tool errors trip retries/max-tool-errors.
- Premature terminal tool can be blocked and converted to corrective nudge.

## Restore
- No durable chat restore found in core loop.
- Runner state is in-memory per workflow.
- Proxy/server side may stream events, but core loop is not an append-only session ledger.

## Tools
- Tool specs passed to inference.
- Executes tool calls sequentially in a batch.
- Tool errors feed back as tool messages.
- Synthetic `respond` tool pattern forces small models to finish with a terminal call.

## Subagents / Long Tasks
- No general subagent API found.
- `slot_worker.py` provides long-running queue execution with one inference slot, priority, and preemption.
- Higher-priority task cancels current worker via cancel event.

## User Handoff
- No rich user-question protocol in core.
- Handoff mostly via workflow validation errors, guardrail nudges, and terminal responses.

## Events / UI
- Runner can emit reasoning/tool_call messages and compaction events through callbacks.
- Context manager emits `CompactEvent`.
- Presentation is proxy/server dependent, not the core loop's main concern.

## Reconciliation
- Guardrails reconcile model intent with required workflow step order.
- Context manager reconciles token pressure by warning/compacting before inference.
- No strong persisted replay reconciliation.

## Decision
- Best for bounded workflows: strict validator plus terminal-tool contract.
- Less suitable as general chat agent unless wrapped in session/event persistence.

Confidence: 0.86

Caveats: proxy/UI not fully traced; core runner inspected.
