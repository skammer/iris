# SmallCode Loop

## Shape
- Main loop in `tmp/smallcode/src/core/session.ms`.
- `runOnce` adds user message, advances turn, calls `agentLoop`.
- Loop: build messages, stream model, parse/native tool calls, execute tools, add results, compact if low budget, repeat.
- Max tool loop constant: 15.

## Halting
- Stops when no tool calls.
- Stops with appended warning when max tool loop reached.
- Policy module has in-memory per-minute turn cap and token budget.
- Clarification module blocks tool use for vague prompts by instructing model to ask one question first.

## Restore
- JSON sessions under `.smallcode/sessions`.
- JS store: one `{id}.json` with messages/tokens/cost/toolCalls/title.
- Marrow store: snapshot includes model/provider/turnCount/messages/workingMemory/planSteps/status.
- Resume latest active/paused session supported.

## Tools
- Two-stage tool router:
  - direct mode for large native-tool models
  - category selector for small native-tool models
  - text parser for no native tool support
- Tool executor validates args, repairs JSON, gates write operations through diff approval, truncates outputs to budget.

## Subagents / Long Tasks
- Multi-session manager supports independent active/paused/completed sessions.
- No durable background worker or subagent protocol found.
- Checkpoint module supports approval waits with timeout.

## User Handoff
- Clarification loop: model asks one specific question before tools.
- Checkpoint approval flow can approve/reject with timeout.
- ACP adapter has basic prompt/context/action.confirm skeleton; not full loop-backed integration.

## Events / UI
- Event bus emits model.request, model.token, model.complete, model.early_stop, tool.start, tool.error, tool.complete.
- TUI diff view used for write approvals.
- ACP adapter emits capabilities/response/error over JSON lines.

## Reconciliation
- Context compactor summarizes older turns, keeps last two turns.
- Tool executor normalizes failed parse/validation into retryable tool feedback.
- Context retriever fetches semantic file/symbol context via MCP with max files/hops.

## Decision
- Designed for small models: reduce schema load, ask clarifying questions, compact aggressively.
- Best reusable ideas: two-stage tool routing and diff-first approval.

Confidence: 0.84

Caveats: some files are generated/Marrow pseudocode style; runtime completeness may vary.
