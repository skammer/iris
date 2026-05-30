(ns agent.persistence.sqlite.runs
  (:require
   [agent.persistence.sqlite.common :as common]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/runs.sql")

(declare get-agent-run
         get-agent-run-by-idempotency-key
         get-agent-run-activity
         get-agent-run-command
         latest-agent-run-lease
         list-agent-run-commands)

(defn- row->run [{:keys [id idempotency_key agent_id parent_run_id lease_id name substrate status capabilities_json
                         network_identity_json bootstrap_token bootstrap_spec_json
                         runner_metadata_json runner_options_json requested_by last_error
                         created_at started_at finished_at]}]
  {:id id
   :idempotency-key idempotency_key
   :agent-id agent_id
   :parent-run-id parent_run_id
   :lease-id lease_id
   :name name
   :substrate substrate
   :status status
   :capabilities (common/parse-json-string capabilities_json)
   :network-identity (common/parse-json-string network_identity_json)
   :bootstrap-token bootstrap_token
   :bootstrap-spec (common/parse-json-string bootstrap_spec_json)
   :runner-metadata (common/parse-json-string runner_metadata_json)
   :runner-options (common/parse-json-string runner_options_json)
   :requested-by requested_by
   :last-error last_error
   :created-at created_at
   :started-at started_at
   :finished-at finished_at})

(defn- row->lease [{:keys [id run_id holder_id status acquired_at expires_at released_at]}]
  {:id id
   :run-id run_id
   :holder-id holder_id
   :status status
   :acquired-at acquired_at
   :expires-at expires_at
   :released-at released_at})

(defn- row->heartbeat [{:keys [run_id sequence_no status metrics_json observed_at]}]
  {:run-id run_id
   :sequence-no sequence_no
   :status status
   :metrics (common/parse-json-string metrics_json)
   :observed-at observed_at})

(defn- row->command [{:keys [id run_id command_type payload_json request_id response_json status
                             created_at acknowledged_at completed_at error]}]
  {:id id
   :run-id run_id
   :command-type command_type
   :payload (common/parse-json-string payload_json)
   :request-id request_id
   :response (common/parse-json-string response_json)
   :status status
   :created-at created_at
   :acknowledged-at acknowledged_at
   :completed-at completed_at
   :error error})

(defn- row->checkpoint [{:keys [id run_id sequence_no checkpoint_type state_json created_at]}]
  {:id id
   :run-id run_id
   :sequence-no sequence_no
   :checkpoint-type checkpoint_type
   :state (common/parse-json-string state_json)
   :created-at created_at})

(defn- row->activity [{:keys [activity_key run_id command_id activity_name status input_json
                              result_json error created_at updated_at]}]
  {:activity-key activity_key
   :run-id run_id
   :command-id command_id
   :activity-name activity_name
   :status status
   :input (common/parse-json-string input_json)
   :result (common/parse-json-string result_json)
   :error error
   :created-at created_at
   :updated-at updated_at})

(defn- run-update-params [run-id updates]
  (merge {:id run-id
          :status nil
          :lease_id nil
          :network_identity_json nil
          :capabilities_json nil
          :bootstrap_spec_json nil
          :runner_metadata_json nil
          :runner_options_json nil
          :last_error nil
          :started_at nil
          :finished_at nil}
         updates))

(defn create-agent-run!
  [store {:keys [id idempotency-key agent-id parent-run-id lease-id name substrate status capabilities
                 network-identity bootstrap-token bootstrap-spec runner-metadata
                 runner-options requested-by last-error]
          :or {status "requested"}}]
  (or (when idempotency-key
        (common/with-connection
          store
          (fn [conn]
            (some-> (common/select-one conn
                                       (get-agent-run-by-idempotency-key-sqlvec
                                        {:idempotency_key idempotency-key})
                                       identity)
                    row->run))))
      (let [run {:id (or id (common/uuid-str))
             :idempotency_key idempotency-key
             :agent_id agent-id
             :parent_run_id parent-run-id
             :lease_id lease-id
             :name name
             :substrate (common/normalize-name substrate)
             :status (common/normalize-name status)
             :capabilities_json (common/json-string capabilities)
             :network_identity_json (common/json-string network-identity)
             :bootstrap_token bootstrap-token
             :bootstrap_spec_json (common/json-string bootstrap-spec)
             :runner_metadata_json (common/json-string runner-metadata)
             :runner_options_json (common/json-string runner-options)
             :requested_by requested-by
             :last_error last-error
             :created_at (common/now-str)}]
        (common/with-connection
          store
          (fn [conn]
            (common/execute! conn (create-agent-run-sqlvec run))))
        (or (get-agent-run store (:id run))
            (when idempotency-key
              (get-agent-run-by-idempotency-key store idempotency-key))
            (row->run run)))))

(defn get-agent-run [store run-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-agent-run-sqlvec {:id run-id}) identity)
              row->run))))

(defn get-agent-run-by-idempotency-key [store idempotency-key]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-agent-run-by-idempotency-key-sqlvec
                                  {:idempotency_key idempotency-key})
                                 identity)
              row->run))))

(defn list-agent-runs
  ([store] (list-agent-runs store {}))
  ([store {:keys [status parent-run-id limit] :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->run
             (common/select-many conn
                                 (list-agent-runs-sqlvec {:status status
                                                          :parent_run_id parent-run-id
                                                          :limit limit})
                                 identity))))))

(defn update-agent-run! [store run-id updates]
  (let [status (some-> (:status updates) common/normalize-name)
        started-at (or (:started-at updates)
                       (when (= status "running") (common/now-str)))
        finished-at (or (:finished-at updates)
                        (when (contains? #{"completed" "failed" "cancelled" "expired"} status)
                          (common/now-str)))]
    (common/with-connection
      store
      (fn [conn]
        (let [updated (common/execute! conn
                                       (update-agent-run-sqlvec
                                         (run-update-params
                                           run-id
                                           {:status status
                                            :lease_id (:lease-id updates)
                                            :network_identity_json (when (contains? updates :network-identity)
                                                                     (common/json-string (:network-identity updates)))
                                            :capabilities_json (when (contains? updates :capabilities)
                                                                 (common/json-string (:capabilities updates)))
                                            :bootstrap_spec_json (when (contains? updates :bootstrap-spec)
                                                                   (common/json-string (:bootstrap-spec updates)))
                                            :runner_metadata_json (when (contains? updates :runner-metadata)
                                                                    (common/json-string (:runner-metadata updates)))
                                            :runner_options_json (when (contains? updates :runner-options)
                                                                   (common/json-string (:runner-options updates)))
                                            :last_error (:last-error updates)
                                            :started_at started-at
                                            :finished_at finished-at})))]
          (when (zero? updated)
            (throw (ex-info "Agent run not found" {:type :run-not-found
                                                   :run-id run-id}))))))
    (get-agent-run store run-id)))

(defn create-agent-run-lease!
  [store {:keys [id run-id holder-id expires-at]
          :or {holder-id "runtime"}}]
  (let [lease {:id (or id (common/uuid-str))
               :run_id run-id
               :holder_id holder-id
               :acquired_at (common/now-str)
               :expires_at expires-at}]
    (common/with-transaction
     store
     (fn [conn]
       (common/execute! conn (create-agent-run-lease-sqlvec lease))
        (let [updated (common/execute! conn
                                      (update-agent-run-sqlvec
                                        (run-update-params run-id {:lease_id (:id lease)})))]
         (when (zero? updated)
           (throw (ex-info "Agent run not found"
                           {:type :run-not-found
                            :run-id run-id}))))))
    (or (latest-agent-run-lease store run-id)
        (assoc (row->lease (assoc lease :status "active" :released_at nil))
               :run-id run-id))))

(defn latest-agent-run-lease [store run-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (latest-agent-run-lease-sqlvec {:run_id run-id}) identity)
              row->lease))))

(defn renew-agent-run-lease! [store lease-id expires-at]
  (common/with-connection
   store
   (fn [conn]
     (let [updated (common/execute! conn
                                    (renew-agent-run-lease-sqlvec {:id lease-id
                                                                   :expires_at expires-at}))]
       (when (zero? updated)
         (throw (ex-info "Lease not found"
                         {:type :lease-not-found
                          :lease-id lease-id}))))))
  lease-id)

(defn release-agent-run-lease! [store lease-id]
  (let [released-at (common/now-str)]
    (common/with-connection
     store
     (fn [conn]
       (let [updated (common/execute! conn
                                      (release-agent-run-lease-sqlvec {:id lease-id
                                                                       :released_at released-at}))]
         (when (zero? updated)
           (throw (ex-info "Lease not found"
                           {:type :lease-not-found
                            :lease-id lease-id}))))))
    released-at))

(defn record-agent-run-heartbeat! [store {:keys [run-id sequence-no status metrics]}]
  (let [heartbeat {:run_id run-id
                   :sequence_no sequence-no
                   :status (common/normalize-name status)
                   :metrics_json (common/json-string metrics)
                   :observed_at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (insert-agent-run-heartbeat-sqlvec heartbeat))
        (or (when sequence-no
              (some-> (common/select-one conn
                                         (get-agent-run-heartbeat-by-sequence-sqlvec
                                          {:run_id run-id
                                           :sequence_no sequence-no})
                                         identity)
                      row->heartbeat))
            (row->heartbeat heartbeat))))))

(defn latest-agent-run-heartbeat [store run-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (latest-agent-run-heartbeat-sqlvec {:run_id run-id}) identity)
              row->heartbeat))))

(defn get-agent-run-heartbeat-by-sequence [store run-id sequence-no]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-agent-run-heartbeat-by-sequence-sqlvec
                                  {:run_id run-id
                                   :sequence_no sequence-no})
                                 identity)
              row->heartbeat))))

(defn list-agent-run-heartbeats
  ([store run-id] (list-agent-run-heartbeats store run-id {}))
  ([store run-id {:keys [since-sequence limit] :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->heartbeat
             (common/select-many conn
                                 (list-agent-run-heartbeats-sqlvec {:run_id run-id
                                                                    :since_sequence since-sequence
                                                                    :limit limit})
                                 identity))))))

(defn enqueue-agent-run-command! [store {:keys [run-id command-type payload request-id]
                                         :or {payload {}}}]
  (or (when request-id
        (first (list-agent-run-commands store run-id {:request-id request-id
                                                      :limit 1})))
      (let [command {:id (common/uuid-str)
                     :run_id run-id
                     :command_type (common/normalize-name command-type)
                     :payload_json (common/json-string payload)
                     :request_id request-id
                     :created_at (common/now-str)}]
        (common/with-connection
          store
          (fn [conn]
            (common/execute! conn (create-agent-run-command-sqlvec command))))
        (or (get-agent-run-command store (:id command))
            (when request-id
              (first (list-agent-run-commands store run-id {:request-id request-id
                                                            :limit 1})))
            (row->command (assoc command
                                 :response_json nil
                                 :status "pending"
                                 :acknowledged_at nil
                                 :completed_at nil
                                 :error nil))))))

(defn list-agent-run-commands
  ([store run-id] (list-agent-run-commands store run-id {}))
  ([store run-id {:keys [status request-id limit] :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->command
             (common/select-many conn
                                 (list-agent-run-commands-sqlvec {:run_id run-id
                                                                  :status status
                                                                  :request_id request-id
                                                                  :limit limit})
                                 identity))))))

(defn count-pending-agent-run-commands [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-pending-agent-run-commands-sqlvec) identity)
              :n
              int))))

(defn get-agent-run-command [store command-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-agent-run-command-sqlvec {:id command-id}) identity)
              row->command))))

(defn update-agent-run-command! [store command-id {:keys [status error response]}]
  (let [status* (some-> status common/normalize-name)
        now* (common/now-str)
        acknowledged-at (when (= status* "acknowledged") now*)
        completed-at (when (contains? #{"completed" "failed" "cancelled"} status*) now*)
        response-json (when (contains? #{"completed" "failed" "cancelled"} status*)
                        (common/json-string response))]
    (common/with-connection
      store
      (fn [conn]
        (let [updated (common/execute! conn
                                       (update-agent-run-command-sqlvec {:id command-id
                                                                         :status status*
                                                                         :acknowledged_at acknowledged-at
                                                                         :completed_at completed-at
                                                                         :error error
                                                                         :response_json response-json}))]
          (when (zero? updated)
            (throw (ex-info "Command not found" {:type :command-not-found
                                                 :command-id command-id}))))))
    (get-agent-run-command store command-id)))

(defn create-agent-run-checkpoint! [store {:keys [run-id sequence-no checkpoint-type state]}]
  (let [checkpoint-type* (common/normalize-name checkpoint-type)
        checkpoint {:id (common/uuid-str)
                    :run_id run-id
                    :sequence_no sequence-no
                    :checkpoint_type checkpoint-type*
                    :state_json (common/json-string state)
                    :created_at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (create-agent-run-checkpoint-sqlvec checkpoint))
        (or (when (and sequence-no checkpoint-type*)
              (some-> (common/select-one conn
                                         (get-agent-run-checkpoint-by-sequence-type-sqlvec
                                          {:run_id run-id
                                           :sequence_no sequence-no
                                           :checkpoint_type checkpoint-type*})
                                         identity)
                      row->checkpoint))
            (row->checkpoint checkpoint))))))

(defn latest-agent-run-checkpoint [store run-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (latest-agent-run-checkpoint-sqlvec {:run_id run-id}) identity)
              row->checkpoint))))

(defn get-agent-run-checkpoint-by-sequence-type [store run-id sequence-no checkpoint-type]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-agent-run-checkpoint-by-sequence-type-sqlvec
                                  {:run_id run-id
                                   :sequence_no sequence-no
                                   :checkpoint_type (common/normalize-name checkpoint-type)})
                                 identity)
              row->checkpoint))))

(defn list-agent-run-checkpoints
  ([store run-id] (list-agent-run-checkpoints store run-id {}))
  ([store run-id {:keys [since-sequence limit] :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->checkpoint
             (common/select-many conn
                                 (list-agent-run-checkpoints-sqlvec {:run_id run-id
                                                                     :since_sequence since-sequence
                                                                     :limit limit})
                                 identity))))))

(defn count-agent-runs [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-agent-runs-sqlvec) identity) :n int))))

(defn start-agent-run-activity!
  [store {:keys [activity-key run-id command-id activity-name input]}]
  (let [now* (common/now-str)
        activity {:activity_key activity-key
                  :run_id run-id
                  :command_id command-id
                  :activity_name (common/normalize-name activity-name)
                  :input_json (common/json-string input)
                  :created_at now*
                  :updated_at now*}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (start-agent-run-activity-sqlvec activity))))
    (get-agent-run-activity store activity-key)))

(defn get-agent-run-activity [store activity-key]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-agent-run-activity-sqlvec
                                  {:activity_key activity-key})
                                 identity)
              row->activity))))

(defn complete-agent-run-activity!
  [store activity-key {:keys [status result error]}]
  (let [status* (common/normalize-name status)]
    (common/with-connection
      store
      (fn [conn]
        (let [updated (common/execute! conn
                                       (complete-agent-run-activity-sqlvec
                                        {:activity_key activity-key
                                         :status status*
                                         :result_json (when (= "completed" status*)
                                                        (common/json-string result))
                                         :error error
                                         :updated_at (common/now-str)}))]
          (when (zero? updated)
            (throw (ex-info "Activity not found" {:type :activity-not-found
                                                  :activity-key activity-key}))))))
    (get-agent-run-activity store activity-key)))

(defn list-agent-run-activities
  ([store run-id] (list-agent-run-activities store run-id {}))
  ([store run-id {:keys [command-id limit] :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->activity
             (common/select-many conn
                                 (list-agent-run-activities-sqlvec
                                  {:run_id run-id
                                   :command_id command-id
                                   :limit limit})
                                 identity))))))
