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

## Decision
- Strongest choice: session directory as full replay artifact.
- Weakest point: tool loop is simple sequential ReAct; less separation between loop and persistence than Iris/pi.

Confidence: 0.88

Caveats: Go/React paths inspected statically; no ACP session run executed.
