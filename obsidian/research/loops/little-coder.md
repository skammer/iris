# Little Coder Loop

## Shape
- It is mostly a Pi distribution: `tmp/little-coder/README.md`.
- Pi supplies main loop, providers, TUI, session tree, compaction, extension model, base tools.
- Little Coder adds extensions and benchmark harness around Pi.
- Hooks: `before_agent_start`, `context`, `before_provider_request`, `tool_call`, `tool_result`, `turn_end`, `session_compact`.

## Halting
- Base halting inherited from Pi.
- Benchmark RPC stops on `agent_end`.
- Extensions can steer after bad output; turn-cap extension can constrain runaway loops.
- Terminal-bench adapter has explicit `max_turns`.

## Restore
- Inherits Pi session tree/compaction.
- Evidence extension persists evidence through compaction.
- Benchmark adapter can start no-session mode or use RPC new_session.

## Tools
- Tool gating extension blocks unallowed tools via `tool_call`.
- Evidence, shell-session, browser, checkpoint, permission, skill, knowledge extensions enrich tool surface.
- Benchmark harness passes allowed tools explicitly.

## Subagents / Long Tasks
- No native subagent loop in Little Coder itself.
- Parallelism is mostly multi-session/benchmark orchestration.
- Terminal-Bench integration keeps external shell/tmux session state.

## User Handoff
- RPC client handles `extension_ui_request`:
  - `input`
  - `confirm`
  - `select`
  - `editor`
  - `notify`
- Permission/checkpoint extensions can ask user through this UI request path.

## Events / UI
- `benchmarks/rpc_client.py` demuxes JSONL responses/events/extension UI requests.
- Collects assistant text, tool_execution_start/end, turn_end, compaction_end.
- TUI presentation is Pi-owned; Little Coder consumes/augments events.

## Reconciliation
- Quality monitor checks `turn_end` and injects correction as steer message, max 2 consecutive corrections.
- Evidence compact re-injects preserved evidence after `session_compact`.
- Tool gating reconciles requested calls against allowed-tool policy.

## Decision
- Best pattern: extensions as loop middleware, not forks.
- Useful when experimenting with guardrails without destabilizing base loop.

Confidence: 0.84

Caveats: Pi core implementation lives in pi-mono; Little Coder itself is mostly wrapper/extensions.
