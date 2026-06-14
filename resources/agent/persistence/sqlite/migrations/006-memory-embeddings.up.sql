CREATE TABLE IF NOT EXISTS memory_embeddings (
  id TEXT PRIMARY KEY,
  surface TEXT NOT NULL,
  surface_id TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  model TEXT,
  embedding_json TEXT NOT NULL,
  dimensions INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(surface, surface_id)
);

CREATE INDEX IF NOT EXISTS idx_memory_embeddings_surface
ON memory_embeddings(surface, surface_id);

CREATE INDEX IF NOT EXISTS idx_memory_embeddings_hash
ON memory_embeddings(content_hash);

CREATE TABLE IF NOT EXISTS vault_chunk_embeddings (
  chunk_id TEXT PRIMARY KEY,
  content_hash TEXT NOT NULL,
  model TEXT,
  embedding_json TEXT NOT NULL,
  dimensions INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(chunk_id) REFERENCES vault_chunks(chunk_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_vault_chunk_embeddings_hash
ON vault_chunk_embeddings(content_hash);
