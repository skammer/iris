CREATE INDEX IF NOT EXISTS idx_sessions_project
ON sessions(json_extract(metadata_json, '$."project-id"')) WHERE kind = 'chat';
