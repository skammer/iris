(ns agent.api.handlers.runs
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.defaults :as defaults]
   [agent.persistence.sqlite :as sqlite]
   [agent.runs.service :as runs]))

(defn- not-found!
  [code message]
  (throw (errors/api-error 404 code message)))

(defn- run-recovery [system run-id]
  (runs/recovery-plan system run-id))

(defn- normalize-run-request [_system body]
  (let [substrate (or (some-> (:substrate body) keyword)
                      :external)]
    (when (not= :external substrate)
      (throw (errors/api-error 400 "bad_request"
                               "Only external runs are supported"
                               {:substrate substrate
                                :allowed ["external"]})))
    {:agent-id (:agent_id body)
     :parent-run-id (:parent_run_id body)
     :idempotency-key (:idempotency_key body)
     :name (:name body)
     :substrate substrate
     :capabilities (or (:capabilities body) [])
     :network-identity (:network_identity body)
     :requested-by (or (:requested_by body) "api")}))

(defn- reject-run-options! [raw-body]
  (let [rejected (->> [:run_options "run_options" :runner_options "runner_options"]
                      (filter #(contains? raw-body %))
                      seq)]
    (when rejected
      (throw (errors/api-error 400 "bad_request"
                               "Run creation does not accept execution options"
                               {:rejected-keys (mapv name rejected)})))))

(defn list-runs [system _request]
  (responses/json-response 200 {:data (mapv ser/run->response (runs/list-runs system))}))

(defn get-run [system _request run-id]
  (if-let [run (runs/get-run system run-id)]
    (responses/json-response 200
                             {:data (assoc (ser/run->response run)
                                           :recovery (run-recovery system run-id))})
    (not-found! "run_not_found" "Run not found")))

(defn create [system request]
  (let [raw-body (h/read-json-body request)
        _ (reject-run-options! raw-body)
        body (or (-> request :parameters :body)
                 raw-body)
        req (cond-> (normalize-run-request system body)
              (and (nil? (:idempotency_key body))
                   (h/header request "Idempotency-Key"))
              (assoc :idempotency-key (h/header request "Idempotency-Key")))
        run (runs/request-run! system req)]
    (responses/json-response 201
                             {:data (ser/run->response (runs/get-run system (:id run)))})))

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

(defn- next-run-stream-id [stream-id counter event]
  (str (or (:id event)
           (str stream-id "-" (swap! counter inc)))))

(defn- send-run-event! [ctx stream-id counter event]
  (let [event-id (next-run-stream-id stream-id counter event)]
    (streaming/send-sse-chunk! ctx
                               event-id
                               {:type "event"
                                :data (ser/event->response event)})))

(defn events-stream-response
  [system run-id request]
  (let [{:keys [after_id replay_limit]} (-> request :parameters :query)
        stream-id (str "run-events-" (System/currentTimeMillis))
        fallback-id (atom 0)
        broker-instance (or (:event-bus system) (:broker system))
        replay-limit (or replay_limit 100)
        replay-messages (broker/replay! broker-instance
                                        (broker/run-events-subject run-id)
                                        {:after-id after_id
                                         :limit replay-limit})]
    (streaming/managed-response
	     request
	     {:name :run-events-stream
          :metrics (:sse-metrics system)
	      :on-error (fn [ctx _error]
                  (streaming/send-sse-error! ctx "stream_error" "Stream failed"))}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                               broker-instance
                                               (broker/all-runs-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)]
         (when-let [run (runs/get-run system run-id)]
           (streaming/send-sse-chunk! ctx
                                      {:type "snapshot"
                                       :run (ser/run->response run)}))
         (doseq [message replay-messages]
           (when (relevant-run-event? (:payload message) run-id)
             (send-run-event! ctx stream-id fallback-id (:payload message))))
         (loop []
           (when-let [event (streaming/take! ctx ch)]
             (when (relevant-run-event? (:payload event) run-id)
               (send-run-event! ctx stream-id fallback-id (:payload event)))
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
                                             :recovery (run-recovery system run-id))})
      (not-found! "run_not_found" "Run not found"))))

(defn recover [system _request run-id]
  (if-let [_ (runs/get-run system run-id)]
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
