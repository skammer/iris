-- :name get-list :? :1
select id, thread_id, slug, description, metadata_json, created_at, updated_at
from todo_lists
where thread_id = :thread_id
  and slug = :slug
limit 1

-- :name insert-list :! :n
insert into todo_lists
  (id, thread_id, slug, description, metadata_json, created_at, updated_at)
values
  (:id, :thread_id, :slug, :description, :metadata_json, :created_at, :updated_at)

-- :name update-list :! :n
update todo_lists
set description = :description,
    metadata_json = :metadata_json,
    updated_at = :updated_at
where id = :id

-- :name delete-list-items :! :n
delete from todo_items
where list_id = :list_id

-- :name insert-list-item :! :n
insert into todo_items
  (id, list_id, position, content, description, status, priority, created_at, updated_at)
values
  (:id, :list_id, :position, :content, :description, :status, :priority, :created_at, :updated_at)

-- :name list-items :? :*
select id, list_id, position, content, description, status, priority, created_at, updated_at
from todo_items
where list_id = :list_id
order by position asc

-- :name list-lists :? :*
select id, thread_id, slug, description, metadata_json, created_at, updated_at
from todo_lists
where (:thread_id is null or thread_id = :thread_id)
order by updated_at desc
limit :limit

-- :name search-lists-like :? :*
select id, thread_id, slug, description, metadata_json, created_at, updated_at
from todo_lists l
where (:thread_id is null or l.thread_id = :thread_id)
  and (:needle is null
       or l.description like :needle
       or l.metadata_json like :needle
       or exists
          (select 1
           from todo_items i
           where i.list_id = l.id
             and (i.content like :needle
                  or i.description like :needle
                  or i.status like :needle
                  or i.priority like :needle)))
order by updated_at desc
limit :limit

-- :name search-lists-fts :? :*
select distinct l.id, l.thread_id, l.slug, l.description, l.metadata_json,
       l.created_at, l.updated_at
from todo_items_fts
join todo_items i on i.rowid = todo_items_fts.rowid
join todo_lists l on l.id = i.list_id
where todo_items_fts match :query
  and (:thread_id is null or l.thread_id = :thread_id)
order by l.updated_at desc
limit :limit

-- :name count-lists :? :1
select count(*) as n
from todo_lists
