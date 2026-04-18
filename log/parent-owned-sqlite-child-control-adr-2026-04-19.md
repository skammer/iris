# Parent-Owned SQLite And Child Control Transport

Date: 2026-04-19

## Decision

Parent process owns durable SQLite state. Isolated children must not mount or write the parent SQLite database.

Container children use authenticated HTTP control endpoints:

- `POST /v1/runs/:run-id/control/register`
- `POST /v1/runs/:run-id/control/heartbeat`
- `POST /v1/runs/:run-id/control/checkpoint`
- `GET /v1/runs/:run-id/control/commands`
- `POST /v1/runs/:run-id/control/commands/:command-id/ack`
- `POST /v1/runs/:run-id/control/commands/:command-id/complete`
- `POST /v1/runs/:run-id/control/transition`

Auth is the per-run bootstrap token.

## Rationale

Shared writable SQLite across parent and Docker/Podman child violates isolation and is unreliable over bind mounts. It caused transient `SQLITE_IOERR_WRITE`, `SQLITE_CANTOPEN`, and duplicate-write ambiguity under polling.

Parent-owned persistence keeps authority, audit, replay, and recovery centralized. Children become isolated workers with a narrow control channel.

## Current Shape

Docker/Podman runner injects:

- `AGENT_CONTROL_URL`
- `AGENT_BOOTSTRAP_TOKEN`
- `AGENT_CHILD_SQLITE_PATH`

The child shim uses HTTP control when `AGENT_CONTROL_URL` is present. Direct SQLite remains only as a local-process/dev compatibility path.

## Follow-Up

- Move HTTP polling behind broker/event-plane abstraction.
- Add subject-scoped credentials for real broker backends.
- Add private network/overlay addressing for distributed children.
- Add richer child-local state/memory use for `AGENT_CHILD_SQLITE_PATH`.
