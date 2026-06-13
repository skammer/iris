-- :name reset-vault-index :! :n
delete from vault_note_index

-- :name reset-vault-chunks-fts :! :n
delete from vault_chunks_fts

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
