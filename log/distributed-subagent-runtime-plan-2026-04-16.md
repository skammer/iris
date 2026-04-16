# Distributed Subagent Runtime Plan

Date: 2026-04-16

## Summary

Goal: run subagents robustly across heterogeneous execution environments while keeping one consistent control model.

Target environments:
- local process
- bubblewrap
- docker / podman
- VM
- later: k8s job, SSH remote

Each environment should run the same child runtime shim. Parent/child protocol must not depend on one live socket or one specific substrate.

## Core Architecture

Split runtime into 5 layers:

1. Execution substrate
- responsibility: start / stop / isolate workload
- examples: local, bwrap, docker, podman, VM
- must not own workflow semantics

2. Identity / network plane
- default direction: Tailscale-compatible overlay, ideally self-hosted via Headscale
- gives stable identity, private addressing, ACL/grants, per-agent reachability
- Yggdrasil remains optional later transport/mesh plugin, not default control plane

3. Control plane
- parent launches child run
- child registers itself
- parent can signal pause / resume / cancel / message / approval
- child reports progress / heartbeat / checkpoints / final result

4. Message / event plane
- realtime path for ideal connectivity
- durable path for replay/catch-up after disconnects
- commands and events must survive intermittent links
- broker candidate: NATS JetStream

5. State plane
- runs
- heartbeats
- checkpoints
- inbox/outbox
- permissions/capabilities
- audit trail

## Why This Shape

- parent-child stdio alone is not robust enough for long-running work
- direct P2P alone is not durable enough
- network plane should provide identity/addressing, not orchestration semantics
- broker should provide replay/durable delivery, not sandboxing
- substrate should only execute/isolate

## Preferred Stack

Default practical stack:
- private network: Headscale/Tailscale
- durable broker: NATS JetStream
- control RPC: HTTP+SSE/WebSocket or gRPC
- durable DB: SQLite first, Postgres later

## Lifecycle Model

Each run gets:
- `run-id`
- `agent-id`
- `parent-run-id`
- `lease-id`
- `capabilities`
- `network identity`
- `checkpoint sequence`

Expected flow:
1. parent creates run record
2. runner launches child with bootstrap token/spec
3. child connects outbound to control/broker
4. child registers capabilities + heartbeat
5. child streams events live
6. child writes checkpoints periodically
7. if connection drops, child keeps working
8. parent recovers by reading latest heartbeat/events/checkpoint

## Communication Model

Need 2 modes simultaneously:

- live mode
  - logs
  - progress
  - token/tool updates

- durable mode
  - every important transition persisted as event
  - commands durable in inbox
  - parent can poll current run status when stream unavailable

Suggested subjects if using NATS:
- `agent.events.<run-id>`
- `agent.cmd.<run-id>`
- `agent.hb.<run-id>`
- `agent.checkpoint.<run-id>`

## Permissions And Limits

Policy must exist at multiple layers:

- network policy
  - overlay grants / ACLs

- runtime policy
  - CPU / memory / fs / net / tool limits

- broker policy
  - subject-scoped credentials

- execution policy
  - per-run allowlist
  - TTL
  - max cost
  - max tokens

## Agent Addressability

Each child should have both:
- stable logical ID, e.g. `agent://cluster/node/run`
- stable overlay address, e.g. tailnet IP / DNS

Routing rule:
- logical ID first
- direct IP second

Direct agent-to-agent traffic should be optional and policy-gated.

## Agent-To-Agent Interop

This is separate from parent-child orchestration.

Need explicit interop model:
- capability advertisement
- trust model
- addressing
- permission handshake
- request/reply semantics
- event subscription
- cancellation
- delivery guarantees

Minimum interop contract:
- `discover`
- `describe-capabilities`
- `send-message`
- `request-task`
- `stream-events`
- `checkpoint`
- `cancel`
- `ack`

Need support for:
- realtime direct communication when network healthy
- fallback to durable routed communication through broker/control plane
- per-agent allowlists for who may call/message whom
- quotas/rate limits per peer

## What Not To Do

- do not couple protocol to one substrate
- do not rely on raw stdio for long-running distributed subagents
- do not make each runner type invent its own child protocol
- do not use Yggdrasil as default policy/control solution
- do not expose direct agent-to-agent connectivity without identity + policy

## Recommended Implementation Order

1. runner protocol
- local
- bubblewrap
- docker/podman

2. run registry + lease model
- DB tables for runs, heartbeats, checkpoints, commands

3. event stream abstraction
- SQLite-backed first
- NATS backend next

4. child bootstrap shim
- same code path in every substrate

5. Headscale/Tailscale integration docs + policy model

6. agent-to-agent interop layer
- logical addressing
- capability exchange
- direct/routed messaging

## Notes On Network Options

Headscale/Tailscale:
- best default for private, policy-controlled per-agent networking
- better operational fit for production-first control plane

Yggdrasil:
- interesting for decentralized mesh scenarios
- better as optional transport/plugin later
- not ideal as default control plane/security layer

## Notes On Broker Choice

NATS JetStream is strong fit because it provides:
- durable streams
- durable consumers
- replay/catch-up
- request/reply
- clean subject model for per-agent inbox/outbox/event topics

## Immediate Next Recommended Build Steps

1. define runner protocol
2. define run registry schema
3. define bootstrap token/spec contract
4. define event/command/checkpoint interfaces
5. wire session/subagent runtime to emit durable run lifecycle state
6. only then add first remote substrate and agent-to-agent interop
