# Agent Loop Architecture Summary

## Decision Map

### 1. Loop Owner
- Iris: pure loop; wrapper owns persistence/transport.
- Pi: session object owns event queue/persistence/extension middleware.
- Shelley: loop owns queue/history repair; embedding owns persistence.
- ZeroStack: Rig owns loop; TUI owns session/events.
- ZeroClaw: mixed functional loop plus OO agent.
- Forge: workflow runner owns step/terminal contract.
- SmallCode: compact session loop tuned for small models.
- Coddy: ReAct loop bound to ACP/session bundle.
- Little Coder: extensions around Pi, not forked loop.

Best decision: keep core loop pure/evented, put transport/persistence outside. Iris does this best.

### 2. Halting
- Hard caps: max steps/tool iterations/turns in Iris, Coddy, Forge, SmallCode, ZeroClaw, ZeroStack.
- Graceful finalization: ZeroClaw asks no-tool summary on max iterations; SmallCode appends tool-limit warning; Iris emits stopped/fallback paths.
- Cancellation: Iris tokens, Shelley contexts, ZeroClaw CancellationToken, Forge cancel_event, Gateway abort endpoint.
- Loop detection: Iris doom-loop/nudges, ZeroClaw detector, ZeroStack permission doom-loop.

Best decision: combine max cap + cancellation + loop detector + graceful final summary.

### 3. Restore
- Strong restore: Iris SQLite branch tree; Pi JSONL tree; Coddy session bundle; ZeroClaw SQLite/JSONL.
- Medium restore: ZeroStack JSON session with compactions.
- Weak restore: Forge core runner, Shelley loop core.
- SmallCode simple JSON snapshots.

Best decision: append-only or tree storage plus derived LLM context rebuild; never treat rendered UI as source of truth.

### 4. Tools
- Native/text dual parsing: ZeroClaw, SmallCode.
- Parallel batches: Iris, ZeroClaw.
- Sequential simple: Coddy, Shelley, Forge, SmallCode.
- Approval gating: Iris, Coddy, SmallCode, ZeroStack, ZeroClaw.
- Deferred/dynamic tools: ZeroClaw MCP filtering/tool_search; Coddy MCP naming.

Best decision: ordered result model even when executing in parallel.

### 5. Subagents
- Iris: durable child runtime with runs, heartbeats, checkpoints, commands.
- ZeroClaw: `llm_task`, `swarm`, `sessions_*`.
- Shelley: DB-backed or CLI subagent tools.
- Little Coder/Pi: multi-session/extensions, less durable child protocol.
- Others: limited/no native subagent.

Best decision: model subagents as runs/sessions with explicit lifecycle, not anonymous tool calls.

### 6. Long-Running Tasks
- Iris: run service with leases/checkpoints/replay.
- Forge: slot worker with priority/preemption.
- ZeroClaw: daemon, cron/routines, session state, cancellation tokens.
- ZeroStack: optional iterative `/loop` over plan file.
- Shelley: context/tool timeouts.

Best decision: durable run state plus heartbeat/checkpoint if task can outlive one chat turn.

### 7. User Handoff / Questions
- Iris: approval-required and queued turns.
- Coddy: ACP permission flow.
- Little Coder: extension UI requests.
- ZeroStack: TUI permission prompt.
- ZeroClaw: channel approval, ask_user, request_choice, poll, escalate.
- SmallCode: clarification loop and checkpoints.

Best decision: treat user questions as first-class loop states/events, not assistant prose only.

### 8. Events / Presentation
- Iris: typed SQLite events plus Datastar UI.
- Coddy: UI log, tool call store, SSE, replay.
- Pi: ordered session events plus extension events.
- ZeroStack: compact `AgentEvent` enum rendered by TUI.
- ZeroClaw: observer events, SSE buffer, WebSocket TurnEvents, runtime trace JSONL.
- Forge/SmallCode: callback/event bus.

Best decision: typed event stream for machines; separate compact renderer for humans.

### 9. Reconciliation
- Tool repair: Iris, Shelley, ZeroClaw.
- Client/server final sync: Coddy, ZeroClaw `chunk_reset` + done.
- Context compaction repair: Iris, Pi, ZeroClaw, ZeroStack.
- Event ordering: Pi serializes event processing.
- Queue serialization: Iris per-session queue; ZeroClaw session actor queue.

Best decision: reconcile at boundaries:
- before provider call: repair history
- after stream: authoritative final message
- before restore: rebuild context from durable log/tree
- before UI paint: replay typed events

## Reference Architecture

1. Core loop:
   - input context
   - provider call
   - parse tool calls
   - execute tools
   - append ordered results
   - stop/recur
2. Loop guards:
   - cancellation token
   - max iterations
   - context budget/compaction
   - duplicate/doom-loop detector
   - approval/user-question suspension
3. Persistence:
   - append-only event/message log
   - session tree/branch if edits/retries matter
   - compaction entries as durable derived state
4. Presentation:
   - stream tokens/tool status live
   - persist partial drafts for crashes
   - send authoritative final state
   - replay from durable state after reconnect
5. Long tasks:
   - create run
   - heartbeat
   - checkpoint
   - command queue/cancel
   - replay history

## Iris-Relevant Takeaways

- Keep Iris pure loop plus wrapper split.
- Borrow ZeroClaw session actor queue and partial-response persistence ideas.
- Borrow Pi steer/followUp queues as explicit message delivery modes.
- Borrow Shelley missing-tool-result repair simplicity.
- Borrow Coddy replay artifacts for richer tool-call restore.
- Borrow Forge terminal-tool contract for bounded workflows.
- Borrow SmallCode two-stage tool routing for small-model reliability.

Confidence: 0.9

Caveats: static architecture review; some repos delegate core behavior to external packages.
