-- :name create-agent-run :! :n
insert or ignore into agent_runs (id, idempotency_key, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, runner_metadata_json, run_options_json, requested_by, last_error, created_at, started_at, finished_at)
values (:id, :idempotency_key, :agent_id, :parent_run_id, :lease_id, :name, :substrate, :status, :capabilities_json, :network_identity_json, :runner_metadata_json, :run_options_json, :requested_by, :last_error, :created_at, null, null)

-- :name get-agent-run :? :1
select id, idempotency_key, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, runner_metadata_json, run_options_json, requested_by, last_error, created_at, started_at, finished_at
from agent_runs
where id = :id

-- :name get-agent-run-by-idempotency-key :? :1
select id, idempotency_key, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, runner_metadata_json, run_options_json, requested_by, last_error, created_at, started_at, finished_at
from agent_runs
where idempotency_key = :idempotency_key
limit 1

-- :name list-agent-runs :? :*
select id, idempotency_key, agent_id, parent_run_id, lease_id, name, substrate, status, capabilities_json, network_identity_json, runner_metadata_json, run_options_json, requested_by, last_error, created_at, started_at, finished_at
from agent_runs
where (:status is null or status = :status)
  and (:parent_run_id is null or parent_run_id = :parent_run_id)
order by created_at desc
limit :limit

-- :name update-agent-run :! :n
update agent_runs
set status = case when :status_set = 1 then :status else status end,
    lease_id = case when :lease_id_set = 1 then :lease_id else lease_id end,
    network_identity_json = case when :network_identity_json_set = 1 then :network_identity_json else network_identity_json end,
    capabilities_json = case when :capabilities_json_set = 1 then :capabilities_json else capabilities_json end,
    runner_metadata_json = case when :runner_metadata_json_set = 1 then :runner_metadata_json else runner_metadata_json end,
    run_options_json = case when :run_options_json_set = 1 then :run_options_json else run_options_json end,
    last_error = case when :last_error_set = 1 then :last_error else last_error end,
    started_at = case when :started_at_set = 1 then :started_at else started_at end,
    finished_at = case when :finished_at_set = 1 then :finished_at else finished_at end
where id = :id

-- :name create-agent-run-lease :! :n
insert or ignore into agent_run_leases (id, run_id, holder_id, status, acquired_at, expires_at, released_at)
values (:id, :run_id, :holder_id, 'active', :acquired_at, :expires_at, null)

-- :name latest-agent-run-lease :? :1
select id, run_id, holder_id, status, acquired_at, expires_at, released_at
from agent_run_leases
where run_id = :run_id
order by acquired_at desc
limit 1

-- :name renew-agent-run-lease :! :n
update agent_run_leases
set expires_at = :expires_at,
    status = 'active'
where id = :id

-- :name release-agent-run-lease :! :n
update agent_run_leases
set status = 'released',
    released_at = :released_at
where id = :id

-- :name insert-agent-run-heartbeat :! :n
insert or ignore into agent_run_heartbeats (run_id, sequence_no, status, metrics_json, observed_at)
values (:run_id, :sequence_no, :status, :metrics_json, :observed_at)

-- :name get-agent-run-heartbeat-by-sequence :? :1
select run_id, sequence_no, status, metrics_json, observed_at
from agent_run_heartbeats
where run_id = :run_id
  and sequence_no = :sequence_no
order by observed_at asc
limit 1

-- :name latest-agent-run-heartbeat :? :1
select run_id, sequence_no, status, metrics_json, observed_at
from agent_run_heartbeats
where run_id = :run_id
order by observed_at desc
limit 1

-- :name list-agent-run-heartbeats :? :*
select run_id, sequence_no, status, metrics_json, observed_at
from agent_run_heartbeats
where run_id = :run_id
  and (:since_sequence is null or sequence_no >= :since_sequence)
order by sequence_no asc, observed_at asc
limit :limit

-- :name create-agent-run-command :! :n
insert or ignore into agent_run_commands (id, run_id, command_type, payload_json, request_id, response_json, status, created_at, acknowledged_at, completed_at, error)
values (:id, :run_id, :command_type, :payload_json, :request_id, null, 'pending', :created_at, null, null, null)

-- :name list-agent-run-commands :? :*
select id, run_id, command_type, payload_json, request_id, response_json, status, created_at, acknowledged_at, completed_at, error
from agent_run_commands
where run_id = :run_id
  and (:status is null or status = :status)
  and (:request_id is null or request_id = :request_id)
order by created_at asc
limit :limit

-- :name get-agent-run-command :? :1
select id, run_id, command_type, payload_json, request_id, response_json, status, created_at, acknowledged_at, completed_at, error
from agent_run_commands
where id = :id

-- :name update-agent-run-command :! :n
update agent_run_commands
set status = coalesce(:status, status),
    acknowledged_at = coalesce(:acknowledged_at, acknowledged_at),
    completed_at = coalesce(:completed_at, completed_at),
    error = coalesce(:error, error),
    response_json = coalesce(:response_json, response_json)
where id = :id

-- :name create-agent-run-checkpoint :! :n
insert or ignore into agent_run_checkpoints (id, run_id, sequence_no, checkpoint_type, state_json, created_at)
values (:id, :run_id, :sequence_no, :checkpoint_type, :state_json, :created_at)

-- :name get-agent-run-checkpoint-by-sequence-type :? :1
select id, run_id, sequence_no, checkpoint_type, state_json, created_at
from agent_run_checkpoints
where run_id = :run_id
  and sequence_no = :sequence_no
  and checkpoint_type = :checkpoint_type
order by created_at asc
limit 1

-- :name latest-agent-run-checkpoint :? :1
select id, run_id, sequence_no, checkpoint_type, state_json, created_at
from agent_run_checkpoints
where run_id = :run_id
order by sequence_no desc, created_at desc
limit 1

-- :name list-agent-run-checkpoints :? :*
select id, run_id, sequence_no, checkpoint_type, state_json, created_at
from agent_run_checkpoints
where run_id = :run_id
  and (:since_sequence is null or sequence_no >= :since_sequence)
order by sequence_no asc, created_at asc
limit :limit

-- :name count-agent-runs :? :1
select count(*) as n
from agent_runs

-- :name count-pending-agent-run-commands :? :1
select count(*) as n
from agent_run_commands
where status = 'pending'

-- :name start-agent-run-activity :! :n
insert or ignore into agent_run_activities (activity_key, run_id, command_id, activity_name, status, input_json, result_json, error, created_at, updated_at)
values (:activity_key, :run_id, :command_id, :activity_name, 'running', :input_json, null, null, :created_at, :updated_at)

-- :name get-agent-run-activity :? :1
select activity_key, run_id, command_id, activity_name, status, input_json, result_json, error, created_at, updated_at
from agent_run_activities
where activity_key = :activity_key

-- :name complete-agent-run-activity :! :n
update agent_run_activities
set status = :status,
    result_json = :result_json,
    error = :error,
    updated_at = :updated_at
where activity_key = :activity_key
