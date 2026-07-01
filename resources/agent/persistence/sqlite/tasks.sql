-- :name insert-task :! :n
insert into chat_tasks
  (id, session_id, request_id, idempotency_key, message_id, status, prompt,
   request_json, result_json, error, created_at, started_at, finished_at, updated_at)
values
  (:id, :session_id, :request_id, :idempotency_key, :message_id, :status, :prompt,
   :request_json, :result_json, :error, :created_at, :started_at, :finished_at, :updated_at)

-- :name get-task :? :1
select id, session_id, request_id, idempotency_key, message_id, status, prompt,
       request_json, result_json, error, created_at, started_at, finished_at, updated_at
from chat_tasks
where id = :id
limit 1

-- :name get-task-by-idempotency-key :? :1
select id, session_id, request_id, idempotency_key, message_id, status, prompt,
       request_json, result_json, error, created_at, started_at, finished_at, updated_at
from chat_tasks
where idempotency_key = :idempotency_key
limit 1

-- :name list-tasks :? :*
select id, session_id, request_id, idempotency_key, message_id, status, prompt,
       request_json, result_json, error, created_at, started_at, finished_at, updated_at
from chat_tasks
where (:session_id is null or session_id = :session_id)
order by updated_at desc, created_at desc
limit :limit

-- :name mark-task-started :! :n
update chat_tasks
set status = :status,
    started_at = coalesce(started_at, :started_at),
    updated_at = :updated_at
where id = :id
  and status not in ('TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED')

-- :name finish-task :! :n
update chat_tasks
set status = :status,
    result_json = :result_json,
    error = :error,
    finished_at = :finished_at,
    updated_at = :updated_at
where id = :id

-- :name cancel-task :! :n
update chat_tasks
set status = 'TASK_STATE_CANCELED',
    error = coalesce(error, 'Task canceled'),
    finished_at = coalesce(finished_at, :finished_at),
    updated_at = :updated_at
where id = :id
  and status not in ('TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED')

-- :name cancel-session-tasks :! :n
update chat_tasks
set status = 'TASK_STATE_CANCELED',
    error = coalesce(error, 'Task canceled'),
    finished_at = coalesce(finished_at, :finished_at),
    updated_at = :updated_at
where session_id = :session_id
  and status not in ('TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED')

-- :name count-tasks :? :1
select count(*) as n
from chat_tasks
