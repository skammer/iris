ALTER TABLE vault_note_index ADD COLUMN content_hash TEXT;

CREATE TABLE IF NOT EXISTS memory_note_updates (
  id TEXT PRIMARY KEY,
  target_id TEXT NOT NULL,
  target_path TEXT NOT NULL,
  base_revision TEXT NOT NULL,
  proposed_revision TEXT NOT NULL,
  changes_json TEXT NOT NULL,
  proposed_content TEXT NOT NULL,
  diff TEXT NOT NULL,
  evidence_json TEXT,
  source TEXT NOT NULL,
  status TEXT NOT NULL,
  decision TEXT,
  decision_reason TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  decided_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_memory_note_updates_status_created
ON memory_note_updates(status, created_at);

CREATE INDEX IF NOT EXISTS idx_memory_note_updates_target
ON memory_note_updates(target_id, created_at DESC);
