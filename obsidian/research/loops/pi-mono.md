# Pi Mono Loop

## Shape
- Main orchestration in `tmp/pi-mono/packages/coding-agent/src/core/agent-session.ts`.
- Wraps `@mariozechner/pi-agent-core` Agent.
- `AgentSession` owns event queue, persistence, prompts, compaction, retries, queues, extensions, model/tool registries.
- `prompt()` transforms input, expands skills/templates, checks compaction, emits `before_agent_start`, then calls `agent.prompt(messages)`.

## Halting
- Core emits `agent_end`.
- Auto-retry handles retryable errors with backoff, removes transient error from core state, continues.
- Context overflow can trigger compaction then `agent.continue()`.
- Manual compaction aborts current agent and rebuilds state.

## Restore
- `session-manager.ts` uses append-only JSONL session tree.
- Entries have `id` and `parentId`.
- `buildSessionContext` walks branch to leaf.
- Latest compaction summary plus kept entries reconstruct context.
- Custom messages and branch summaries are first-class entries.

## Tools
- Core Agent handles tool execution; session translates core events to extension events.
- Extensions can intercept `tool_call`, `tool_result`, etc.
- Queued custom messages can be delivered as steer/followUp/nextTurn.

## Subagents / Long Tasks
- No separate child-worker runtime seen in inspected files.
- Multi-session/tree plus queued follow-ups support long interactive work.
- Shared event queue gives stable interface for clients.

## User Handoff
- `steer()` queues into current loop before next LLM call.
- `followUp()` queues after agent done/no tools.
- `sendCustomMessage()` and `sendUserMessage()` support delivery mode.
- Extensions can request UI and inject messages.

## Events / UI
- `AgentSessionEvent` includes queue_update, compaction_start/end, session_info_changed, thinking_level_changed, auto_retry_start/end.
- `_processAgentEvent` serializes handling, persists on message_end, emits extension event before external listeners.
- RPC client waits/collects events until `agent_end`.

## Reconciliation
- Event processing is serialized through a promise chain.
- Compaction rebuilds `agent.state.messages`.
- Auto compaction after agent_end can continue queued messages.
- Branch context plus compaction summary reconciles durable state to active LLM context.

## Decision
- Strongest choice: append-only session tree with steer/followUp queues.
- Best reusable idea: expose loop as evented session object with middleware and deterministic event ordering.

Confidence: 0.88

Caveats: lower-level `pi-agent-core` loop is package dependency; wrapper behavior inspected.
