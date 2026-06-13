CREATE TABLE IF NOT EXISTS vault_note_index (
  path TEXT PRIMARY KEY,
  id TEXT,
  type TEXT,
  title TEXT,
  description TEXT,
  tags_json TEXT,
  timestamp TEXT,
  iris_scope TEXT,
  iris_status TEXT,
  iris_confidence REAL,
  origins_json TEXT,
  frontmatter_json TEXT,
  body_hash TEXT,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_vault_note_index_status_scope
ON vault_note_index(iris_status, iris_scope, updated_at DESC);

CREATE TABLE IF NOT EXISTS vault_chunks (
  chunk_id TEXT PRIMARY KEY,
  path TEXT NOT NULL,
  heading TEXT,
  block_id TEXT,
  content_hash TEXT NOT NULL,
  text TEXT NOT NULL,
  FOREIGN KEY(path) REFERENCES vault_note_index(path) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vault_chunks_path
ON vault_chunks(path);

CREATE VIRTUAL TABLE IF NOT EXISTS vault_chunks_fts
USING fts5(chunk_id UNINDEXED, path UNINDEXED, heading, text);
