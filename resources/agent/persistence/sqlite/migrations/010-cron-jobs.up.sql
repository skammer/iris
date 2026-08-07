ALTER TABLE sessions ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'
  CHECK (kind IN ('chat', 'cron'));
ALTER TABLE sessions ADD COLUMN metadata_json TEXT;

CREATE INDEX sessions_kind_created_idx ON sessions(kind, created_at DESC);

CREATE TABLE cron_jobs (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  prompt TEXT NOT NULL,
  schedule_json TEXT NOT NULL,
  timezone TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active', 'paused', 'completed', 'deleted')),
  notification_json TEXT NOT NULL,
  provider TEXT,
  model TEXT,
  tool_profile TEXT,
  next_run_at TEXT,
  last_run_at TEXT,
  last_run_status TEXT,
  run_count INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  occurrence_count INTEGER NOT NULL DEFAULT 0,
  max_occurrences INTEGER,
  revision INTEGER NOT NULL DEFAULT 1,
  created_by TEXT NOT NULL,
  origin_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT
);

CREATE UNIQUE INDEX cron_jobs_name_unique
  ON cron_jobs(lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX cron_jobs_due_idx ON cron_jobs(status, next_run_at);

CREATE TABLE cron_runs (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES cron_jobs(id) ON DELETE RESTRICT,
  job_revision INTEGER NOT NULL,
  trigger TEXT NOT NULL CHECK (trigger IN ('scheduled', 'manual')),
  scheduled_for TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('claimed', 'running', 'succeeded', 'failed', 'cancelled', 'abandoned', 'skipped')),
  notification_status TEXT NOT NULL,
  request_id TEXT NOT NULL,
  session_id TEXT NOT NULL UNIQUE REFERENCES sessions(id) ON DELETE RESTRICT,
  owner_id TEXT,
  snapshot_json TEXT NOT NULL,
  output TEXT,
  error TEXT,
  usage_json TEXT,
  notification_json TEXT,
  claimed_at TEXT,
  started_at TEXT,
  finished_at TEXT,
  created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX cron_runs_scheduled_claim_unique
  ON cron_runs(job_id, scheduled_for) WHERE trigger = 'scheduled';
CREATE UNIQUE INDEX cron_runs_one_active_per_job
  ON cron_runs(job_id) WHERE status IN ('claimed', 'running');
CREATE INDEX cron_runs_job_history_idx ON cron_runs(job_id, created_at DESC);
CREATE INDEX cron_runs_status_idx ON cron_runs(status, created_at DESC);
