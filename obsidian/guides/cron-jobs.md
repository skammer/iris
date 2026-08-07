# Cron Jobs

#guide #operations #cron

Cron jobs schedule normal Iris agent turns. A job contains a prompt, schedule,
timezone, optional model/tool-profile pins, and optional delivery policy. Every
run gets a fresh persisted session with its own transcript.

## Mental Model

- The prompt is the task. It is always required.
- Iris runs the prompt through the normal agent loop and lets the agent call
  tools allowed by the job's tool profile.
- Invoke a skill in the prompt with `/skill-name`; there is no separate skills
  setting.
- To run a script, tell the agent to run it. There is no separate command-job
  mode.
- A run is always retained locally. Telegram delivery is optional and separate.
- Editing a job affects future runs only. An already claimed run uses its saved
  snapshot.

This keeps skill use, scripts, tool calls, memory, persistence, timeouts, and
notifications on one execution path.

## Quick Start: Web UI

Open **Cron** in Iris:

1. Enter a unique name and non-empty prompt.
2. Choose `Cron`, `Once`, or `Every`.
3. Set an IANA timezone such as `UTC`.
4. Keep the default model and `cron-observe` tool profile unless the task needs
   broader capabilities.
5. Choose notification behavior.
6. Use **Preview next runs**, verify the UTC instants, then create the job.
7. Use **Run now** for a safe end-to-end test. Open **Transcript** or **Logs**
   from run history to inspect the result.

The UI polls scheduler state every 15 seconds. A newly due job may therefore
start up to one poll interval after its scheduled instant.

## Quick Start: Ask Iris

From Chat or Telegram, describe the complete job. Mutating `cronjob` calls are
approval-sensitive, so Iris may ask for confirmation before saving it.

```text
Создай cron job «Morning climate» на 08:00 по будням, timezone UTC.
Prompt: /ha-report Проверь климат и состояние растений. Сохрани полный отчёт,
но ничего не отправляй в Telegram. Используй cron-observe.
```

From Telegram, conditional delivery can target the originating chat:

```text
Создай cron job «Suspicious logs» каждые 15 минут. Проверь последние Iris logs.
Если есть новая ошибка, утечка секрета или crash loop — вызови cron_notify один
раз с коротким диагнозом. Если всё нормально, ничего не отправляй. Уведомления
доставляй в этот Telegram chat.
```

Read operations (`list`, `get`, `history`, `preview`) do not mutate jobs.
Create, update, pause, resume, run, and delete require `cron-manage` and approval
under the normal tool policy.

## Quick Start: CLI

Create a weekday report using a slash skill:

```bash
iris cron create \
  --name morning-climate \
  --cron "0 8 * * 1-5" \
  --timezone UTC \
  --tool-profile cron-observe \
  --prompt "/ha-report Проверь климат и растения. Сохрани полный отчёт локально."
```

Create a conditional Telegram monitor:

```bash
iris cron create \
  --name suspicious-logs \
  --every 15m \
  --timezone UTC \
  --tool-profile cron-observe \
  --notify agent \
  --target telegram:123456789 \
  --prompt "Проверь Iris logs. При новой серьёзной проблеме вызови cron_notify один раз с кратким диагнозом; иначе не отправляй ничего."
```

Ask the agent to run a script:

```bash
iris cron create \
  --name backup-check \
  --cron "30 7 * * *" \
  --timezone UTC \
  --tool-profile cron-automation \
  --notify always \
  --target telegram:123456789 \
  --prompt "Запусти через shell /opt/iris/jobs/check-backup.sh. Сообщи exit code и кратко перескажи stdout/stderr."
```

The last example is agent-directed, not a raw scheduler exec. The agent decides
which tool call to make, and shell approval policy still applies.

Common operations:

```bash
iris cron status
iris cron list
iris cron list --status active
iris cron get suspicious-logs
iris cron run suspicious-logs
iris cron runs suspicious-logs --limit 20
iris cron update suspicious-logs --revision 1 --every 30m
iris cron pause suspicious-logs --revision 2
iris cron resume suspicious-logs --revision 3
iris cron delete suspicious-logs --revision 4
```

`get`, `update`, and lifecycle commands accept either the UUID or unique job
name. Updates and lifecycle mutations require the current `revision`; fetch the
job again after a revision conflict.

## Schedules

| Kind | Input | Semantics |
|---|---|---|
| `cron` | Five Unix fields | Calendar schedule evaluated in the job timezone |
| `at` | ISO-8601 UTC instant | One run, then job becomes `completed` |
| `interval` | Whole seconds plus anchor | Fixed-rate schedule; minimum 60 seconds |

Cron fields:

```text
minute hour day-of-month month day-of-week
0      8    *            *     1-5
```

There is no seconds field. Use an IANA timezone, not a fixed abbreviation such
as `MSK` or `PST`.

CLI schedule examples:

```bash
--cron "0 9 * * *" --timezone UTC
--at "2026-08-10T06:00:00Z" --timezone UTC
--at "in 15m" --timezone UTC
--every 2h --timezone UTC
--every 1d --anchor-at "2026-08-08T06:00:00Z" --timezone UTC
```

Notes:

- `at` must be strictly in the future. Its timezone is already encoded by the
  required UTC instant; the job timezone remains required for a uniform job
  contract.
- Relative `in 15m` is accepted by CLI and the `cronjob` tool, then frozen as an
  absolute UTC instant. The HTTP API expects an absolute instant.
- Intervals are anchored fixed-rate schedules. A slow run does not shift later
  occurrences.
- `max-occurrences` limits scheduled occurrences. Manual **Run now** calls do not
  consume the limit or move the recurring schedule.
- A one-shot job always has an effective occurrence limit of one.
- During the spring DST gap, nonexistent local times are skipped. During the
  autumn repeated hour, Iris runs a repeated local slot once.

## Prompt, Skills, and Scripts

Put everything needed to execute safely in the prompt:

- objective and data source;
- success/failure criteria;
- exact files, endpoints, entities, or script paths;
- desired output shape;
- when notification is warranted;
- slash skills at the start, for example `/ha-report`.

```text
/ha-report
Проверь Home Assistant. Если недоступны критичные датчики или влажность вышла
за допустимый диапазон, вызови cron_notify один раз: перечисли только проблемы
и текущее значение. Если проблем нет, сохрани короткий локальный результат и не
вызывай cron_notify.
```

An installed `/skill` contributes instructions to the normal turn. It does not
grant permissions or tools. Unknown or unavailable slash skills contribute
nothing. The job's resolved tool profile must expose every required tool/action.

Do not put secrets in prompts. The current prompt is stored in `cron_jobs`,
copied into each run snapshot, and persisted again in the run transcript.

## Tool Profiles and Model Selection

Default tool profiles:

- `cron-observe`: filesystem reads, HTTP `GET/HEAD`, memory reads, and read-only
  Home Assistant actions.
- `cron-automation`: filesystem writes, HTTP, and shell in addition to reads.

Choose the smallest profile that can complete the prompt. A profile controls
what the model can see and request; global roots, blocklists, command policy,
and approval rules remain hard limits.

Important consequences:

- A skill cannot widen its job's profile.
- `cron-automation` does not imply unattended approval. If a sensitive call
  still needs approval, the run ends failed with an auditable approval ID.
- Cron runs cannot call `cronjob`, preventing recursive schedule creation.
- General messaging tools are removed. Conditional delivery uses only the bound
  `cron_notify` tool.

Model selection has two modes:

- **Cron default**: resolve `:cron/:provider` and `:cron/:model`, then fall back
  to the active global provider/model.
- **Pinned**: store both provider and model on the job. They must be configured
  as a valid pair.

Every run snapshots the resolved model and tool profile. Config or job changes
do not mutate an in-flight run.

## Notifications

| Policy | Successful run |
|---|---|
| `never` | Retain locally; send no success message |
| `always` | Send final assistant output |
| `agent` | Send only content staged by one `cron_notify` call |

For `agent`, no `cron_notify` call is a successful suppressed notification, not
a run failure. The destination is fixed in the run snapshot; the model cannot
redirect it. Only one notification can be staged per run.

Error delivery is independent. When a Telegram target exists,
`notify_on_error` defaults to true, including with `policy=never`. Disable it
explicitly when a job must never send outbound messages:

```bash
iris cron create \
  --name local-health-check \
  --every 1h \
  --timezone UTC \
  --notify never \
  --target telegram:123456789 \
  --notify-on-error false \
  --prompt "Проверь health endpoint и сохрани результат локально."
```

Simpler strict-local configuration: omit both `--notify` and `--target`.

Notification failure does not rerun the agent and does not stop later
occurrences. The run output remains available locally.

## HTTP API

All `/v1/*` routes require normal Iris API authentication. This example assumes
`IRIS_API_KEY` contains the configured key:

```bash
curl -fsS http://127.0.0.1:8689/v1/cron/jobs \
  -H "Authorization: Bearer $IRIS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "daily-health",
    "prompt": "Проверь /health через http tool и сохрани краткий результат.",
    "schedule": {"kind": "cron", "expression": "0 8 * * *"},
    "timezone": "UTC",
    "tool_profile": "cron-observe",
    "notification": {"policy": "never"}
  }'
```

Routes:

```text
GET    /v1/cron/status
GET    /v1/cron/jobs
POST   /v1/cron/jobs
GET    /v1/cron/jobs/:id
PATCH  /v1/cron/jobs/:id
DELETE /v1/cron/jobs/:id
POST   /v1/cron/jobs/:id/pause
POST   /v1/cron/jobs/:id/resume
POST   /v1/cron/jobs/:id/run
GET    /v1/cron/jobs/:id/runs
GET    /v1/cron/runs/:id
POST   /v1/cron/preview
```

PATCH, pause, resume, and delete require the current numeric `revision` in the
JSON body. Create returns `201`; manual run returns `202`.

## Persistence and Audit

SQLite is the source of truth:

- `cron_jobs.prompt` stores the current job prompt.
- `cron_runs.snapshot_json` freezes prompt, job revision, model, tool profile,
  schedule, and notification target for one occurrence.
- Every run owns one `kind=cron` session containing the prompt, thinking,
  assistant messages, tool calls/results, and completion state.
- `cron_runs` stores status, output/error summary, usage, timestamps, request ID,
  and session ID.

Deleting a job is a soft delete. Run ledger and session transcripts remain.
Cron sessions are hidden from normal chat listing but open from Cron run history.

The run summary output is bounded by `:cron/:output-max-chars`; the persisted
session remains the detailed audit trail.

## Runtime Edge Cases

- **Overlap:** a scheduled occurrence that finds the same job already active is
  recorded as `skipped`. Manual **Run now** rejects overlap instead.
- **Downtime:** an occurrence older than `misfire-grace-seconds` is recorded as
  `skipped`, not executed. Missed recurring occurrences drain one eligible tick
  at a time; later schedules continue.
- **Capacity:** waiting for a free worker counts toward misfire age. A due run may
  be skipped if capacity remains exhausted beyond the grace window.
- **Timeout:** after `run-timeout-seconds`, Iris cancels the session and records a
  failed run. Error notification follows `notify_on_error`.
- **Graceful restart:** owned active runs become `abandoned`; their partial
  transcripts remain readable and are not replayed.
- **Abrupt crash:** startup abandons stale active runs only after the configured
  run timeout. A younger stale run can keep its job blocked until it becomes
  stale and the service is restarted again.
- **Optimistic edits:** stale UI/API/CLI revisions fail instead of overwriting a
  newer definition.
- **Pause/resume:** pause prevents new claims. Resume computes the next future
  occurrence; it does not backfill paused time.
- **Delete:** preserves history and prevents future runs.
- **Delivery outage:** records notification failure without rerunning the LLM.
- **Model/profile removal:** the scheduler reports an error and leaves the job
  due until its pinned provider/model or profile is restored or the job is
  edited.
- **Approval required:** unattended runs cannot wait for a human indefinitely;
  the run fails with the approval ID.

## Configuration Defaults

```clojure
:cron {:enabled true
       :poll-interval-seconds 15
       :max-concurrency 2
       :run-timeout-seconds 1800
       :misfire-grace-seconds 3600
       :timezone "UTC"
       :provider nil
       :model nil
       :tool-profile :cron-observe
       :output-max-chars 200000}
```

Personal/server overrides belong in `~/.config/iris/`, not repository defaults.
See [[configuration-observability]].

## Troubleshooting

1. Run `iris cron status`; confirm `enabled=true` and `running=true`.
2. Check the Cron page's recent run status and notification status separately.
3. Open **Transcript** for agent/tool behavior and **Logs** for the request ID.
4. Confirm the job timezone and previewed UTC instants.
5. Confirm the selected profile exposes the skill's required tools/actions.
6. For shell or writes, check whether approval policy blocked the call.
7. For Telegram, distinguish `suppressed`, `failed`, and `succeeded` delivery.

Architecture and invariants: [CRON_JOBS_PLAN.md](../../CRON_JOBS_PLAN.md).
