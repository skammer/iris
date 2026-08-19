CREATE TABLE restart_handoffs (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL UNIQUE REFERENCES sessions(id) ON DELETE CASCADE,
  message TEXT NOT NULL,
  permission_profile TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'running', 'succeeded', 'failed')),
  message_id INTEGER REFERENCES messages(id) ON DELETE SET NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TEXT NOT NULL,
  started_at TEXT,
  finished_at TEXT,
  updated_at TEXT NOT NULL
);

CREATE INDEX restart_handoffs_status_created_idx
ON restart_handoffs(status, created_at);
