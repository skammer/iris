-- :name create-session :! :n
insert into sessions (id, title, created_at)
values (:id, :title, :created_at)

-- :name list-sessions :? :*
select id, title, created_at
from sessions
order by created_at desc

-- :name session-exists :? :1
select 1 as present
from sessions
where id = :id
limit 1

-- :name insert-message :! :n
insert into messages (session_id, role, content, created_at)
values (:session_id, :role, :content, :created_at)

-- :name list-messages :? :*
select id, role, content, created_at
from messages
where session_id = :session_id
order by id asc

-- :name search-messages :? :*
select id, session_id, role, content, created_at
from messages
where content like :needle
  and (:session_id is null or session_id = :session_id)
order by id desc
limit :limit

-- :name last-insert-row-id :? :1
select last_insert_rowid() as id

-- :name insert-completion :! :n
insert into completions (session_id, provider, model, prompt, response, created_at)
values (:session_id, :provider, :model, :prompt, :response, :created_at)

-- :name get-channel-session-mapping :? :1
select source, external_chat_id, session_id, metadata_json, created_at, updated_at
from channel_session_mappings
where source = :source
  and external_chat_id = :external_chat_id
limit 1

-- :name upsert-channel-session-mapping :! :n
insert into channel_session_mappings
  (source, external_chat_id, session_id, metadata_json, created_at, updated_at)
values
  (:source, :external_chat_id, :session_id, :metadata_json, :created_at, :updated_at)
on conflict(source, external_chat_id) do update set
  session_id = excluded.session_id,
  metadata_json = excluded.metadata_json,
  updated_at = excluded.updated_at
