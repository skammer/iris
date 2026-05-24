# ZeroClaw Loop

## Shape
- Two loop styles:
  - functional tool loop: `crates/zeroclaw-runtime/src/agent/loop_.rs::run_tool_call_loop`
  - OO streamed turn: `crates/zeroclaw-runtime/src/agent/agent.rs::turn_streamed`
- Functional loop owns multimodal routing, native/text tool parsing, approvals, hooks, parallel execution, tracing, pacing, loop detection.
- Agent object owns memory loading, prompt building, classification, response cache, persistent in-memory history.

## Halting
- Max tool iterations default 10 when unset.
- CancellationToken aborts streaming/model/tool waits.
- Context overflow triggers fast trim/emergency trim, then retry.
- Loop detector escalates warning/block/break for exact repeat, ping-pong, no progress.
- Identical-output detection can abort after repeated same outputs.
- On max iterations, asks LLM for final no-tool summary; if that fails, errors.

## Restore
- Channel sessions use `SessionBackend`.
- JSONL backend: append-only `{workspace}/sessions/*.jsonl`.
- SQLite backend: `sessions.db`, WAL, metadata, FTS5, state tracking, JSONL migration.
- Gateway serializes per-session turns with `SessionActorQueue`.
- Interactive CLI history can load/save JSON state and self-heal orphan tool messages.

## Tools
- Native tools if provider supports them; otherwise XML/text parsing.
- Tool specs filtered by allowlist and dynamic MCP groups.
- Deferred tools can be activated by `tool_search`.
- Parallel execution when no approval-gated calls and no `tool_search`.
- Hooks can cancel or rewrite tool calls.
- Tool output is credential-scrubbed, truncated, optionally receipt-signed.

## Subagents / Long Tasks
- `llm_task`: single no-tool LLM subcall, optional JSON schema validation.
- `swarm`: sequential, parallel, or router-selected delegate agents.
- `sessions_*`: list/read/send/reset/delete sessions for inter-agent communication.
- Daemon/service infrastructure and cron/routines support scheduled/long-running operation.

## User Handoff
- Approval manager gates risky tools.
- Non-interactive channel approvals call `Channel::request_approval`.
- `ask_user` sends channel question and waits up to 300s by default; choices use native `request_choice` when available.
- `poll`, `reaction`, `escalate_to_human` use late-bound channel map pattern.

## Events / UI
- `StreamDelta`: Text and Status for channels/drafts.
- `TurnEvent`: Chunk, Thinking, ToolCall, ToolResult for WebSocket.
- Gateway persists partial assistant output every 500ms during streaming.
- SSE broadcasts observer events and keeps recent event buffer.
- Runtime trace stores JSONL events in rolling/full mode.

## Reconciliation
- History pruning removes orphaned tool messages before provider call.
- Context compressor aligns boundaries to avoid splitting tool_call/tool_result pairs.
- Compression persists summary to memory before discarding old messages.
- Gateway sends `chunk_reset` before authoritative `done` so client discards speculative draft.
- Cancellation persists partial assistant with `[interrupted by user]`.

## Code Pattern

```rust
// tmp/zeroclaw/crates/zeroclaw-runtime/src/agent/tool_execution.rs
pub fn should_execute_tools_in_parallel(
    tool_calls: &[ParsedToolCall],
    approval: Option<&ApprovalManager>,
) -> bool {
    if tool_calls.len() <= 1 {
        return false;
    }

    if tool_calls.iter().any(|call| call.name == "tool_search") {
        return false;
    }

    if let Some(mgr) = approval
        && tool_calls.iter().any(|call| mgr.needs_approval(&call.name))
    {
        return false;
    }

    true
}
```

Pattern: parallelism is opt-out for dependency-creating or approval-gated tools.

```rust
// tmp/zeroclaw/crates/zeroclaw-runtime/src/agent/loop_.rs
let det_result = loop_detector.record(&tool_name, args, &outcome.output);
match det_result {
    crate::agent::loop_detector::LoopDetectionResult::Ok => {}
    crate::agent::loop_detector::LoopDetectionResult::Warning(ref msg) => {
        tracing::warn!(tool = %tool_name, %msg, "loop detector warning");
        history.push(ChatMessage::system(format!("[Loop Detection] {msg}")));
    }
    crate::agent::loop_detector::LoopDetectionResult::Block(ref msg) => {
        tracing::warn!(tool = %tool_name, %msg, "loop detector blocked tool call");
        history.push(ChatMessage::system(format!(
            "[Loop Detection — BLOCKED] {msg}"
        )));
    }
    crate::agent::loop_detector::LoopDetectionResult::Break(msg) => {
        runtime_trace::record_event(
            "loop_detector_circuit_breaker",
            Some(channel_name),
            Some(provider_name),
            Some(model),
            Some(&turn_id),
            Some(false),
            Some(&msg),
            serde_json::json!({
                "iteration": iteration + 1,
                "tool": tool_name,
            }),
        );
        anyhow::bail!("Agent loop aborted by loop detector: {msg}");
    }
}
```

Pattern: loop detector can warn, block, or break; warnings feed back into context as system nudges.

## Decision
- Most complete system: channel-first, daemon-ready, multi-provider, multimodal, strong observability.
- Cost: many loop paths; same concepts exist in functional loop and Agent OO loop.
- Best reusable ideas: session actor queue, late-bound channel tools, trace JSONL, partial persistence, context repair.

Confidence: 0.9

Caveats: broad repo; inspected main runtime/gateway/session/tool paths, not every channel adapter.
