# Coddy Agent Loop

## Shape
- ReAct loop in `tmp/coddy-agent/internal/agent/react.go`.
- `Agent.Run(ctx, prompt)` appends user message, loads memory/skills/tools, restores plan, builds messages, loops up to `maxTurns` default 30.
- Each turn rebuilds system prompt, streams LLM output, persists usage/UI events, appends assistant, executes tool calls, appends tool results, repeats.
- ACP-first design; HTTP/direct modes also exist.

## Halting
- Stops when assistant returns no tool calls, provider returns end-turn/max-tokens, maxTurns exceeded, or context cancel fires.
- Tool denial is not fatal: permission denial becomes tool result text.
- UI stop aborts SSE reader and sends cancel endpoint.

## Restore
- Filesystem session bundle under root/session id:
  - `session.json`
  - `messages.json`
  - `ui_log.json`
  - `permission_grants.json`
  - `todos/active.md`
  - `tool_calls/<id>/{args.json,result.md,meta.json}`
- `internal/session/manager.go` loads session, reconnects MCP, restores messages, plan, UI log, permissions.

## Tools
- Tool set is mode-dependent.
- MCP tools are available through `server__tool` naming.
- Tool calls execute sequentially.
- Permission grants persist per session.

## Subagents / Long Tasks
- No first-class subagent runtime found.
- Long tasks are represented as streaming tool calls/UI logs; no durable worker protocol like Iris.

## User Handoff
- ACP permission flow supports request/approve/deny.
- User confirmations become grant state or tool result denial.
- Reconnect/replay can restore UI state after client reload.

## Events / UI
- ACP/session updates plus OpenAI-like SSE.
- React UI consumes raw events: deltas, token usage, memory phases/chunks, tool_call, tool_call_update.
- Tool queue flushed by requestAnimationFrame; final assistant reconciled from server messages after stream.

## Reconciliation
- `manager_replay.go` replays conversation from persisted messages/tool metadata into UI events.
- UI syncs final assistant from `/coddy/sessions/:sid/messages` with retries.
- Tool call store keeps args/result/meta so replay can reconstruct rich tool UI.

## Code Pattern

```go
// tmp/coddy-agent/internal/agent/react.go
maxTurns := a.cfg.Agent.MaxTurns
if maxTurns <= 0 {
	maxTurns = 30
}

for turn := 0; turn < maxTurns; turn++ {
	if ctx.Err() != nil {
		return string(acp.StopReasonCancelled), nil
	}

	if len(response.ToolCalls) == 0 {
		stopReason := response.StopReason
		if stopReason == "" || stopReason == "end_turn" {
			return string(acp.StopReasonEndTurn), nil
		}
		if stopReason == "max_tokens" {
			return string(acp.StopReasonMaxTokens), nil
		}
		return string(acp.StopReasonEndTurn), nil
	}
}

return string(acp.StopReasonMaxTurns), nil
```

Pattern: conventional ReAct loop with hard turn cap, cancellation check, and no-tool final exit.

```go
// tmp/coddy-agent/internal/session/manager_replay.go
for _, tc := range msg.ToolCalls {
	_ = m.server.SendSessionUpdate(sessionID, acp.ToolCallUpdate{
		SessionUpdate: acp.UpdateTypeToolCall,
		ToolCallID:    tc.ID,
		Title:         tc.Name,
		Kind:          replayToolKind(tc.Name),
		Status:        "pending",
	})
}
```

Pattern: persisted tool calls replay into UI events, so restore is richer than plain message history.

## Decision
- Strongest choice: session directory as full replay artifact.
- Weakest point: tool loop is simple sequential ReAct; less separation between loop and persistence than Iris/pi.

Confidence: 0.88

Caveats: Go/React paths inspected statically; no ACP session run executed.
