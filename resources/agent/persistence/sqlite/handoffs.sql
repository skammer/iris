-- :name upsert-restart-handoff :! :n
insert into restart_handoffs
  (id, session_id, message, permission_profile, status, message_id, attempts,
   last_error, created_at, started_at, finished_at, updated_at)
values
  (:id, :session_id, :message, :permission_profile, 'pending', null, 0,
   null, :created_at, null, null, :updated_at)
on conflict(session_id) do update set
  id = excluded.id,
  message = excluded.message,
  permission_profile = excluded.permission_profile,
  status = 'pending',
  message_id = null,
  attempts = 0,
  last_error = null,
  created_at = excluded.created_at,
  started_at = null,
  finished_at = null,
  updated_at = excluded.updated_at

-- :name get-restart-handoff :? :1
select id, session_id, message, permission_profile, status, message_id, attempts,
       last_error, created_at, started_at, finished_at, updated_at
from restart_handoffs
where id = :id
limit 1

-- :name get-session-restart-handoff :? :1
select id, session_id, message, permission_profile, status, message_id, attempts,
       last_error, created_at, started_at, finished_at, updated_at
from restart_handoffs
where session_id = :session_id
limit 1

-- :name list-resumable-restart-handoffs :? :*
select id, session_id, message, permission_profile, status, message_id, attempts,
       last_error, created_at, started_at, finished_at, updated_at
from restart_handoffs
where status in ('pending', 'running')
order by created_at asc

-- :name mark-restart-handoff-running :! :n
update restart_handoffs
set status = 'running',
    attempts = attempts + 1,
    started_at = :started_at,
    finished_at = null,
    last_error = null,
    updated_at = :updated_at
where id = :id
  and status in ('pending', 'running')

-- :name attach-restart-handoff-message :! :n
update restart_handoffs
set message_id = :message_id,
    updated_at = :updated_at
where id = :id

-- :name finish-restart-handoff :! :n
update restart_handoffs
set status = :status,
    last_error = :last_error,
    finished_at = :finished_at,
    updated_at = :updated_at
where id = :id

-- :name find-restart-handoff-message :? :1
select id, session_id, role, content, tool_calls, tool_call_id, metadata_json,
       excluded_from_context, created_at
from messages
where session_id = :session_id
  and json_extract(metadata_json, '$."restart-handoff-id"') = :handoff_id
order by id asc
limit 1
