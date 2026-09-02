# Cron Jobs Architecture

#architecture #cron #scheduler #persistence

Status: implemented. Practical UI, agent-tool, CLI, API, notification, and
troubleshooting guidance lives in [[guides/cron-jobs|Cron jobs guide]].

## Execution model

Cron is a scheduler around the normal persisted Iris chat path, not a second
agent runtime.

```text
tool / CLI / API / UI
        -> cron service
        -> SQLite claim + fresh kind=cron session
        -> bounded runner -> chat/run!
        -> run ledger -> optional notification dispatcher
```

The prompt is the task. Skills are invoked inside it; scripts are requested
through normal tools. Every occurrence starts with fresh conversational context
but retains the full session transcript and can use global memory recall.

## Job and run invariants

- Jobs have stable UUIDs, case-insensitive unique names, typed schedules, IANA
  timezones, lifecycle state, and optimistic revisions.
- One-shot `at`, five-field cron, and anchored fixed-rate intervals are the only
  persisted schedule kinds. Relative delays are frozen to absolute instants.
- `next_run_at` is a derived UTC claim index, never the canonical schedule.
- Every claimed/manual run atomically gets one unique `kind=cron` session.
- Run snapshots freeze prompt, job revision, provider/model, tool policy, and
  notification destination; later edits cannot alter in-flight execution.
- Job deletion is soft. Run ledger, session transcript, partial output, and
  audit history remain.
- No scheduler transaction stays open during inference or delivery.

## Claiming and recovery

The DB-backed ticker atomically creates a run/session and advances the schedule.
A unique occurrence key prevents duplicate claims across reloads or competing
tickers. Execution is globally bounded and limited to one active run per job.

Overlapping scheduled occurrences and old misfires become auditable `skipped`
runs. Manual Run now rejects overlap and does not move the recurring schedule or
consume occurrence limits. Startup/reload abandons owned stale work instead of
replaying it; partial transcripts remain readable.

DST behavior belongs to schedule calculation: nonexistent local times are
skipped, repeated wall-clock slots run at most once, and UTC-anchored intervals
do not drift with completion time.

## Capability boundaries

Permissions, visible tools/actions, and approval policy remain separate layers.
Each job resolves a named tool profile; every run snapshots its permissions,
allowlists, action filters, and policy hash. Global roots, blocklists, command
rules, and approval gates can only restrict that profile.

Cron runs cannot see `cronjob` or general messaging tools. `cron_notify` is
injected only for `notification.policy=agent`; it accepts message content but no
destination and stages at most one audited message. An unattended sensitive
call that still needs approval fails the run with an approval ID.

## Notification boundary

Local execution, transcript, and result retention are unconditional.
Notification is a separate state machine:

- `never`: no success delivery.
- `always`: deliver final successful output.
- `agent`: deliver only content staged by `cron_notify`.
- `notify_on_error`: independent sanitized Iris-generated failure alert.

The target is resolved and frozen before execution. Delivery failure changes
notification status only; it never reruns inference or changes run outcome.

## Persistence and events

Migration `010-cron-jobs` adds `cron_jobs`, `cron_runs`, and `kind`/metadata to
sessions. `cron_runs` is the queryable ledger; session entries remain the full
transcript source of truth.

Durable event families:

- `cron.job.created|updated|paused|resumed|completed|deleted`
- `cron.run.claimed|started|succeeded|failed|cancelled|abandoned|skipped`
- `cron.notification.staged|succeeded|failed|suppressed`

Health separates scheduler state from provider/agent health and reports worker
capacity, active runs, due lag, tick timing/error, next wake, and failures.

## Modules

- `agent.cron.schedule`: typed schedules and next-fire calculation
- `agent.cron.store`: CRUD, claims, recovery, run history
- `agent.cron.runner`: bounded persisted chat execution
- `agent.cron.notification`: staged and adapter-neutral delivery
- `agent.cron.service`: lifecycle, ticker, health
- `agent.cron.cli`: command parsing/rendering
- `agent.tools.common.cron`: agent-facing `cronjob`/`cron_notify`
- `agent.api.routes.cron`, `agent.api.handlers.cron`, `agent.ui.cron`: surfaces

## Deferred boundaries

No natural-language schedule persistence, seconds cron, sub-minute intervals,
RRULE/holiday calendars, fan-out, artifact delivery, dependencies, per-job
workdirs, per-job retry/overlap policy, standing approval grants, transcript
merge into origin chat, or distributed scheduler provider.
