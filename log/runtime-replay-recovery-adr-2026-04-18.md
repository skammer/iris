# Runtime Replay / Recovery ADR

Date: 2026-04-18

## Decision

1. Container substrates keep generic-image support as current default.
   - Mode: `:mounted-dev`
   - Contract includes required mounts, env, working dir, child command, pull policy.
   - Published runner image deferred; not required for current local/distributed baseline.

2. Broker remains SQLite-backed + local in-process for now.
   - Add durable replay from SQLite for events, commands, heartbeats, checkpoints, output.
   - Add request/reply semantics in local broker to shape later JetStream/NATS backend.
   - Keep SQLite as source of truth until real external broker lands.

3. Long-running recovery stays explicit and operator-visible.
   - Runs expose recovery plan.
   - API supports wait, recover, reclaim-stale.
   - Retry path carries prior checkpoint metadata and previous-run lineage.
   - Reclaim/retry policy remains opt-in via runner recovery config.

## Rationale

- Generic container image keeps setup simple while substrate contract stabilizes.
- Replay + request/reply solves current reliability gap without forcing external infra now.
- Explicit recovery is easier to audit and reason about than hidden supervisor behavior.

## Follow-up

- Add real JetStream backend.
- Add published runner image only if mounted-dev flow becomes limiting.
- Improve reclaim policy with backoff, scheduling, and cross-host failover.
