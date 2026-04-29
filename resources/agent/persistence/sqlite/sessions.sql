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
insert into messages (session_id, role, content, tool_calls, tool_call_id, created_at)
values (:session_id, :role, :content, :tool_calls, :tool_call_id, :created_at)

-- :name list-messages :? :*
select id, role, content, tool_calls, tool_call_id, created_at
from messages
where session_id = :session_id
order by id asc

-- :name search-messages-like :? :*
select id, session_id, role, content, created_at
from messages
where content like :needle
  and (:session_id is null or session_id = :session_id)
order by id desc
limit :limit

-- :name search-messages-fts :? :*
select m.id, m.session_id, m.role, m.content, m.created_at,
       bm25(messages_fts) as retrieval_score
from messages_fts
join messages m on m.id = messages_fts.rowid
where messages_fts match :query
  and (:session_id is null or m.session_id = :session_id)
order by retrieval_score asc, m.id desc
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

-- :name insert-session-ignore :! :n
insert or ignore into sessions (id, title, created_at)
values (:id, :title, :created_at)

-- :name insert-channel-session-mapping-ignore :! :n
insert or ignore into channel_session_mappings
  (source, external_chat_id, session_id, metadata_json, created_at, updated_at)
values
  (:source, :external_chat_id, :session_id, :metadata_json, :created_at, :updated_at)

-- :name get-channel-offset :? :1
select source, next_offset, updated_at
from channel_offsets
where source = :source
limit 1

-- :name upsert-channel-offset :! :n
insert into channel_offsets (source, next_offset, updated_at)
values (:source, :next_offset, :updated_at)
on conflict(source) do update set
  next_offset = excluded.next_offset,
  updated_at = excluded.updated_at

-- :name upsert-channel-inbox :! :n
insert into channel_inbox
  (source, update_id, status, raw_json, attempts, last_error, created_at, updated_at)
values
  (:source, :update_id, :status, :raw_json, :attempts, :last_error, :created_at, :updated_at)
on conflict(source, update_id) do update set
  raw_json = excluded.raw_json,
  status = case
    when channel_inbox.status = 'processed' then channel_inbox.status
    else excluded.status
  end,
  updated_at = excluded.updated_at

-- :name update-channel-inbox-status :! :n
update channel_inbox
set status = :status,
    attempts = attempts + :attempt_delta,
    last_error = :last_error,
    updated_at = :updated_at
where source = :source
  and update_id = :update_id

-- :name get-channel-inbox-update :? :1
select source, update_id, status, raw_json, attempts, last_error, created_at, updated_at
from channel_inbox
where source = :source
  and update_id = :update_id
limit 1
