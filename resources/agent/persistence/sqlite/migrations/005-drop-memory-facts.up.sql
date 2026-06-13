DROP TRIGGER IF EXISTS memory_facts_fts_ai;
DROP TRIGGER IF EXISTS memory_facts_fts_ad;
DROP TRIGGER IF EXISTS memory_facts_fts_au;
DROP TABLE IF EXISTS memory_facts_fts;
DROP INDEX IF EXISTS idx_memory_facts_dedup;
DROP INDEX IF EXISTS idx_memory_facts_scope_updated;
DROP TABLE IF EXISTS memory_facts;
