CREATE TABLE IF NOT EXISTS chat_tasks (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  request_id TEXT NOT NULL,
  idempotency_key TEXT,
  message_id TEXT,
  status TEXT NOT NULL,
  prompt TEXT,
  request_json TEXT,
  result_json TEXT,
  error TEXT,
  created_at TEXT NOT NULL,
  started_at TEXT,
  finished_at TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_tasks_idempotency_key
ON chat_tasks(idempotency_key)
WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_tasks_request_id
ON chat_tasks(request_id);

CREATE INDEX IF NOT EXISTS idx_chat_tasks_session_updated
ON chat_tasks(session_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_tasks_status_updated
ON chat_tasks(status, updated_at DESC);
