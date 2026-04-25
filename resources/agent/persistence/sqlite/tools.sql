-- :name create-tool-approval :! :n
insert into tool_approvals (id, tool_name, status, input_json, input_hash, requested_permissions_json, requested_by, reason, actor, decision_reason, expires_at, created_at, decided_at)
values (:id, :tool_name, 'pending', :input_json, :input_hash, :requested_permissions_json, :requested_by, :reason, null, null, :expires_at, :created_at, null)

-- :name get-tool-approval :? :1
select id, tool_name, status, input_json, input_hash, requested_permissions_json, requested_by, reason, actor, decision_reason, expires_at, created_at, decided_at
from tool_approvals
where id = :id

-- :name list-tool-approvals :? :*
select id, tool_name, status, input_json, input_hash, requested_permissions_json, requested_by, reason, actor, decision_reason, expires_at, created_at, decided_at
from tool_approvals
where (:status is null or status = :status)
order by created_at desc
limit :limit

-- :name decide-tool-approval :! :n
update tool_approvals
set status = :status,
    actor = :actor,
    decision_reason = :decision_reason,
    decided_at = :decided_at
where id = :id

-- :name count-tool-approvals :? :1
select count(*) as n
from tool_approvals
