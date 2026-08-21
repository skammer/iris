ALTER TABLE memory_note_updates ADD COLUMN operation TEXT NOT NULL DEFAULT 'note-update';
ALTER TABLE memory_note_updates ADD COLUMN secondary_target_id TEXT;
ALTER TABLE memory_note_updates ADD COLUMN secondary_target_path TEXT;
ALTER TABLE memory_note_updates ADD COLUMN secondary_base_revision TEXT;

CREATE INDEX IF NOT EXISTS idx_memory_note_updates_operation_status
ON memory_note_updates(operation, status, created_at);
