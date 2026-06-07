-- :name upsert-peer-key :! :n
insert into federation_peer_keys
  (peer_id, key_id, public_key, status, valid_from, valid_until, created_at)
values
  (:peer_id, :key_id, :public_key, :status, :valid_from, :valid_until, :created_at)
on conflict(peer_id, key_id) do update set
  public_key = excluded.public_key,
  status = excluded.status,
  valid_from = excluded.valid_from,
  valid_until = excluded.valid_until

-- :name get-peer-key :? :1
select peer_id, key_id, public_key, status, valid_from, valid_until, created_at
from federation_peer_keys
where peer_id = :peer_id
  and key_id = :key_id
limit 1

-- :name delete-expired-nonces :! :n
delete from federation_nonces
where expires_at < :now

-- :name insert-nonce :! :n
insert into federation_nonces (peer_id, nonce, seen_at, expires_at)
values (:peer_id, :nonce, :seen_at, :expires_at)

-- :name create-outbox :! :n
insert into federation_outbox
  (id, peer_id, key_id, url, envelope_json, state, attempt_count,
   next_attempt_at, last_error, last_status, created_at, updated_at)
values
  (:id, :peer_id, :key_id, :url, :envelope_json, :state, :attempt_count,
   :next_attempt_at, :last_error, :last_status, :created_at, :updated_at)

-- :name claim-due-outbox :? :*
with due as (
  select id
  from federation_outbox
  where state = 'queued'
    and (next_attempt_at is null or next_attempt_at <= :now)
  order by created_at asc
  limit :limit
)
update federation_outbox
set state = 'in_flight',
    updated_at = :updated_at
where id in (select id from due)
  and state = 'queued'
returning id, peer_id, key_id, url, envelope_json, state, attempt_count,
          next_attempt_at, last_error, last_status, created_at, updated_at

-- :name update-outbox-state :? :1
update federation_outbox
set state = :state,
    attempt_count = coalesce(:attempt_count, attempt_count),
    next_attempt_at = :next_attempt_at,
    last_error = :last_error,
    last_status = :last_status,
    updated_at = :updated_at
where id = :id
returning id, peer_id, key_id, url, envelope_json, state, attempt_count,
          next_attempt_at, last_error, last_status, created_at, updated_at

-- :name get-outbox :? :1
select id, peer_id, key_id, url, envelope_json, state, attempt_count,
       next_attempt_at, last_error, last_status, created_at, updated_at
from federation_outbox
where id = :id
limit 1

-- :name count-outbox :? :1
select count(*) as n
from federation_outbox

-- :name count-peer-keys :? :1
select count(*) as n
from federation_peer_keys
