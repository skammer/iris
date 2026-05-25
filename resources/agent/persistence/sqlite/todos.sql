-- :name get-list :? :1
select id, thread_id, slug, description, todos_json, metadata_json, created_at, updated_at
from todo_lists
where thread_id = :thread_id
  and slug = :slug
limit 1

-- :name insert-list :! :n
insert into todo_lists
(id, thread_id, slug, description, todos_json, metadata_json, created_at, updated_at)
values
(:id, :thread_id, :slug, :description, :todos_json, :metadata_json, :created_at, :updated_at)

-- :name update-list :! :n
update todo_lists
set description = :description,
    todos_json = :todos_json,
    metadata_json = :metadata_json,
    updated_at = :updated_at
where id = :id

-- :name list-lists :? :*
select id, thread_id, slug, description, todos_json, metadata_json, created_at, updated_at
from todo_lists
where (:thread_id is null or thread_id = :thread_id)
order by updated_at desc
limit :limit

-- :name search-lists-like :? :*
select id, thread_id, slug, description, todos_json, metadata_json, created_at, updated_at
from todo_lists
where (:thread_id is null or thread_id = :thread_id)
  and (:needle is null
       or description like :needle
       or todos_json like :needle
       or metadata_json like :needle)
order by updated_at desc
limit :limit

-- :name search-lists-fts :? :*
select l.id, l.thread_id, l.slug, l.description, l.todos_json, l.metadata_json,
       l.created_at, l.updated_at,
       bm25(todo_lists_fts) as retrieval_score
from todo_lists_fts
join todo_lists l on l.rowid = todo_lists_fts.rowid
where todo_lists_fts match :query
  and (:thread_id is null or l.thread_id = :thread_id)
order by retrieval_score asc, l.updated_at desc
limit :limit

-- :name count-lists :? :1
select count(*) as n
from todo_lists
