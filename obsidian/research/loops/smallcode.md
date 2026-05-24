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

## Code Pattern

```marrow
// tmp/smallcode/src/core/session.ms
while self.toolCallCount < MAX_TOOL_LOOPS {
  let messages = self.buildMessages()

  let request = CompletionRequest {
    messages: messages,
    tools: self.router.getSchemas(null),
    temperature: 0.1,
    max_tokens: self.model.profile().max_output_tokens,
    stop: self.model.profile().stop_sequences,
    stream: true,
  }

  let response = self.model.completeStream(request, StreamHandler.new(
    onToken: |token| { self.eventBus.emit("model.token", { token: token }) },
    onComplete: || { self.eventBus.emit("model.complete", {}) },
    onEarlyStop: || { self.eventBus.emit("model.early_stop", {}) },
    maxOutputTokens: self.model.profile().max_output_tokens,
  ))
}

if self.toolCallCount >= MAX_TOOL_LOOPS {
  finalResponse += "\n\n[Reached tool call limit (${MAX_TOOL_LOOPS}). Stopping.]"
}
```

Pattern: small-model loop keeps temperature low, streams, caps tool recursion, and turns max-loop into visible assistant text.

```marrow
// tmp/smallcode/src/tools/router.ms
pub fn getSchemas(self, category: String?): List<Map<String, Any>> {
  match self.mode {
    "direct" => {
      return self.registry.schemas(null)
    }
    "two_stage" => {
      if !category {
        return [categoryTool()]
      }
      let tools = self.registry.byCategory(category)
      return self.registry.schemas(tools.map(|t| t.id))
    }
    "text" => {
      return []
    }
    _ => return []
  }
}
```

Pattern: two-stage routing shrinks schema load by asking for a category before exposing full tool schemas.

## Decision
- Designed for small models: reduce schema load, ask clarifying questions, compact aggressively.
- Best reusable ideas: two-stage tool routing and diff-first approval.

Confidence: 0.84

Caveats: some files are generated/Marrow pseudocode style; runtime completeness may vary.
