(ns agent.api.handlers.runs
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.api.validation :as v]
   [agent.broker.core :as broker]
   [agent.health :as health]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.core :as runners]
   [agent.runners.docker-podman :as docker-podman]
   [agent.runners.options :as runner-options]
   [agent.runtime.core :as runtime]
   [clojure.core.async :as async]))

(defn- run-recovery [system run-id]
  (runtime/recovery-plan (:runtime-service system) run-id))

(defn- run-container-contract [run]
  (when (#{"docker" "podman"} (:substrate run))
    (docker-podman/image-contract (:runner-options run))))

(defn list-runs* [system]
  (runtime/list-runs (:runtime-service system)))

(defn get-run* [system run-id]
  (runtime/get-run (:runtime-service system) run-id))

(defn request-run! [system req]
  (runtime/request-run! (:runtime-service system)
                        (runtime/create-run-request
                         (update req :substrate #(or % (runner-options/default-substrate system))))))

(defn- runner-status [system run-id]
  (when-let [run (get-run* system run-id)]
    (when-let [runner (get (:runner-registry system) (keyword (:substrate run)))]
      (runners/status runner run-id))))

(defn launch-run! [system run-id]
  (let [run (or (get-run* system run-id)
                (throw (errors/api-error 404 "run_not_found" "Run not found")))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (errors/api-error 404 "runner_not_found" "Runner not found")))
        checkpoint-seq (or (get-in run [:checkpoint :sequence-no]) 0)]
    (try
      (let [run-spec (runners/create-run-spec
                      {:run-id (:id run)
                       :agent-id (:agent-id run)
                       :parent-run-id (:parent-run-id run)
                       :lease-id (:lease-id run)
                       :name (:name run)
                       :substrate (keyword (:substrate run))
                       :capabilities (:capabilities run)
                       :network-identity (:network-identity run)
                       :bootstrap-token (:bootstrap-token run)
                       :bootstrap-spec (assoc (:bootstrap-spec run)
                                              :checkpoint-seq checkpoint-seq)
                       :requested-by (:requested-by run)
                       :runner-options (runner-options/prepare-runner-options system run)})
            launch-result (:result (runtime/execute-activity!
                                    (:runtime-service system)
                                    {:run-id run-id
                                     :activity-name :runner.launch
                                     :input run-spec}
                                    #(runners/launch runner run-spec)))]
        (runtime/transition-run! (:runtime-service system) run-id :launched
                                 {:runner-metadata launch-result})
        (health/mark-ok! (:health-registry system) :runtime)
        (get-run* system run-id))
      (catch clojure.lang.ExceptionInfo e
        (health/mark-error! (:health-registry system) :runtime e)
        (case (:type (ex-data e))
          :validation-failed (throw (errors/api-error 400 "bad_request" (.getMessage e) (ex-data e)))
          (throw e)))
      (catch Exception e
        (health/mark-error! (:health-registry system) :runtime e)
        (throw e)))))

(defn signal-run! [system run-id command]
  (let [run (or (get-run* system run-id)
                (throw (errors/api-error 404 "run_not_found" "Run not found")))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (errors/api-error 404 "runner_not_found" "Runner not found")))
        command-type (keyword (:command-type command))
        signal-result (:result (runtime/execute-activity!
                                (:runtime-service system)
                                {:run-id run-id
                                 :activity-name (keyword (str "runner.signal." (name command-type)))
                                 :input command}
                                #(runners/signal runner run-id command)))]
    (when (contains? #{:cancel :terminate :kill} command-type)
      (runtime/transition-run! (:runtime-service system)
                               run-id
                               :cancelled
                               {:runner-metadata (merge (:runner-metadata run) signal-result)}))
    signal-result))

(defn- normalize-run-request [body]
  {:agent-id (:agent_id body)
   :parent-run-id (:parent_run_id body)
   :idempotency-key (:idempotency_key body)
   :name (:name body)
   :substrate (some-> (:substrate body) keyword)
   :capabilities (or (:capabilities body) [])
   :network-identity (:network_identity body)
   :runner-options (:runner_options body)
   :requested-by (or (:requested_by body) "api")
   :auto-launch? (true? (:auto_launch body))})

(defn list-runs [system _request]
  (responses/json-response 200 {:data (mapv ser/run->response (list-runs* system))}))

(defn get-run [system _request run-id]
  (if-let [run (get-run* system run-id)]
    (responses/json-response 200
                             {:data (assoc (ser/run->response run)
                                           :runner_status (runner-status system run-id)
                                           :recovery (run-recovery system run-id)
                                           :container_contract (run-container-contract run))})
    (throw (errors/api-error 404 "run_not_found" "Run not found"))))

(defn create [system request]
  (let [body (h/read-json-body request)
        req (cond-> (normalize-run-request body)
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
  (let [{:keys [command_type]} (h/read-json-body request)]
    (responses/json-response 200
                             {:data (signal-run! system run-id {:command-type command_type})})))

(defn heartbeats [system request run-id]
  (let [{:keys [limit since_sequence]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/heartbeat->response
                                          (runtime/list-heartbeats (:runtime-service system) run-id
                                                                   (cond-> {}
                                                                     limit (assoc :limit limit)
                                                                     since_sequence (assoc :since-sequence since_sequence))))})))

(defn checkpoints [system request run-id]
  (let [{:keys [limit since_sequence]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/checkpoint->response
                                          (runtime/list-checkpoints (:runtime-service system) run-id
                                                                    (cond-> {}
                                                                      limit (assoc :limit limit)
                                                                      since_sequence (assoc :since-sequence since_sequence))))})))

(defn commands [system request run-id]
  (let [{:keys [limit status request_id]} (-> request :parameters :query)]
    (responses/json-response 200
                             {:data (mapv ser/run-command->response
                                          (runtime/list-commands (:runtime-service system) run-id
                                                                 (cond-> {}
                                                                   limit (assoc :limit limit)
                                                                   request_id (assoc :request-id request_id)
                                                                   status (assoc :status status))))})))

(defn- ensure-run-control! [system request run-id]
  (let [run (or (get-run* system run-id)
                (throw (errors/api-error 404 "run_not_found" "Run not found")))
        token (h/control-token request)]
    (when-not (and token (= token (:bootstrap-token run)))
      (throw (errors/api-error 401 "unauthorized" "Invalid run control token")))
    run))

(defn control-register [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        run (runtime/register-run! (:runtime-service system)
                                   run-id
                                   {:capabilities (or (:capabilities body) [])
                                    :network-identity (h/body-value body :network-identity :network_identity)
                                    :runner-metadata (h/body-value body :runner-metadata :runner_metadata)})]
    (responses/json-response 200 {:data (ser/run->response run)})))

(defn control-heartbeat [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        heartbeat (runtime/heartbeat! (:runtime-service system)
                                      run-id
                                      {:sequence-no (h/body-value body :sequence-no :sequence_no)
                                       :status (keyword (or (:status body) "running"))
                                       :metrics (:metrics body)
                                       :lease-id (h/body-value body :lease-id :lease_id)})]
    (responses/json-response 200 {:data (ser/heartbeat->response heartbeat)})))

(defn control-checkpoint [system request run-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        checkpoint (runtime/checkpoint! (:runtime-service system)
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
                                        (runtime/pending-commands (:runtime-service system) run-id))}))

(defn control-command-ack [system request run-id command-id]
  (ensure-run-control! system request run-id)
  (runtime/acknowledge-command! (:runtime-service system) run-id command-id)
  (responses/json-response 200 {:data {:id command-id :status "acknowledged"}}))

(defn control-command-complete [system request run-id command-id]
  (ensure-run-control! system request run-id)
  (let [body (h/read-json-body request)
        status (keyword (or (:status body) "completed"))
        command (runtime/complete-command! (:runtime-service system)
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
        run (runtime/transition-run! (:runtime-service system)
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
                                         :limit replay-limit})
        subscription (broker/subscribe! broker-instance
                                        (broker/all-runs-subject)
                                        {:buffer-size 256
                                         :buffer-strategy :sliding
                                         :slow-client :drop-new})
        ch (:channel subscription)
        open? (atom true)]
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (when-let [run (get-run* system run-id)]
             (streaming/send-sse-chunk! channel
                                        {:type "snapshot"
                                         :run (ser/run->response run)}))
           (doseq [message replay-messages]
             (when (relevant-run-event? (:payload message) run-id)
               (streaming/send-sse-chunk! channel
                                          {:type "event"
                                           :data (ser/event->response (:payload message))})))
           (loop []
             (when @open?
               (when-let [event (async/<!! ch)]
                 (when (relevant-run-event? (:payload event) run-id)
                   (streaming/send-sse-chunk! channel
                                              {:type "event"
                                               :data (ser/event->response (:payload event))}))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn wait [system request run-id]
  (let [{:keys [timeout_ms interval_ms]} (-> request :parameters :query)
        timeout-ms (or timeout_ms 15000)
        interval-ms (or interval_ms 250)]
    (if-let [run (runtime/wait-for-run! (:runtime-service system) run-id
                                        {:timeout-ms timeout-ms
                                         :interval-ms interval-ms})]
      (responses/json-response 200
                               {:data (assoc (ser/run->response run)
                                             :recovery (run-recovery system run-id)
                                             :container_contract (run-container-contract run))})
      (throw (errors/api-error 404 "run_not_found" "Run not found")))))

(defn recover [system _request run-id]
  (if-let [_ (get-run* system run-id)]
    (try
      (let [replacement (runtime/retry-run! (:runtime-service system) run-id)]
        (health/bump-restart! (:health-registry system) :runtime)
        (health/mark-ok! (:health-registry system) :runtime)
        (responses/json-response 202
                                 {:data {:recovery (run-recovery system run-id)
                                         :replacement_run (ser/run->response replacement)}}))
      (catch Exception e
        (health/mark-error! (:health-registry system) :runtime e)
        (throw e)))
    (throw (errors/api-error 404 "run_not_found" "Run not found"))))

(defn reclaim-stale [system _request]
  (try
    (let [results (runtime/reclaim-stale-runs! (:runtime-service system))]
      (doseq [_ (filter :replacement results)]
        (health/bump-restart! (:health-registry system) :runtime))
      (health/mark-ok! (:health-registry system) :runtime)
      (responses/json-response 200
                               {:data (mapv (fn [{:keys [reclaimed replacement]}]
                                              {:reclaimed (ser/run->response reclaimed)
                                               :replacement (some-> replacement ser/run->response)})
                                            results)}))
    (catch Exception e
      (health/mark-error! (:health-registry system) :runtime e)
      (throw e))))
