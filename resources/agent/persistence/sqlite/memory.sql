-- :name reset-vault-index :! :n
delete from vault_note_index

-- :name reset-vault-chunks-fts :! :n
delete from vault_chunks_fts

-- :name reset-vault-chunks :! :n
delete from vault_chunks

-- :name reset-vault-note-embeddings :! :n
delete from memory_embeddings
where surface = 'vault_note'

-- :name reset-vault-chunk-embeddings :! :n
delete from vault_chunk_embeddings

-- :name insert-vault-note :! :n
insert into vault_note_index
(path, id, type, title, description, tags_json, timestamp,
 iris_scope, iris_status, iris_confidence, origins_json,
 frontmatter_json, body_hash, updated_at)
values
(:path, :id, :type, :title, :description, :tags_json, :timestamp,
 :iris_scope, :iris_status, :iris_confidence, :origins_json,
 :frontmatter_json, :body_hash, :updated_at)

-- :name insert-vault-chunk :! :n
insert into vault_chunks
(chunk_id, path, heading, block_id, content_hash, text)
values
(:chunk_id, :path, :heading, :block_id, :content_hash, :text)

-- :name insert-vault-chunk-fts :! :n
insert into vault_chunks_fts
(chunk_id, path, heading, text)
values
(:chunk_id, :path, :heading, :text)

-- :name insert-memory-embedding :! :n
insert into memory_embeddings
(id, surface, surface_id, content_hash, model, embedding_json, dimensions, updated_at)
values
(:id, :surface, :surface_id, :content_hash, :model, :embedding_json, :dimensions, :updated_at)

-- :name insert-vault-chunk-embedding :! :n
insert into vault_chunk_embeddings
(chunk_id, content_hash, model, embedding_json, dimensions, updated_at)
values
(:chunk_id, :content_hash, :model, :embedding_json, :dimensions, :updated_at)

-- :name search-vault-chunks-like :? :*
select c.chunk_id, c.path, c.heading, c.block_id, c.content_hash, c.text,
       n.id as note_id, n.type, n.title, n.description, n.tags_json,
       n.timestamp, n.iris_scope, n.iris_status, n.iris_confidence,
       n.origins_json, n.frontmatter_json, n.updated_at
from vault_chunks c
join vault_note_index n on n.path = c.path
where ((n.iris_status = 'approved' and n.iris_scope in ('global', 'project'))
       or (:session_id is not null
           and n.iris_scope = 'session'
           and n.iris_status in ('approved', 'auto_session')
           and n.origins_json like :session_origin_needle))
  and (:needle is null
       or c.text like :needle
       or c.heading like :needle
       or n.title like :needle
       or n.description like :needle
       or n.tags_json like :needle)
order by n.updated_at desc
limit :limit

-- :name search-vault-chunks-fts :? :*
select c.chunk_id, c.path, c.heading, c.block_id, c.content_hash, c.text,
       n.id as note_id, n.type, n.title, n.description, n.tags_json,
       n.timestamp, n.iris_scope, n.iris_status, n.iris_confidence,
       n.origins_json, n.frontmatter_json, n.updated_at,
       bm25(vault_chunks_fts) as retrieval_score
from vault_chunks_fts
join vault_chunks c on c.chunk_id = vault_chunks_fts.chunk_id
join vault_note_index n on n.path = c.path
where vault_chunks_fts match :query
  and ((n.iris_status = 'approved' and n.iris_scope in ('global', 'project'))
       or (:session_id is not null
           and n.iris_scope = 'session'
           and n.iris_status in ('approved', 'auto_session')
           and n.origins_json like :session_origin_needle))
order by retrieval_score asc, n.updated_at desc
limit :limit

-- :name list-vault-notes :? :*
select path, id, type, title, description, tags_json, timestamp,
       iris_scope, iris_status, iris_confidence, origins_json,
       frontmatter_json, body_hash, updated_at
from vault_note_index
where (:status is null or iris_status = :status)
order by updated_at desc
limit :limit

-- :name count-vault-notes :? :1
select count(*) as n
from vault_note_index

-- :name count-vault-chunks :? :1
select count(*) as n
from vault_chunks

-- :name list-vault-chunks :? :*
select chunk_id, path, heading, block_id, content_hash, text
from vault_chunks
limit :limit

-- :name list-memory-embeddings :? :*
select id, surface, surface_id, content_hash, model, embedding_json, dimensions, updated_at
from memory_embeddings
where (:surface is null or surface = :surface)
limit :limit

-- :name list-vault-chunk-embeddings :? :*
select chunk_id, content_hash, model, embedding_json, dimensions, updated_at
from vault_chunk_embeddings
limit :limit

-- :name list-vault-chunk-embedding-candidates :? :*
select c.chunk_id, c.path, c.heading, c.block_id, c.content_hash, c.text,
       n.id as note_id, n.type, n.title, n.description, n.tags_json,
       n.timestamp, n.iris_scope, n.iris_status, n.iris_confidence,
       n.origins_json, n.frontmatter_json, n.updated_at,
       e.embedding_json, e.model as embedding_model, e.dimensions as embedding_dimensions
from vault_chunk_embeddings e
join vault_chunks c on c.chunk_id = e.chunk_id
join vault_note_index n on n.path = c.path
where ((n.iris_status = 'approved' and n.iris_scope in ('global', 'project'))
       or (:session_id is not null
           and n.iris_scope = 'session'
           and n.iris_status in ('approved', 'auto_session')
           and n.origins_json like :session_origin_needle))
limit :limit
