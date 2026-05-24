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

## Code Pattern

```python
# tmp/forge/src/forge/core/runner.py
while iteration < self.max_iterations:
    if cancel_event is not None and cancel_event.is_set():
        raise WorkflowCancelledError(
            messages=messages,
            completed_steps=step_enforcer.completed_steps,
            iteration=iteration,
        )

    step_check = step_enforcer.check(tool_calls)

    if step_check.needs_nudge:
        if step_enforcer.premature_exhausted:
            attempted = next(
                tc.tool for tc in tool_calls
                if tc.tool in workflow.terminal_tools
            )
            raise StepEnforcementError(
                terminal_tool=attempted,
                attempts=step_enforcer.premature_attempts,
                pending_steps=step_enforcer.pending(),
            )
        nudge = step_check.nudge
        nudge_type = _NUDGE_KIND_TO_TYPE[nudge.kind]
        _emit(Message(
            MessageRole.USER,
            nudge.content,
            MessageMeta(nudge_type, step_index=iteration),
        ))
        continue

raise MaxIterationsError(
    self.max_iterations, step_enforcer.completed_steps, step_enforcer.pending()
)
```

Pattern: terminal-tool contract plus step enforcer turns premature completion into corrective user messages until retry budget exhausts.

```python
# tmp/forge/src/forge/tools/respond.py
def respond_tool() -> ToolDef:
    def _respond(message: str) -> str:
        return message

    return ToolDef(
        spec=respond_spec(),
        callable=_respond,
    )
```

Pattern: synthetic terminal response keeps weak models inside tool-call protocol.

## Decision
- Best for bounded workflows: strict validator plus terminal-tool contract.
- Less suitable as general chat agent unless wrapped in session/event persistence.

Confidence: 0.86

Caveats: proxy/UI not fully traced; core runner inspected.
