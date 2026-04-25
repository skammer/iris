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

-- :name search-facts-scoped :? :*
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

-- :name search-facts-all :? :*
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

-- :name count-facts :? :1
select count(*) as n
from memory_facts
where status = 'active'
