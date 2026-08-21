-- :name create-session :! :n
insert into sessions (id, title, kind, metadata_json, created_at)
values (:id, :title, :kind, :metadata_json, :created_at)

-- :name list-sessions :? :*
select id, title, active_mode, kind, metadata_json, created_at
from sessions
where kind = :kind
order by coalesce((select max(messages.created_at)
                   from messages
                   where messages.session_id = sessions.id),
                  created_at) desc,
         created_at desc

-- :name get-session :? :1
select id, title, active_mode, kind, metadata_json, created_at
from sessions
where id = :id
limit 1

-- :name update-session-active-mode :! :n
update sessions
set active_mode = :active_mode
where id = :id

-- :name update-session-title :! :n
update sessions
set title = :title
where id = :id
  and (title is null or trim(title) = '')

-- :name update-session-metadata :! :n
update sessions
set metadata_json = :metadata_json
where id = :id

-- :name session-exists :? :1
select 1 as present
from sessions
where id = :id
limit 1

-- :name insert-message :! :n
insert into messages (session_id, role, content, tool_calls, tool_call_id, metadata_json, excluded_from_context, created_at)
values (:session_id, :role, :content, :tool_calls, :tool_call_id, :metadata_json, :excluded_from_context, :created_at)

-- :name list-messages :? :*
select id, role, content, tool_calls, tool_call_id, metadata_json, excluded_from_context, created_at
from messages
where session_id = :session_id
order by coalesce(json_extract(metadata_json, '$.activated-at'), created_at) asc, id asc

-- :name count-messages :? :1
select count(*) as n
from messages
where session_id = :session_id

-- :name list-recent-messages :? :*
select id, role, content, tool_calls, tool_call_id, metadata_json, excluded_from_context, created_at
from (
  select id, role, content, tool_calls, tool_call_id, metadata_json, excluded_from_context, created_at
  from messages
  where session_id = :session_id
  order by coalesce(json_extract(metadata_json, '$.activated-at'), created_at) desc, id desc
  limit :limit
)
order by coalesce(json_extract(metadata_json, '$.activated-at'), created_at) asc, id asc

-- :name message-usage-stats :? :1
select
  coalesce(sum(cast(json_extract(metadata_json, '$."usage"."tokens"') as integer)), 0) as total_tokens,
  coalesce(sum(cast(json_extract(metadata_json, '$."usage"."prompt-tokens"') as integer)), 0) as prompt_tokens,
  coalesce(sum(cast(json_extract(metadata_json, '$."usage"."completion-tokens"') as integer)), 0) as completion_tokens,
  coalesce(sum(cast(json_extract(metadata_json, '$."usage"."cached-tokens"') as integer)), 0) as cached_tokens,
  coalesce(sum(case
    when cast(json_extract(metadata_json, '$."usage"."completion-tokens"') as integer) > 0
     and cast(json_extract(metadata_json, '$."usage"."duration-ms"') as integer) > 0
    then cast(json_extract(metadata_json, '$."usage"."completion-tokens"') as integer)
    else 0 end), 0) as timed_completion_tokens,
  coalesce(sum(case
    when cast(json_extract(metadata_json, '$."usage"."completion-tokens"') as integer) > 0
     and cast(json_extract(metadata_json, '$."usage"."duration-ms"') as integer) > 0
    then cast(json_extract(metadata_json, '$."usage"."duration-ms"') as integer)
    else 0 end), 0) as timed_duration_ms
from messages
where session_id = :session_id

-- :name latest-message-usage :? :1
select
  cast(json_extract(metadata_json, '$."usage"."prompt-tokens"') as integer) as prompt_tokens,
  cast(json_extract(metadata_json, '$."usage"."completion-tokens"') as integer) as completion_tokens
from messages
where session_id = :session_id
  and json_type(metadata_json, '$."usage"') = 'object'
order by coalesce(json_extract(metadata_json, '$.activated-at'), created_at) desc, id desc
limit 1

-- :name message-tool-counts :? :*
select json_extract(tool.value, '$.function.name') as tool_name, count(*) as n
from messages message, json_each(coalesce(message.tool_calls, '[]')) tool
where message.session_id = :session_id
  and json_extract(tool.value, '$.function.name') is not null
group by tool_name
order by n desc, tool_name asc

-- :name list-messages-after :? :*
select id, role, content, tool_calls, tool_call_id, metadata_json, excluded_from_context, created_at
from messages
where session_id = :session_id
  and id > :after_id
  and (:through_id is null or id <= :through_id)
order by coalesce(json_extract(metadata_json, '$.activated-at'), created_at) asc, id asc
limit :limit

-- :name update-message-runtime-flags :! :n
update messages
set metadata_json = :metadata_json,
    excluded_from_context = :excluded_from_context
where id = :id

-- :name update-message-entry-runtime-flags :! :n
update session_entries
set payload_json = json_set(payload_json,
                            '$.metadata', json(:metadata_json),
                            '$."excluded-from-context?"',
                            case when :excluded_from_context = 1
                                 then json('true')
                                 else json('false')
                            end)
where type = 'message'
  and json_extract(payload_json, '$."message-id"') = :id

-- :name update-message-entry-parent :! :n
update session_entries
set parent_id = :parent_id
where type = 'message'
  and json_extract(payload_json, '$."message-id"') = :id

-- :name get-message-entry-by-message-id :? :1
select id, session_id, parent_id, type, payload_json, created_at
from session_entries
where type = 'message'
  and json_extract(payload_json, '$."message-id"') = :id
limit 1

-- :name search-messages-like :? :*
select m.id, m.session_id, m.role, m.content, m.created_at,
       s.kind as session_kind, s.title as session_title
from messages m
join sessions s on s.id = m.session_id
where m.content like :needle
  and (:session_id is null or m.session_id = :session_id)
  and (:session_kind is null or s.kind = :session_kind)
  and (:since is null or m.created_at >= :since)
  and (:until is null or m.created_at < :until)
  and (:include_tool_results = 1 or m.role <> 'tool')
order by m.created_at desc, m.id desc
limit :limit

-- :name search-messages-fts :? :*
select m.id, m.session_id, m.role, m.content, m.created_at,
       s.kind as session_kind, s.title as session_title,
       bm25(messages_fts) as retrieval_score
from messages_fts
join messages m on m.id = messages_fts.rowid
join sessions s on s.id = m.session_id
where messages_fts match :query
  and (:session_id is null or m.session_id = :session_id)
  and (:session_kind is null or s.kind = :session_kind)
  and (:since is null or m.created_at >= :since)
  and (:until is null or m.created_at < :until)
  and (:include_tool_results = 1 or m.role <> 'tool')
order by retrieval_score asc, m.id desc
limit :limit

-- :name get-search-message-by-id :? :1
select m.id, m.session_id, m.role, m.content, m.created_at,
       s.kind as session_kind, s.title as session_title
from messages m
join sessions s on s.id = m.session_id
where m.id = :id
  and (:session_id is null or m.session_id = :session_id)
  and m.role <> 'tool'
limit 1

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

-- :name list-channel-session-mappings :? :*
select source, external_chat_id, session_id, metadata_json, created_at, updated_at
from channel_session_mappings
where source = :source
order by updated_at desc

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

-- :name insert-session-entry :! :n
insert into session_entries (id, session_id, parent_id, type, payload_json, created_at)
values (:id, :session_id, :parent_id, :type, :payload_json, :created_at)

-- :name get-session-entry :? :1
select id, session_id, parent_id, type, payload_json, created_at
from session_entries
where id = :id
  and session_id = :session_id
limit 1

-- :name list-session-entries :? :*
select id, session_id, parent_id, type, payload_json, created_at
from session_entries
where session_id = :session_id
order by created_at asc, rowid asc

-- :name latest-session-entry :? :1
select id, session_id, parent_id, type, payload_json, created_at
from session_entries
where session_id = :session_id
order by created_at desc, rowid desc
limit 1

-- :name get-session-leaf-selection :? :1
select s.leaf_entry_id
from session_leaf_selection s
where s.session_id = :session_id
limit 1

-- :name upsert-session-leaf-selection :! :n
insert into session_leaf_selection (session_id, leaf_entry_id, updated_at)
values (:session_id, :leaf_entry_id, :updated_at)
on conflict(session_id) do update set
  leaf_entry_id = excluded.leaf_entry_id,
  updated_at = excluded.updated_at

-- :name count-sessions :? :1
select count(*) as n
from sessions
