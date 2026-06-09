(ns agent.persistence.sqlite.federation
  (:require
   [agent.persistence.sqlite.common :as common]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/federation.sql")

(def ^:private peer-key-statuses #{"active" "inactive" "revoked"})
(def ^:private outbox-states #{"queued" "in_flight" "acked" "dead_letter"})

(defn- valid-status! [allowed field value]
  (common/valid-enum! (common/normalize-name value) allowed
                      {:message (str "Invalid federation " (name field))
                       :type :invalid-federation-state
                       :field field
                       :value value
                       :allowed allowed}))

(defn- row->peer-key
  [{:keys [peer_id key_id public_key status valid_from valid_until created_at]}]
  {:peer-id peer_id
   :key-id key_id
   :public-key public_key
   :status status
   :valid-from valid_from
   :valid-until valid_until
   :created-at created_at})

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

(defn- outbox-row
  [{:keys [id peer-id key-id url envelope state attempt-count next-attempt-at last-error last-status]}]
  (let [now (common/now-str)]
    {:id (or id (common/uuid-str))
     :peer_id peer-id
     :key_id key-id
     :url url
     :envelope_json (common/json-string envelope)
     :state (valid-status! outbox-states :state (or state "queued"))
     :attempt_count (long (or attempt-count 0))
     :next_attempt_at next-attempt-at
     :last_error last-error
     :last_status last-status
     :created_at now
     :updated_at now}))

(defn upsert-peer-key!
  [store {:keys [peer-id key-id public-key status valid-from valid-until]}]
  (let [row {:peer_id peer-id
             :key_id key-id
             :public_key public-key
             :status (valid-status! peer-key-statuses :status (or status "active"))
             :valid_from valid-from
             :valid_until valid-until
             :created_at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (upsert-peer-key-sqlvec row))
        (row->peer-key (common/select-one conn (get-peer-key-sqlvec row) identity))))))

(defn get-peer-key
  [store peer-id key-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-peer-key-sqlvec {:peer_id peer-id
                                                       :key_id key-id})
                                 identity)
              row->peer-key))))

(defn insert-nonce!
  [store {:keys [peer-id nonce seen-at expires-at]}]
  (common/with-transaction
    store
    (fn [conn]
      (let [now (common/now-str)]
        (common/execute! conn (delete-expired-nonces-sqlvec {:now now}))
        (common/execute! conn
                         (insert-nonce-sqlvec {:peer_id peer-id
                                               :nonce nonce
                                               :seen_at (or seen-at now)
                                               :expires_at expires-at}))))))

(defn create-outbox!
  [store outbox]
  (let [row (outbox-row outbox)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (create-outbox-sqlvec row))))
    (row->outbox row)))

(defn claim-due-outbox!
  [store {:keys [limit now] :or {limit 25}}]
  (common/with-transaction
    store
    (fn [conn]
      (let [now* (or now (common/now-str))]
        (->> (common/select-many conn
                                 (claim-due-outbox-sqlvec
                                  {:now now*
                                   :updated_at now*
                                   :limit (common/bounded-limit limit 25 100)})
                                 row->outbox)
             (sort-by :created-at)
             vec)))))

(defn- update-outbox-state!
  [store id {:keys [state attempt-count next-attempt-at last-error last-status]}]
  (let [state* (valid-status! outbox-states :state state)
        now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (or (some-> (common/select-one conn
                                       (update-outbox-state-sqlvec
                                        {:id id
                                         :state state*
                                         :attempt_count attempt-count
                                         :next_attempt_at next-attempt-at
                                         :last_error last-error
                                         :last_status last-status
                                         :updated_at now})
                                       identity)
                    row->outbox)
            (throw (ex-info "Federation outbox not found"
                            {:type :federation-outbox-not-found
                             :id id})))))))

(defn mark-outbox-retry!
  [store id {:keys [attempt-count next-attempt-at last-error last-status]}]
  (update-outbox-state! store id {:state "queued"
                                  :attempt-count attempt-count
                                  :next-attempt-at next-attempt-at
                                  :last-error last-error
                                  :last-status last-status}))

(defn mark-outbox-acked!
  [store id {:keys [attempt-count last-status]}]
  (update-outbox-state! store id {:state "acked"
                                  :attempt-count attempt-count
                                  :next-attempt-at nil
                                  :last-error nil
                                  :last-status last-status}))

(defn mark-outbox-dead-letter!
  [store id {:keys [attempt-count last-error last-status]}]
  (update-outbox-state! store id {:state "dead_letter"
                                  :attempt-count attempt-count
                                  :next-attempt-at nil
                                  :last-error last-error
                                  :last-status last-status}))

(defn get-outbox
  [store id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-outbox-sqlvec {:id id}) identity)
              row->outbox))))

(defn count-outbox [store]
  (common/count-rows store (count-outbox-sqlvec)))

(defn count-peer-keys [store]
  (common/count-rows store (count-peer-keys-sqlvec)))
