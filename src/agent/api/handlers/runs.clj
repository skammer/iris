(ns agent.api.handlers.runs
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.options :as runner-options]
   [agent.runs.service :as runs]
   [agent.security :as security]
   [clojure.string :as str]))

(defn- not-found!
  [code message]
  (throw (errors/api-error 404 code message)))

(defn- run-recovery [system run-id]
  (runs/recovery-plan system run-id))

(defn list-runs* [system]
  (runs/list-runs system))

(defn get-run* [system run-id]
  (runs/get-run system run-id))

(defn request-run! [system req]
  (runs/request-run! system
                     (update req :substrate #(or % (runner-options/default-substrate system)))))

(defn- runner-status [system run-id]
  (runs/runner-status system run-id))

(defn launch-run! [system run-id]
  (try
    (runs/launch-run! system run-id)
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :run-not-found (not-found! "run_not_found" "Run not found")
        :runner-not-found (not-found! "runner_not_found" "Runner not found")
        :validation-failed (throw (errors/api-error 400 "bad_request" (.getMessage e) (ex-data e)))
        (throw e)))))

(defn signal-run! [system run-id command]
  (try
    (runs/signal-run! system run-id command)
    (catch clojure.lang.ExceptionInfo e
      (case (:type (ex-data e))
        :run-not-found (not-found! "run_not_found" "Run not found")
        :runner-not-found (not-found! "runner_not_found" "Runner not found")
        (throw e)))))

(def ^:private default-api-selectable-substrates
  #{:seatbelt :bubblewrap :docker :podman})

;; runner_options keys that select what/where/how code runs. A remote caller
;; must never control these — together they collapse the sandbox/auth threat
;; model into an arbitrary-exec endpoint. The server config supplies them at
;; launch (see runners.options/prepare-runner-options). Both kebab- and
;; snake-case spellings are stripped so neither JSON nor EDN clients slip one in.
(def ^:private execution-controlling-runner-option-keys
  #{:command
    :working-dir :working_dir
    :binds :mounts :env :user
    :share-network? :share-network :share_network
    :image :image-mode :image_mode :pull-policy :pull_policy
    :control-url :control_url
    :profile :profile-file :profile_file :profile-name :profile_name
    :profile-string :profile_string
    :sandbox-exec-binary :sandbox_exec_binary
    :read-only-paths :read_only_paths :read-write-paths :read_write_paths
    :host-working-dir :host_working_dir
    :container-working-dir :container_working_dir
    :container-home-dir :container_home_dir
    :container-data-dir :container_data_dir})

(defn- api-selectable-substrates [system]
  (set (or (get-in system [:config :runners :api-selectable-substrates])
           default-api-selectable-substrates)))

(defn- assert-api-substrate! [system substrate]
  (when (and substrate
             (not (contains? (api-selectable-substrates system) substrate)))
    (throw (errors/api-error 400 "bad_request"
                             (str "Substrate not selectable via API: " (name substrate))
                             {:substrate substrate
                              :allowed (vec (sort (api-selectable-substrates system)))}))))

(defn- sanitize-api-runner-options [runner-options]
  (when (map? runner-options)
    (let [safe (apply dissoc runner-options execution-controlling-runner-option-keys)]
      (when (seq safe) safe))))

(defn- normalize-run-request [system body]
  (let [substrate (some-> (:substrate body) keyword)]
    (assert-api-substrate! system substrate)
    {:agent-id (:agent_id body)
     :parent-run-id (:parent_run_id body)
     :idempotency-key (:idempotency_key body)
     :name (:name body)
     :substrate substrate
     :capabilities (or (:capabilities body) [])
     :network-identity (:network_identity body)
     :runner-options (sanitize-api-runner-options (:runner_options body))
     :requested-by (or (:requested_by body) "api")
     :auto-launch? (true? (:auto_launch body))}))

(defn list-runs [system _request]
  (responses/json-response 200 {:data (mapv ser/run->response (list-runs* system))}))

(defn get-run [system _request run-id]
  (if-let [run (get-run* system run-id)]
    (responses/json-response 200
                             {:data (assoc (ser/run->response run)
                                           :runner_status (runner-status system run-id)
                                           :recovery (run-recovery system run-id)
                                           :container_contract (runs/container-image-contract system run-id))})
    (not-found! "run_not_found" "Run not found")))

(defn create [system request]
  ;; Use the Malli-coerced body (request :parameters :body), not raw JSON, so the
  ;; route schema actually gates input instead of being validated then ignored.
  (let [body (or (-> request :parameters :body) (h/read-json-body request))
        req (cond-> (normalize-run-request system body)
              (and (nil? (:idempotency_key body))
                   (h/header request "Idempotency-Key"))
              (assoc :idempotency-key (h/header request "Idempotency-Key")))
        run (request-run! system req)
        launched-run (when (:auto-launch? req)
                       (launch-run! system (:id run)))]
    (responses/json-response 201
                             {:data (ser/run->response (or launched-run (get-run* system (:id run))))})))

(defn launch [system _request run-id]
  (responses/json-response 200 {:data (ser/run->response (launch-run! system run-id))}))

(defn signal [system request run-id]
  (let [{:keys [command_type]} (or (-> request :parameters :body)
                                   (h/read-json-body request))]
    (responses/json-response 200
                             {:data (signal-run! system run-id {:command-type command_type})})))

(defn heartbeats [system request run-id]
  (let [{:keys [limit since_sequence]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/heartbeat->response
                                          (runs/list-run-heartbeats system run-id
                                                                    (cond-> {}
                                                                      limit (assoc :limit limit)
                                                                      since_sequence (assoc :since-sequence since_sequence))))})))

(defn checkpoints [system request run-id]
  (let [{:keys [limit since_sequence]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/checkpoint->response
                                          (runs/list-run-checkpoints system run-id
                                                                     (cond-> {}
                                                                       limit (assoc :limit limit)
                                                                       since_sequence (assoc :since-sequence since_sequence))))})))

(defn commands [system request run-id]
  (let [{:keys [limit status request_id]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/run-command->response
                                          (runs/list-run-commands system run-id
                                                                  (cond-> {}
                                                                    limit (assoc :limit limit)
                                                                    request_id (assoc :request-id request_id)
                                                                    status (assoc :status status))))})))

(defn- ensure-run-control! [system request run-id]
  (let [run (or (get-run* system run-id)
                (not-found! "run_not_found" "Run not found"))
        token (h/control-token request)]
    (when-not (and (not (str/blank? token))
                   (not (str/blank? (:bootstrap-token run)))
                   (security/constant-time= token (:bootstrap-token run)))
      (throw (errors/api-error 401 "unauthorized" "Invalid run control token")))
    run))

(defn control-register [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        run (runs/register-run! system
                                run-id
                                {:capabilities (or (:capabilities body) [])
                                 :network-identity (h/body-value body :network-identity :network_identity)
                                 :runner-metadata (h/body-value body :runner-metadata :runner_metadata)})]
    (responses/json-response 200 {:data (ser/run->response run)})))

(defn control-heartbeat [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        heartbeat (runs/heartbeat-run! system
                                       run-id
                                       {:sequence-no (h/body-value body :sequence-no :sequence_no)
                                        :status (keyword (or (:status body) "running"))
                                        :metrics (:metrics body)
                                        :lease-id (h/body-value body :lease-id :lease_id)})]
    (responses/json-response 200 {:data (ser/heartbeat->response heartbeat)})))

(defn control-checkpoint [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        checkpoint (runs/checkpoint-run! system
                                         run-id
                                         {:sequence-no (h/body-value body :sequence-no :sequence_no)
                                          :checkpoint-type (keyword (or (h/body-value body :checkpoint-type :checkpoint_type)
                                                                        "state"))
                                          :state (:state body)})]
    (responses/json-response 200 {:data (ser/checkpoint->response checkpoint)})))

(defn control-commands [system request run-id]
  (ensure-run-control! system request run-id)
  (responses/json-response 200
                           {:data (mapv ser/run-command->response
                                        (runs/pending-run-commands system run-id))}))

(defn control-command-ack [system request run-id command-id]
  (ensure-run-control! system request run-id)
  (runs/acknowledge-run-command! system run-id command-id)
  (responses/json-response 200 {:data {:id command-id :status "acknowledged"}}))

(defn control-command-complete [system request run-id command-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        status (keyword (or (:status body) "completed"))
        command (runs/complete-run-command! system
                                            run-id
                                            command-id
                                            status
                                            (:error body)
                                            (:response body))]
    (responses/json-response 200 {:data (ser/run-command->response command)})))

(defn control-transition [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        status (keyword (or (:status body) "running"))
        run (runs/transition-run! system
                                  run-id
                                  status
                                  {:last-error (h/body-value body :last-error :last_error)
                                   :runner-metadata (h/body-value body :runner-metadata :runner_metadata)})]
    (responses/json-response 200 {:data (ser/run->response run)})))

(defn run-events [system request run-id]
  (let [{:keys [limit after_id]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/event->response
                                          (sqlite/list-events (:store system)
                                                              (cond-> {:entity-type :agent_run
                                                                       :entity-id run-id}
                                                                after_id (assoc :after-id after_id)
                                                                limit (assoc :limit limit))))})))

(defn- relevant-run-event? [event run-id]
  (and (= "agent_run" (:entity-type event))
       (= run-id (:entity-id event))))

(defn events-stream-response
  [system run-id request]
  (let [{:keys [after_id replay_limit]} (-> request :parameters :query)
        broker-instance (or (:event-bus system) (:broker system))
        replay-limit (or replay_limit 100)
        replay-messages (broker/replay! broker-instance
                                        (broker/run-events-subject run-id)
                                        {:after-id after_id
                                         :limit replay-limit})]
    (streaming/managed-response
     request
     {:name :run-events-stream
      :on-error (fn [ctx error]
                  (streaming/send-sse-error! ctx "stream_error" (.getMessage error)))}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-runs-subject)
                                                {:buffer-size 256
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)]
         (when-let [run (get-run* system run-id)]
           (streaming/send-sse-chunk! ctx
                                      {:type "snapshot"
                                       :run (ser/run->response run)}))
         (doseq [message replay-messages]
           (when (relevant-run-event? (:payload message) run-id)
             (streaming/send-sse-chunk! ctx
                                        {:type "event"
                                         :data (ser/event->response (:payload message))})))
         (loop []
           (when-let [event (streaming/take! ctx ch)]
             (when (relevant-run-event? (:payload event) run-id)
               (streaming/send-sse-chunk! ctx
                                          {:type "event"
                                           :data (ser/event->response (:payload event))}))
            (recur))))))))

(defn wait [system request run-id]
  (let [{:keys [timeout_ms interval_ms]} (-> request :parameters :query)
        timeout-ms (or timeout_ms 15000)
        interval-ms (or interval_ms 250)]
    (if-let [run (runs/wait-for-run! system run-id
                                     {:timeout-ms timeout-ms
                                      :interval-ms interval-ms})]
      (responses/json-response 200
                               {:data (assoc (ser/run->response run)
                                             :recovery (run-recovery system run-id)
                                             :container_contract (runs/container-image-contract system run-id))})
      (not-found! "run_not_found" "Run not found"))))

(defn recover [system _request run-id]
  (if-let [_ (get-run* system run-id)]
    (let [replacement (runs/retry-run! system run-id)]
      (responses/json-response 202
                               {:data {:recovery (run-recovery system run-id)
                                       :replacement_run (ser/run->response replacement)}}))
    (not-found! "run_not_found" "Run not found")))

(defn reclaim-stale [system _request]
  (let [results (runs/reclaim-stale-runs! system)]
    (responses/json-response 200
                             {:data (mapv (fn [{:keys [reclaimed replacement]}]
                                            {:reclaimed (ser/run->response reclaimed)
                                             :replacement (some-> replacement ser/run->response)})
                                          results)})))
