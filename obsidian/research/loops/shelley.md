# Shelley Loop

## Shape
- Main loop in `tmp/shelley/loop/loop.go`.
- `Loop.Go(ctx)` runs forever: drain queued user messages into history, call `processLLMRequest`, else wait on notify.
- `ProcessOneTurn` drains once for tests/non-daemon use.
- `processLLMRequest` loops internally: build request, repair history, call LLM, append assistant, execute tools, repeat until no tool use.

## Halting
- Outer loop halts on context cancellation.
- LLM call has 5 minute timeout and retryable network retry max 2.
- `max_tokens` truncation records excluded assistant message plus user-visible system error and ends turn.
- Stop reason not `tool_use` ends turn.

## Restore
- Loop is given `history` and `recordMessage` callback.
- Persistence is delegated to embedding app/DB, not built into loop.
- History repair prevents provider errors after crashes/cancels.

## Tools
- `executeToolCalls` runs tool_use blocks sequentially.
- Each tool result is appended as a user message with `tool_result` content.
- Tool context carries working dir, progress callback, tool use id.

## Subagents / Long Tasks
- `claudetool/subagent.go` can spawn/interact with DB-backed subagent conversations.
- `claudetool/cli_subagent.go` delegates to external `claude` or `codex exec`, timeout default 5m, max 30m.
- Long tasks rely on Go context cancellation and per-tool timeout behavior.

## User Handoff
- `QueueUserMessage` appends to queue and notifies loop.
- If user interrupts during tool execution, queued messages are appended after current tool results before next LLM call.
- No generic structured ask_user tool seen in loop.

## Events / UI
- Streaming callbacks for text/progress.
- `recordMessage` callback persists messages and usage.
- Git state callback after turn can notify external UI.

## Reconciliation
- `insertMissingToolResults` inserts synthetic error results for missing tool_use results.
- It removes orphan tool_results not matching immediately previous assistant message.
- This repairs cancellation/crash/provider-history edge cases.

## Decision
- Simple, robust Go loop: queue plus repair function.
- Best reusable idea: explicit interruption handling between tool batch and next LLM call.

Confidence: 0.9

Caveats: persistence/UI embedding outside loop not fully traced.
