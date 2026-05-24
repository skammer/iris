# ZeroStack Loop

## Shape
- Thin Rig wrapper in `tmp/zerostack/src/agent/runner.rs`.
- `spawn_agent` calls `agent.stream_chat(prompt, history)` and maps Rig multi-turn stream to `AgentEvent`.
- UI owns session mutation, rendering, cancellation, slash commands.
- Agent builder sets preamble, max tokens, max turns, tools, MCP tools.

## Halting
- Rig agent `default_max_turns` from config controls tool loop bound.
- `Ctrl+C` while running drops `agent_rx`, marking UI interrupted.
- `/loop stop` stops iterative loop state.
- Permission checker detects doom loops: same tool/input 3+ times can ask or deny.

## Restore
- Sessions are pretty JSON under data dir `zerostack/sessions/{id}.json`.
- Session stores messages, compactions, token/cost estimates, model/provider, working dir, permission allowlist.
- `/sessions` lists/loads/deletes by prefix.
- `convert_history` reconstructs Rig messages with latest compaction summary.

## Tools
- Built-ins: read/write/edit/bash/grep/find_files/list_dir/todo.
- Permission layer supports standard/restrictive/accept/yolo.
- Ask flow: allow once, allow always, deny; allow-always pattern saved to session.
- MCP tools collected when feature enabled.

## Subagents / Long Tasks
- No native subagent.
- Optional `/loop` feature runs iterative coding loop over `LOOP_PLAN.md`, relaunching agent after each done event.
- Loop transcript writes per-iteration JSON under data dir `zerostack/loops/{session_id}`.

## User Handoff
- Permission prompts go through `AskRequest` channel and TUI keys.
- `/retry`, `/undo`, `/compress`, `/prompt`, `/mode` are user-controlled handoff points.
- While `/loop` active, normal input is blocked until `/loop stop`.

## Events / UI
- `AgentEvent`: Token, Reasoning, ToolCall, ToolResult, Error, Done.
- UI renders tokens incrementally, tool summaries compactly, optional tool result details.
- Reasoning visibility toggled with `Ctrl+R` or `/reasoning`.

## Reconciliation
- UI buffers streamed response and replaces rendered lines until Done.
- On Done, assistant message appended, compaction maybe runs, session saved.
- Compression keeps recent token budget, summarizes older messages with model, rebuilds agent.

## Decision
- Minimal architecture: use Rig as loop engine; app owns UX/session.
- Best reusable ideas: simple event enum, permission allowlist saved in session, optional external iterative loop.

Confidence: 0.86

Caveats: Rig internals not inspected; ZeroStack delegates core multi-turn semantics to Rig.
