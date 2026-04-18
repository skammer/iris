# Orchestrator State Refactor

Date: 2026-04-19

## Decision

`agent.orchestrator` now uses one state atom:

```clojure
{:agents {}
 :channels {}
 :federated-peers {}
 :interop {:windows {}
           :deliveries {}
           :messages {}}}
```

## Rationale

Separate atoms made cross-structure invariants unsafe. Interop delivery, dedupe, message records, rate windows, agents, and peer state are related control-plane state and should update through one authority.

## Changes

- Replaced six atoms with one state atom and small access/update helpers.
- Split `send-interop-message!` into validation, target resolution, route selection, envelope build, delivery, persistence, and event emission helpers.
- Reworked rate limit mutation into a pure state transition plus CAS loop.
- Replaced bounded inbox/channel buffers with explicit sliding buffers.
- Removed self-namespace-qualified `logical-address` call.

## Follow-Up

- Persist interop/channel inbox overflow to DB if lossy sliding buffers are not acceptable for production.
- Extract interop routing into its own namespace if it grows further.
