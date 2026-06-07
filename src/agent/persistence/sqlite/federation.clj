(ns agent.persistence.sqlite.federation
  (:require
   [agent.persistence.sqlite.common :as common]))

(declare row->outbox)

(defn upsert-peer-key!
  [store {:keys [peer-id key-id public-key status valid-from valid-until]}]
  (let [record {:peer-id peer-id
                :key-id key-id
                :public-key public-key
                :status (or status "active")
                :valid-from valid-from
                :valid-until valid-until
                :created-at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute!
         conn
         ["INSERT OR REPLACE INTO federation_peer_keys
           (peer_id, key_id, public_key, status, valid_from, valid_until, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)"
          (:peer-id record)
          (:key-id record)
          (:public-key record)
          (:status record)
          (:valid-from record)
          (:valid-until record)
          (:created-at record)])))
    record))

(defn get-peer-key
  [store peer-id key-id]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one
       conn
       ["SELECT peer_id, key_id, public_key, status, valid_from, valid_until, created_at
         FROM federation_peer_keys
         WHERE peer_id = ? AND key_id = ?"
        peer-id key-id]
       (fn [{:keys [peer_id key_id public_key status valid_from valid_until created_at]}]
         {:peer-id peer_id
          :key-id key_id
          :public-key public_key
          :status status
          :valid-from valid_from
	          :valid-until valid_until
	          :created-at created_at})))))

(defn insert-nonce!
  [store {:keys [peer-id nonce seen-at expires-at]}]
  (common/with-transaction
    store
    (fn [conn]
      (common/execute!
       conn
       ["DELETE FROM federation_nonces WHERE expires_at < ?" (common/now-str)])
      (common/execute!
       conn
       ["INSERT INTO federation_nonces (peer_id, nonce, seen_at, expires_at)
         VALUES (?, ?, ?, ?)"
        peer-id nonce (or seen-at (common/now-str)) expires-at]))))

(defn create-outbox!
  [store {:keys [id peer-id key-id url envelope state attempt-count next-attempt-at last-error last-status]}]
  (let [now (common/now-str)
        record {:id (or id (common/uuid-str))
                :peer-id peer-id
                :key-id key-id
                :url url
                :envelope envelope
                :state (or state "queued")
                :attempt-count (long (or attempt-count 0))
                :next-attempt-at next-attempt-at
                :last-error last-error
                :last-status last-status
                :created-at now
                :updated-at now}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute!
         conn
         ["INSERT INTO federation_outbox
           (id, peer_id, key_id, url, envelope_json, state, attempt_count,
            next_attempt_at, last_error, last_status, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
          (:id record)
          (:peer-id record)
          (:key-id record)
          (:url record)
          (common/json-string (:envelope record))
          (:state record)
          (:attempt-count record)
          (:next-attempt-at record)
          (:last-error record)
          (:last-status record)
          (:created-at record)
          (:updated-at record)])))
	    record))

(defn claim-due-outbox!
  [store {:keys [limit now]
          :or {limit 25}}]
  (common/with-transaction
    store
    (fn [conn]
      (let [now* (or now (common/now-str))
	            rows (common/select-many
                  conn
                  ["SELECT id, peer_id, key_id, url, envelope_json, state, attempt_count,
                           next_attempt_at, last_error, last_status, created_at, updated_at
                    FROM federation_outbox
                    WHERE state = 'queued'
                      AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                    ORDER BY created_at ASC
                    LIMIT ?"
                   now*
                   (long limit)]
                  row->outbox)
            ids (mapv :id rows)]
        (doseq [id ids]
          (common/execute!
           conn
           ["UPDATE federation_outbox
             SET state = 'in_flight',
                 updated_at = ?
             WHERE id = ? AND state = 'queued'"
            now*
            id]))
        rows))))

(defn update-outbox!
  [store id {:keys [state attempt-count next-attempt-at last-error last-status]}]
  (common/with-connection
    store
    (fn [conn]
      (common/execute!
       conn
       ["UPDATE federation_outbox
         SET state = COALESCE(?, state),
             attempt_count = COALESCE(?, attempt_count),
             next_attempt_at = ?,
             last_error = ?,
             last_status = ?,
             updated_at = ?
         WHERE id = ?"
        state
        attempt-count
        next-attempt-at
        last-error
        last-status
        (common/now-str)
	        id]))))

(defn mark-outbox-retry!
  [store id {:keys [attempt-count next-attempt-at last-error last-status]}]
  (update-outbox! store id {:state "queued"
                            :attempt-count attempt-count
                            :next-attempt-at next-attempt-at
                            :last-error last-error
                            :last-status last-status}))

(defn mark-outbox-acked!
  [store id {:keys [attempt-count last-status]}]
  (update-outbox! store id {:state "acked"
                            :attempt-count attempt-count
                            :next-attempt-at nil
                            :last-error nil
                            :last-status last-status}))

(defn mark-outbox-dead-letter!
  [store id {:keys [attempt-count last-error last-status]}]
  (update-outbox! store id {:state "dead_letter"
                            :attempt-count attempt-count
                            :next-attempt-at nil
                            :last-error last-error
                            :last-status last-status}))

(defn row->outbox
  [{:keys [id peer_id key_id url envelope_json state attempt_count next_attempt_at
           last_error last_status created_at updated_at]}]
  {:id id
   :peer-id peer_id
   :key-id key_id
   :url url
   :envelope (common/parse-json-string envelope_json)
   :state state
   :attempt-count (long attempt_count)
   :next-attempt-at next_attempt_at
   :last-error last_error
   :last-status last_status
   :created-at created_at
   :updated-at updated_at})

(defn get-outbox
  [store id]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one
       conn
       ["SELECT id, peer_id, key_id, url, envelope_json, state, attempt_count,
                next_attempt_at, last_error, last_status, created_at, updated_at
         FROM federation_outbox
         WHERE id = ?"
        id]
       row->outbox))))

(defn count-outbox [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn ["SELECT count(*) AS n FROM federation_outbox"] identity) :n int))))

(defn count-peer-keys [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn ["SELECT count(*) AS n FROM federation_peer_keys"] identity) :n int))))
