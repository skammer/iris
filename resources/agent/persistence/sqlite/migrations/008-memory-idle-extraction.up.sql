CREATE TABLE IF NOT EXISTS memory_extraction_state (
  session_id TEXT PRIMARY KEY,
  last_processed_message_id INTEGER NOT NULL DEFAULT 0,
  last_processed_message_created_at TEXT,
  last_processed_event_id INTEGER NOT NULL DEFAULT 0,
  last_run_at TEXT,
  last_success_at TEXT,
  last_note_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  next_attempt_at TEXT,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_memory_extraction_state_next_attempt
ON memory_extraction_state(next_attempt_at);
