# Cron Jobs Plan

Status: implemented. Migration, runtime, notifications, tool, CLI, API, and Web
UI complete.

## Implementation result

- Migration 010 persists jobs, runs, and `kind=cron` sessions.
- Five-field cron uses `cron-utils`; `at` and anchored intervals use `java.time`.
- DB claims, bounded workers, timeout/cancellation, misfire/overlap handling, and
  stale-run recovery are wired into system start/stop/reload.
- Run snapshots freeze provider/model and named tool profile; visible schemas and
  execution both enforce action allowlists.
- `cronjob` and bound `cron_notify` tools, rich Telegram delivery, CLI, JSON API,
  and server-rendered Cron UI share the same service layer.
- Tests cover schedule arithmetic, migration/session isolation, atomic claims,
  overlap, and queued manual-run recovery.

## Goal

Persistent scheduled agent jobs manageable through agent tools, CLI, HTTP API,
and web UI. Each run executes a self-contained prompt, records an audit trail,
and can optionally notify through Telegram or future channel adapters.

## Product contract

### Job

- Stable UUID `id`; case-insensitive unique `name` for safe CLI/tool lookup.
- Required: `name`, `prompt`, typed `schedule`, IANA `timezone`.
- Lifecycle status: `active`, `paused`, `completed`, `deleted`. `running` is
  derived from an active run and never overwrites lifecycle state.
- Execution and retention are always local: the job may call its allowed tools,
  and its session/run result is always saved. Outbound notification is a separate,
  optional policy:
  - `never`: execute and retain only; default.
  - `always`: send the final response after every successful run.
  - `agent`: expose a restricted `cron_notify` tool; send only when the agent
    explicitly stages an alert.
- Notification target is structured data, not a Telegram-specific string:
  - `origin`: resolve adapter + recipient from tool-call context at job creation.
  - `channel`: explicit adapter + recipient; v1 supports Telegram.
- `notify_on_error` is independent from policy and defaults to true when a target
  exists. Iris generates the failure alert; it does not ask the failed agent to
  explain its own failure.
- Optional model/provider pin. Resolution: job pin -> cron config -> active
  global provider/model. Every run snapshots the resolved values.
- Optimistic `revision` prevents an old UI/tool edit from overwriting a newer
  job definition.
- Create/update validates schedule, timezone, notification policy/target, and
  shows the next five fire times before saving.

### Inference selection

Only inference identity is job-scoped in v1:

- `provider`: configured Iris provider key, for example `deepseek` or `ollama`.
- `model`: configured model ID belonging to that provider.

API keys, base URLs, API flavor, temperature, token limits, reasoning level,
cache/stream settings, retries, and provider timeouts remain global provider/cron
configuration. Permission profile and run timeout are separate job concerns.

UI options:

1. `Cron default` (default): inherit `:cron/:provider` + `:cron/:model`, then the
   active global pair if cron defaults are unset. UI always shows the currently
   resolved pair and warns that a config reload may change future runs.
2. `Pinned`: choose one configured provider/model pair. Config reload may update
   credentials/endpoints for that provider but cannot silently move the job to a
   different model.

v1 decision: combine fleet-wide Cron default with optional per-job pin. UI shows
one compact `Model: Cron default (provider/model)` row plus an advanced
`Pin provider/model` toggle. Do not expose raw text fields or every LLM knob.
API/CLI use the same two modes. Every run snapshots the resolved pair; an
unavailable pinned pair fails before inference and can trigger `notify_on_error`.

Current provider registry/API must be extended to enumerate every configured
model under each provider; today it effectively exposes only each provider's
selected model. Secrets never enter job rows or API/UI responses.

Deferred alternatives:

- Active global model only: simplest, but an unrelated chat-model reload silently
  changes every unattended job.
- Logical profiles such as `cheap`, `strong`, `local`: cleaner at larger scale,
  but need a new inference-profile config contract and migration semantics.
- Arbitrary per-job generation knobs: excessive provider-specific UI and support
  matrix for v1; add only after concrete workloads require them.

### Run

- Every execution gets a new persisted Iris session. It starts with fresh
  context—no previous run or origin-chat history—but its complete transcript is
  durable: prompt, assistant messages, thinking/tool entries, tool results,
  errors, completion metadata, and partial progress after interruption. Global
  memory recall remains available.
- One session belongs to exactly one cron run and is never reused by the next
  occurrence. `cron_runs.session_id` provides the stable link in both directions.
- Run snapshot freezes prompt, schedule revision, notification, model/provider,
  and permission policy. Editing a job never changes an in-flight run.
- Run states: `claimed`, `running`, `succeeded`, `failed`, `cancelled`,
  `abandoned`, `skipped`.
- Notification states are independent: `not_configured`, `not_requested`,
  `staged`, `pending`, `succeeded`, `failed`, `suppressed`. Notification failure
  does not rerun the LLM.
- Final output, error, usage, duration, request ID, scheduled time, actual start,
  and completion time remain queryable in run history.
- In `agent` mode, `cron_notify` accepts message content only. Adapter and
  recipient are frozen in the run snapshot, so the model cannot redirect alerts.
  One notification may be staged per run; the complete transcript and final
  response are still retained whether or not it stages one.
- `run now` works for active or paused jobs, does not alter the recurring next
  fire, and refuses overlap with an already-running instance.

### Scheduling semantics

- Persist a typed schedule, never an ambiguous executable string:
  - `cron`: standard five-field Unix expression; calendar-based in job timezone.
  - `at`: one UTC instant; one-shot and becomes `completed` after its occurrence.
  - `interval`: duration in whole seconds plus immutable UTC anchor; fixed-rate,
    not "delay after completion".

```clojure
{:kind :cron :expression "0 9 * * 1-5"}
{:kind :at :at "2026-08-10T06:00:00Z"}
{:kind :interval :every-seconds 7200 :anchor-at "2026-08-06T12:00:00Z"}
```

- Minimum interval is 60 seconds. Six-field/seconds cron is excluded: agent runs
  are too expensive and long-lived for sub-minute calendar scheduling.
- Relative input such as `in 30m` is CLI/tool UX sugar converted immediately to
  an absolute `at` instant. It is never persisted as a relative duration.
- Daily/weekly builders in UI compile to five-field cron. Natural-language
  interpretation belongs to the calling agent/UI, not scheduler persistence.
- Optional `max_occurrences` limits scheduled claims; `at` fixes it to 1, recurring
  schedules default to unlimited. Manual `run now` does not consume the limit.
- Store `next_run_at` as a derived UTC cache for indexed claims. Recompute it only
  from canonical schedule + timezone + occurrence state, never from display text.
- DST contract: nonexistent local times are skipped; a repeated wall-clock slot
  runs at most once.
- Intervals remain anchored to UTC across DST and are calculated from the previous
  scheduled instant, not completion time, so slow runs do not cause clock drift.
- Default overlap policy: one active run per job. A due occurrence while its job
  is still running becomes `skipped` and advances the schedule.
- Default misfire policy: fire once when downtime delay is within configurable
  grace; otherwise record `skipped` and compute the next future occurrence. No
  backlog replay.
- No automatic agent retry in v1. Manual `run now` is explicit and auditable.

## Architecture

```text
tool / CLI / API / UI
        |
        v
cron service API -> SQLite cron_jobs + cron_runs + sessions
        |
        v
DB-backed ticker -> atomic claim -> bounded executor
        |                              |
        |                              v
        |                         chat/run!
        |                              |
        v                              v
next_run_at update              stored final result
                                       |
                                       v
                           notification dispatcher
                              channel adapter
```

Do not introduce a second agent loop. The cron runner creates a fresh persisted
session, then adapts the existing `chat/run!` path with that session ID, cron
request ID, bounded runtime, filtered tools, and cron permissions.

### New modules

- `agent.cron.schedule`: validate typed schedules/timezones; compute next runs.
- `agent.cron.store`: job/run CRUD, atomic claim, reconciliation, history.
- `agent.cron.runner`: execute frozen run snapshot through `chat/run!`.
- `agent.cron.notification`: stage and deliver adapter-neutral notifications.
- `agent.cron.service`: ticker, bounded executor, lifecycle, health.
- `agent.tools.common.cron`: unified agent-facing `cronjob` tool.
- `agent.api.routes.cron`, `agent.api.handlers.cron`: JSON API.
- `agent.ui.cron`: Datastar-rendered management UI.
- `agent.cron.cli`: isolated cron subcommand parser/renderer.

### Existing seams to extend

- `agent.system.components` / `agent.system`: create, start, reload, stop cron
  service without leaving duplicate workers after soft/full reload.
- `agent.chat.turn` / `agent.chat.kernel-ops`: accept permission profile,
  request identity, and filtered tool set instead of hardcoding `:chat`.
- `agent.channels.core`: adapter lookup and delivery capability validation.
- Telegram adapter outbound path: honor rich Markdown metadata and reuse current
  rich chunking/fallback behavior.
- `agent.persistence.sqlite`: migration facade, counts, health.
- `agent.api`, route aggregation, UI tab/router, dashboard scheduler health.
- `resources/config/default.edn` and config validation.

## Persistence

Migration `010-cron-jobs` adds:

### `cron_jobs`

- `id`, `name`, `prompt`, `schedule_json`, `timezone`, `status`
- `notification_json`, `provider`, `model`, `tool_profile`
- `next_run_at`, `last_run_at`, `last_run_status`
- `run_count`, `failure_count`, `occurrence_count`, `max_occurrences`, `revision`
- `created_by`, `origin_json`, `created_at`, `updated_at`, `deleted_at`
- Unique index on `lower(name)` for non-deleted rows.
- Due-job index on `(status, next_run_at)`.

### `cron_runs`

- `id`, `job_id`, `job_revision`, `trigger`, `scheduled_for`
- `status`, `notification_status`, `request_id`, `session_id`, `owner_id`
- `snapshot_json`, `output`, `error`, `usage_json`, `notification_json`
- `claimed_at`, `started_at`, `finished_at`, `created_at`
- `session_id` is unique, non-null for claimed/manual executions, and references
  `sessions(id)` with deletion restricted. A run/session pair is immutable.
- Unique scheduled claim key per job occurrence; manual runs use unique run IDs.
- Indexes for job history, active runs, and recent failures.

### Session integration

- Extend `sessions` with `kind` (`chat` default, `cron`) and `metadata_json`.
  Cron metadata stores `cron_job_id`, `cron_run_id`, trigger, and scheduled time.
- Session title is deterministic and readable: `Cron: <job name> · <scheduled
  local time>`. Rename is unnecessary; job/run IDs remain authoritative.
- Claim transaction creates both the run and its session, then advances
  `next_run_at`. Failure of any insert rolls back the whole claim; an executable
  run can never exist without a durable session.
- Existing message/session-entry/completion persistence stays the transcript
  source of truth. `cron_runs.output`, usage, and error remain denormalized run
  summaries for fast history, notification, and audit after later UI changes.
- Empty, partial, failed, cancelled, and abandoned run sessions are retained.
  Deleting a job is soft-delete and never cascades into runs or sessions.
- Normal Chat sidebar defaults to `kind=chat` to avoid cron noise. Cron run detail
  links to the full session transcript; session APIs support an explicit kind
  filter rather than making cron sessions inaccessible.

All state transitions use conditional SQL updates. No scheduler transaction stays
open during agent execution or notification delivery.

## Scheduler and recovery

1. Ticker wakes every 15 seconds by default.
2. Transaction selects due jobs and atomically creates each immutable run claim
   plus its persisted `kind=cron` session.
3. Same transaction advances each job's `next_run_at`; unique claim prevents a
   second process/reload from claiming the same occurrence. Failed session
   creation rolls back the claim and schedule advance.
4. Bounded executor runs at most `max-concurrency` jobs globally and one per job.
5. Runner calls `chat/run!` with the run's persisted session ID, request ID equal
   to cron run ID, `:cron` permissions, and the `cronjob` tool removed.
6. Chat persistence records the complete transcript as it happens. Final result
   and usage are copied to the run ledger. Notification delivery changes only
   notification state/result and never changes run outcome.
7. Startup marks stale `claimed`/`running` rows `abandoned`; they are not replayed.
   Their jobs continue from already-computed `next_run_at`.
8. Stop/reload rejects new claims, cancels worker tokens, waits a bounded grace,
   then marks unfinished owned runs `abandoned`.

Recommended config:

```clojure
:cron {:enabled true
       :poll-interval-seconds 15
       :max-concurrency 2
       :run-timeout-seconds 1800
       :misfire-grace-seconds 3600
       :timezone "UTC"
       :provider nil
       :model nil
       :output-max-chars 200000}
```

Use a small Java/Clojure adapter around `cron-utils` only for five-field cron
validation and `ZonedDateTime` next-fire calculation. Implement `at` and anchored
interval arithmetic directly with `java.time`. Keep scheduler ownership, claims,
retries, and persistence in Iris.

## Security

- Add `:cron-read` and `:cron-manage` permissions.
- Unified `cronjob` tool read actions: `list`, `get`, `history`.
- Mutating actions: `create`, `update`, `pause`, `resume`, `run`, `delete`.
- Creating/updating/running a persistent job through an agent tool is
  approval-sensitive. Approval card shows exact prompt, schedule, timezone,
  notification policy/target, model, and resolved tool profile.
- Cron runs use their resolved tool profile and cannot see or execute the
  `cronjob` tool. This is the recursion/runaway-spend guard.
- `cron_notify` is available only for jobs with `notification.policy=agent`. It
  has no adapter/recipient input and only stages one audited message against the
  current run. It does not grant general messaging access.
- Existing per-call approval rules remain; an unattended run requiring approval
  ends `failed` with the approval ID in its audit result. Job-scoped standing
  grants are explicitly out of v1.
- General messaging tools remain unavailable. `always` notification uses the
  final response; `agent` notification uses only staged `cron_notify` content.
  Both go through the notification dispatcher and configured target.
- Direct UI/CLI/API mutations remain protected by existing API/operator trust
  boundaries and are fully event-logged.

### Tool policy selection

Permissions, tool visibility, and approvals are different layers:

- `permissions`: capability tokens required by execution, such as
  `filesystem-read`, `http-request`, or `shell-exec`.
- `allowed-tools` + `allowed-actions`: what the model can see/request. Action
  filtering matters for multiplexed tools: an observe profile may allow HTTP
  `GET/HEAD` but not `POST/PUT/PATCH/DELETE`, and Home Assistant reads but not
  `call_service`.
- approvals: independent runtime gate for sensitive inputs. Selecting a broader
  profile never bypasses approval policy.

Options:

1. One fleet-wide profile only: safest/simplest, but insufficient for mixed
   monitoring and automation jobs.
2. Fleet default + per-job named profile: same UX shape as model selection;
   selected for v1.
3. Inline per-job permission/tool editor: maximum flexibility, but difficult to
   review, migrate, and secure; defer.
4. Standing approval grants: required for unattended sensitive actions, but a
   separate capability with tool/input/scope/expiry rules; defer.

Selected config shape:

```clojure
:tools {:profiles
        {:cron-observe {:permissions [:filesystem-read :http-request
                                      :memory-read :homeassistant]
                        :allowed-tools [:fs_read :fs_list :fs_search :http
                                        :memory_recall :vault_search
                                        :message_search :homeassistant]
                        :allowed-actions {:http [:get :head]
                                          :homeassistant [:get_state :list_states
                                                          :search_states
                                                          :list_services]}}
         :cron-automation {:permissions [:filesystem-read :filesystem-write
                                         :http-request :shell-exec]
                           :allowed-tools [:fs_read :fs_list :fs_search :fs_write
                                           :http :shell]}}}
:cron {:tool-profile :cron-observe}
```

Job policy has two modes:

- `Cron default` (default): resolve `:cron/:tool-profile` at run time.
- `Pinned profile`: store another configured profile key on the job.

UI mirrors model selection: compact `Tools: Cron default (cron-observe)` plus an
advanced profile dropdown and effective capability preview. Agent-created jobs
may request a profile, but approval shows the fully resolved tool/action set.
Profiles are operator configuration; jobs cannot embed new permissions.
Config validation rejects unknown tools/actions and permission mismatches.

Every run snapshots profile key, permissions, allowed tools/actions, and policy
hash. Global blocklists, roots, command rules, and adapter configuration remain
hard upper bounds. `cronjob` and general messaging tools are always removed;
`cron_notify` is injected only by notification policy. Model-visible schemas are
filtered before inference and the same policy is rechecked at execution.

## Surfaces

### Agent tool

One `cronjob` tool with action-dependent validation and compact structured
results. Tool context resolves `origin` only when adapter and recipient are
present. Missing notification config means `policy=never`; a notification policy
other than `never` requires a valid target.

### CLI

```text
iris cron list [--status active]
iris cron get <id-or-name>
iris cron create --name NAME (--cron EXPR | --at INSTANT | --every DURATION) --timezone ZONE --prompt TEXT [--notify POLICY --target TARGET]
iris cron update <id-or-name> [fields...]
iris cron pause|resume|run|delete <id-or-name>
iris cron runs [id-or-name] [--limit N]
iris cron status
```

Put parsing in `agent.cron.cli`; do not add more cron flags to the current global
prompt parser. CLI `run` enqueues a manual run for the daemon; it does not start a
second scheduler.

### HTTP API

- `GET/POST /v1/cron/jobs`
- `GET/PATCH/DELETE /v1/cron/jobs/:id`
- `POST /v1/cron/jobs/:id/pause`
- `POST /v1/cron/jobs/:id/resume`
- `POST /v1/cron/jobs/:id/run`
- `GET /v1/cron/jobs/:id/runs`
- `GET /v1/cron/runs/:id`
- `GET /v1/cron/status`

Create returns `201`; manual run returns `202`; stale revision or active overlap
returns `409`; validation returns `400`; missing job returns `404`.

### Web UI

- New `Cron` top-level tab between Chat and Tools.
- Header metrics: scheduler state, active jobs, running jobs, recent failures.
- Job table: name, lifecycle state, schedule + timezone, next/last run,
  notification policy/target, last result. Clear active-row highlight and badges.
- Create/edit panel starts with `Once`, `Every`, and `Cron` schedule modes. Cron is
  the advanced mode; common daily/weekly controls generate it. Always preview
  timezone, next runs, and the resolved human-readable schedule before saving.
- Same panel includes optional occurrence limit, tool profile, notification
  policy, target, and `notify_on_error`. `agent` mode explains that prompt must
  call `cron_notify` only when an alert is warranted.
- Row actions: pause/resume, run now, edit, delete.
- Job detail: prompt, immutable revision metadata, run history, duration/tokens,
  notification state, expandable output/error, request-ID link into Logs, and a
  session link opening the complete persisted transcript.
- Datastar polling first; reuse current server-rendered fragments. Add SSE only if
  polling produces visible latency or unnecessary full-list morphs.

## Events and health

Emit durable events:

- `cron.job.created|updated|paused|resumed|completed|deleted`
- `cron.run.claimed|started|succeeded|failed|cancelled|abandoned|skipped`
- `cron.notification.staged|succeeded|failed|suppressed`

Cron service health reports enabled/running, worker count, active run count,
oldest due lag, last tick, last tick error, next wake, and recent failures.
Dashboard shows unhealthy scheduler separately from agent/provider health.

## Notification details

- There is no `local` delivery target. Local execution, transcript, and run result
  retention are unconditional; `policy=never` means no outbound notification.
- `origin` is resolved and frozen at creation, never guessed at fire time.
- `always` sends successful final output. `agent` sends only the message staged by
  `cron_notify`; no tool call means `notification_status=suppressed`.
- `cron_notify` persists intent before returning but performs no network I/O.
  Dispatcher sends it only after a successful run; failed/cancelled/abandoned runs
  use the sanitized `notify_on_error` path instead.
- Avoid a magic `[SILENT]` response contract: explicit tool intent is easier to
  prompt, validate, audit, and distinguish from an accidentally empty response.
- `notify_on_error` sends a short Iris-generated alert containing job name, run
  ID, error category, and completion time; it never includes secret-bearing tool
  inputs/results. Full details remain in the persisted session.
- Telegram uses existing rich-message chunking and fallback. Dispatcher adds a
  compact job header, then renders model-supplied Markdown as the message body.
- Adapter unavailable/missing target produces `notification_status=failed`;
  output remains local and later schedules continue.
- v1 permits one target and at most one agent-staged message per run. Schema keeps
  a vector-compatible target shape so fan-out can be added without migration.

## Implementation phases

1. **Domain + migration**: typed schedule contract, schema, CRUD, revision
   conflicts, run ledger, cron adapter, `java.time` arithmetic, fake-clock tests.
2. **Runtime**: atomic claim, ticker, bounded executor, recovery, chat context
   overrides, tool filtering, timeout/cancellation, health/events.
3. **Notification**: bound `cron_notify`, adapter lookup, structured target,
   Telegram rich output, failure/suppression semantics.
4. **Management surfaces**: unified tool, JSON API, CLI; shared service functions
   prevent behavior drift.
5. **Web UI**: tab, forms, preview, actions, run history/detail.
6. **Hardening + deploy**: race/restart/DST tests, full focused suite, lint,
   migration smoke test, remote restart, local + Telegram scheduled-run smoke.

## Acceptance tests

- Two concurrent tickers cannot claim the same scheduled occurrence.
- Restart during `running` creates one `abandoned` ledger row and no replay.
  Its persisted session and partial transcript remain readable.
- Pause prevents claims; resume computes next future run; edit is revision-safe.
- Manual run leaves recurring `next_run_at` unchanged and rejects overlap.
- Relative delay input freezes to an absolute `at`; restarting/editing does not
  shift it. One-shot completion and occurrence limits stop future claims.
- Anchored intervals do not drift after slow runs or DST transitions; manual runs
  do not move their anchor or consume `max_occurrences`.
- Downtime inside/outside misfire grace follows documented behavior.
- DST spring gap skips; autumn repeated local slot runs once.
- Run uses fresh context, `:cron` permissions, configured model, memory, and no
  cron-management tool.
- Default/pinned tool profiles expose exactly their allowed tools/actions before
  inference and enforce the same policy during execution. Global policy can only
  restrict them further.
- Selecting a profile never bypasses sensitive-tool approval; without a matching
  approval path, unattended sensitive calls fail with an auditable approval ID.
- Every claimed/manual run atomically gets one unique `kind=cron` session; prompt,
  tool calls/results, assistant output, and partial failure state survive restart.
- A failed session insert creates neither run claim nor schedule advancement.
- Chat session listing excludes cron sessions by default; Cron run detail opens
  them explicitly. Deleting a job preserves both run ledger and sessions.
- Timeout cancels the agent and records terminal failure without wedging worker.
- `policy=never` never sends; all local transcripts/results survive restart.
- `policy=always` sends final output. `policy=agent` sends only after exactly one
  bound `cron_notify` call; the model cannot change target or send directly.
- A clean `agent` run without `cron_notify` is suppressed, not failed.
- `notify_on_error` emits sanitized system text without leaking tool payloads.
- Telegram gets correctly chunked rich Markdown. Telegram outage records
  notification failure without rerunning agent or stopping
  later schedules.
- Tool, CLI, API, and UI produce identical persisted job definitions.
- Soft/full config reload leaves exactly one scheduler worker.

## Deferred

- Natural-language scheduler parsing, seconds field, sub-minute intervals.
- Calendar exclusions/holidays, RRULE, per-job jitter, start/end windows.
- Multiple notification targets/fan-out and file artifact delivery.
- Attached skills, workdir, script-only/no-LLM jobs, job dependencies.
- Per-job overlap/retry/misfire policies.
- Job-scoped standing approvals and continuation after approval.
- Continuable Telegram threads and merging cron transcripts into the originating
  chat. Cron sessions are already persisted separately and linked to their runs.
- Distributed/managed external scheduler providers.

## Unresolved questions

None.
