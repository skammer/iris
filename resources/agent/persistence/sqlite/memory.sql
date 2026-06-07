-- :name get-fact-by-normalized :? :1
select id, scope_type, scope_id, subject, predicate, object,
       normalized_subject, normalized_predicate, normalized_object,
       source_session_id, source_message_ids_json, source_request_id,
       confidence, status, metadata_json, created_at, updated_at
from memory_facts
where scope_type = :scope_type
  and coalesce(scope_id, '') = coalesce(:scope_id, '')
  and normalized_subject = :normalized_subject
  and normalized_predicate = :normalized_predicate
  and normalized_object = :normalized_object
limit 1

-- :name insert-fact :! :n
insert into memory_facts
(id, scope_type, scope_id, subject, predicate, object,
 normalized_subject, normalized_predicate, normalized_object,
 source_session_id, source_message_ids_json, source_request_id,
 confidence, status, metadata_json, created_at, updated_at)
values
(:id, :scope_type, :scope_id, :subject, :predicate, :object,
 :normalized_subject, :normalized_predicate, :normalized_object,
 :source_session_id, :source_message_ids_json, :source_request_id,
 :confidence, :status, :metadata_json, :created_at, :updated_at)

-- :name update-fact :! :n
update memory_facts
set subject = :subject,
    predicate = :predicate,
    object = :object,
    source_session_id = coalesce(:source_session_id, source_session_id),
    source_message_ids_json = :source_message_ids_json,
    source_request_id = coalesce(:source_request_id, source_request_id),
    confidence = coalesce(:confidence, confidence),
    status = :status,
    metadata_json = :metadata_json,
    updated_at = :updated_at
where id = :id

-- :name get-fact :? :1
select id, scope_type, scope_id, subject, predicate, object,
       normalized_subject, normalized_predicate, normalized_object,
       source_session_id, source_message_ids_json, source_request_id,
       confidence, status, metadata_json, created_at, updated_at
from memory_facts
where id = :id
limit 1

-- :name remove-fact-by-id :! :n
update memory_facts
set status = 'removed',
    updated_at = :updated_at
where id = :id
  and status = 'active'

-- :name remove-fact-by-normalized :! :n
update memory_facts
set status = 'removed',
    updated_at = :updated_at
where scope_type = :scope_type
  and coalesce(scope_id, '') = coalesce(:scope_id, '')
  and normalized_subject = :normalized_subject
  and normalized_predicate = :normalized_predicate
  and normalized_object = :normalized_object
  and status = 'active'

-- :name reset-facts :! :n
delete from memory_facts

-- :name search-facts-scoped-like :? :*
select id, scope_type, scope_id, subject, predicate, object,
       normalized_subject, normalized_predicate, normalized_object,
       source_session_id, source_message_ids_json, source_request_id,
       confidence, status, metadata_json, created_at, updated_at
from memory_facts
where status = 'active'
  and ((:include_global = 1 and scope_type = 'global')
       or (scope_type = :scope_type and coalesce(scope_id, '') = coalesce(:scope_id, '')))
  and (:needle is null
       or subject like :needle
       or predicate like :needle
       or object like :needle
       or metadata_json like :needle)
order by updated_at desc
limit :limit

-- :name search-facts-scoped-fts :? :*
select f.id, f.scope_type, f.scope_id, f.subject, f.predicate, f.object,
       f.normalized_subject, f.normalized_predicate, f.normalized_object,
       f.source_session_id, f.source_message_ids_json, f.source_request_id,
       f.confidence, f.status, f.metadata_json, f.created_at, f.updated_at,
       bm25(memory_facts_fts) as retrieval_score
from memory_facts_fts
join memory_facts f on f.rowid = memory_facts_fts.rowid
where memory_facts_fts match :query
  and f.status = 'active'
  and ((:include_global = 1 and f.scope_type = 'global')
       or (f.scope_type = :scope_type and coalesce(f.scope_id, '') = coalesce(:scope_id, '')))
order by retrieval_score asc, f.updated_at desc
limit :limit

-- :name search-facts-all-like :? :*
select id, scope_type, scope_id, subject, predicate, object,
       normalized_subject, normalized_predicate, normalized_object,
       source_session_id, source_message_ids_json, source_request_id,
       confidence, status, metadata_json, created_at, updated_at
from memory_facts
where status = 'active'
  and (:needle is null
       or subject like :needle
       or predicate like :needle
       or object like :needle
       or metadata_json like :needle)
order by updated_at desc
limit :limit

-- :name search-facts-all-fts :? :*
select f.id, f.scope_type, f.scope_id, f.subject, f.predicate, f.object,
       f.normalized_subject, f.normalized_predicate, f.normalized_object,
       f.source_session_id, f.source_message_ids_json, f.source_request_id,
       f.confidence, f.status, f.metadata_json, f.created_at, f.updated_at,
       bm25(memory_facts_fts) as retrieval_score
from memory_facts_fts
join memory_facts f on f.rowid = memory_facts_fts.rowid
where memory_facts_fts match :query
  and f.status = 'active'
order by retrieval_score asc, f.updated_at desc
limit :limit

-- :name count-facts :? :1
select count(*) as n
from memory_facts
where status = 'active'

-- :name list-facts :? :*
select id, scope_type, scope_id, subject, predicate, object,
       normalized_subject, normalized_predicate, normalized_object,
       source_session_id, source_message_ids_json, source_request_id,
       confidence, status, metadata_json, created_at, updated_at
from memory_facts
where (:status is null or status = :status)
order by updated_at desc
