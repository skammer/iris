-- :name insert-event :! :n
insert into agent_events (event_type, entity_type, entity_id, request_id, payload, created_at)
values (:event_type, :entity_type, :entity_id, :request_id, :payload, :created_at)

-- :name list-events :? :*
select id, event_type, entity_type, entity_id, request_id, payload, created_at
from agent_events
where (:entity_type is null or entity_type = :entity_type)
  and (:entity_id is null or entity_id = :entity_id)
  and (:event_type is null or event_type = :event_type)
  and (:request_id is null or request_id = :request_id)
  and (:after_id is null or id > :after_id)
order by id desc
limit :limit

-- :name search-events-like :? :*
select id, event_type, entity_type, entity_id, request_id, payload, created_at
from agent_events
where (event_type like :needle
       or entity_id like :needle
       or payload like :needle)
  and (:entity_type is null or entity_type = :entity_type)
  and (:entity_id is null or entity_id = :entity_id)
order by id desc
limit :limit

-- :name search-events-fts :? :*
select e.id, e.event_type, e.entity_type, e.entity_id, e.request_id, e.payload, e.created_at,
       bm25(agent_events_fts) as retrieval_score
from agent_events_fts
join agent_events e on e.id = agent_events_fts.rowid
where agent_events_fts match :query
  and (:entity_type is null or e.entity_type = :entity_type)
  and (:entity_id is null or e.entity_id = :entity_id)
order by retrieval_score asc, e.id desc
limit :limit

-- :name count-events :? :1
select count(*) as n
from agent_events

-- :name latest-event-id :? :1
select coalesce(max(id), 0) as id
from agent_events
