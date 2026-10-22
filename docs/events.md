# Event and streaming contracts

## Identity and persistence

Runtime events carry `:request-id` for a specific turn and `:entity-type :session`
plus `:entity-id` for the conversation. Persistence normalizes type names to
strings. Consumers must support the boundary representation, not rely on a mix
of incidental keyword/string values.

The system event sink writes SQLite first, then observers/trace, then live broker
messages. Chat transcript persistence is a separate required runtime subscriber.
Its failure aborts successful completion and provider fallback. UI/telemetry
callbacks do not replace this required persistence contract.

The normal turn sequence is `agent-start`, then per-step `turn-start` and
`message-start`, deltas and tool events, followed by the appropriate terminal
message and `agent-end`. `message-end` can also describe a non-final tool/audit
message: consumers must inspect final/stop fields. A successful complete path
emits final `message-end` before `agent-end`. Approvals, cancellation, guardrail
exhaustion and provider errors have their own terminal payloads; do not infer
success just from channel closure.

`runtime.loop` terminal helpers define the precise event ordering; tests exercise
completion, retries, approvals, truncated tool calls, invalid result submissions,
cancellation and persistence failure. `turn-policy/advance` never emits events.

## Queue and cancellation

A cancelled worker retains session ownership until it exits. Cleanup compares
request identity before removing streaming state. The next queued request must
not lose its state to a delayed final event from the cancelled request.
Soft reload preserves the chat queue and executors. Full reload may interrupt
old turns after the replacement starts; it does not promise transparent replay
of an in-flight tool call.

## Routing and replay

Every event is published once to `events.all`. Operator streams retain
`events.>` subscriptions. Session events are additionally published to the
subject returned by `broker/session-events-subject`. Session subjects live
outside `events.>`, so an operator wildcard receives no duplicate.

UI chat, API chat and Telegram turns subscribe only to their session. The local
broker indexes exact subscriptions and scans only wildcard subscribers. Indexes
and subscription ownership change in one atom, so publish sees a coherent
snapshot. Unsubscribe removes the index entry and closes its channel.

Replay supports the all-events subject, `events.all`, and an exact session
subject. SQLite filters session, limit and after-id before returning results in
chronological ID order. Replay requires a persisted store; an ephemeral broker
has no durable replay. Live event payloads need not include a database ID;
replay IDs are not a promise of exactly-once live delivery.

## Overflow and recovery

Bounded sliding/dropping buffers count losses. The envelope, outside `:payload`,
contains cumulative `:broker/dropped-count`. Do not discard the envelope before
checking it. `streaming/check-message!` is shared by blocking `take!` and
`alts!!` consumers; the default raises `:event-stream-gap`.

- A passive UI session stream resynchronizes title, current messages and status
  after a new gap. It preserves a deliberately selected history window and
  leaves an owned POST stream responsible for its transcript. Repeated envelopes
  with the same loss count do not repeatedly refresh the entire transcript.
- A POST chat stream switches from accumulated deltas to authoritative streaming
  state after a gap. Its task-result fallback still sends the final transcript
  if terminal broker events were lost.
- API chat streams explicitly fail on a gap rather than silently claim complete
  delta delivery. Clients must refresh/reconnect.
- Telegram retains its task-result finalization fallback, so losing a terminal
  broker event need not lose the final answer.

Stream cleanup unsubscribes and releases worker/channel resources. Buffer loss
is not equivalent to task failure; result completion and event delivery are
independent signals.

## Search degradation

Vector recall falls back to lexical results on embedding/search exceptions.
It logs `:agent.memory/vector-search-degraded` with the controlled reason
`:embedding-search-failed`. No query, exception message, response body or
credential is included. A valid empty result emits no degradation event.
