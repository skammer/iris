(ns agent.runtime.core
  "Durable distributed run registry and control-plane primitives."
  (:require
   [agent.broker.core :as broker]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.core :as runners])
  (:import
   (java.time Instant)
   (java.time.temporal ChronoUnit)
   (java.util UUID)))

(def default-lease-duration-seconds 60)
(def default-stale-grace-seconds 30)

(defn- now [] (str (Instant/now)))

(defn- plus-seconds [seconds]
  (str (.plus (Instant/now) (long seconds) ChronoUnit/SECONDS)))

(defn- emit-event! [runtime event]
  (when-let [sink (:event-sink runtime)]
    (sink event)))

(defn create-runtime-service
  [{:keys [store event-sink broker]
    :or {event-sink (fn [_] nil)}}]
  {:store store
   :broker broker
   :event-sink event-sink})

(defn create-run-request
  [{:keys [agent-id parent-run-id name substrate capabilities network-identity runner-options requested-by]
    :or {substrate :local-process
         capabilities []}}]
  {:agent-id (or agent-id (str "agent-" (UUID/randomUUID)))
   :parent-run-id parent-run-id
   :name name
   :substrate substrate
   :capabilities (vec capabilities)
   :network-identity network-identity
   :runner-options runner-options
   :requested-by (or requested-by "system")})

(defn request-run!
  [runtime request]
  (let [run-id (str "run-" (UUID/randomUUID))
        lease-id (str "lease-" (UUID/randomUUID))
        bootstrap-token (runners/random-token)
        bootstrap-spec (runners/create-bootstrap-spec
                        {:run-id run-id
                         :agent-id (:agent-id request)
                         :parent-run-id (:parent-run-id request)
                         :lease-id lease-id
                         :capabilities (:capabilities request)
                         :network-identity (:network-identity request)
                         :checkpoint-seq (or (get-in request [:recovery :checkpoint-seq]) 0)})
        run (sqlite/create-agent-run! (:store runtime)
                                      {:id run-id
                                       :agent-id (:agent-id request)
                                       :parent-run-id (:parent-run-id request)
                                       :lease-id lease-id
                                       :name (:name request)
                                       :substrate (:substrate request)
                                       :status :requested
                                       :capabilities (:capabilities request)
                                       :network-identity (:network-identity request)
                                       :runner-options (:runner-options request)
                                       :bootstrap-token bootstrap-token
                                       :bootstrap-spec bootstrap-spec
                                       :requested-by (:requested-by request)})
        lease (sqlite/create-agent-run-lease! (:store runtime)
                                              {:id lease-id
                                               :run-id (:id run)
                                               :holder-id "control-plane"
                                               :expires-at (plus-seconds default-lease-duration-seconds)})]
    (emit-event! runtime
                 {:event-type :agent.run.requested
                  :entity-type :agent_run
                  :entity-id (:id run)
                  :payload {:agent-id (:agent-id run)
                            :substrate (:substrate run)
                            :lease-id (:id lease)}})
    (assoc run :lease lease)))

(defn list-runs
  ([runtime] (list-runs runtime {}))
  ([runtime opts]
   (sqlite/list-agent-runs (:store runtime) opts)))

(defn get-run
  [runtime run-id]
  (when-let [run (sqlite/get-agent-run (:store runtime) run-id)]
    (assoc run
           :lease (sqlite/latest-agent-run-lease (:store runtime) run-id)
           :heartbeat (sqlite/latest-agent-run-heartbeat (:store runtime) run-id)
           :checkpoint (sqlite/latest-agent-run-checkpoint (:store runtime) run-id)
           :pending-commands (sqlite/list-agent-run-commands (:store runtime) run-id {:status "pending"}))))

(defn register-run!
  [runtime run-id {:keys [capabilities network-identity runner-metadata]}]
  (let [run (sqlite/update-agent-run! (:store runtime) run-id
                                      {:status :running
                                       :capabilities capabilities
                                       :network-identity network-identity
                                       :runner-metadata runner-metadata
                                       :started-at (now)})]
    (emit-event! runtime
                 {:event-type :agent.run.registered
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:agent-id (:agent-id run)
                            :network-identity network-identity}})
    run))

(defn heartbeat!
  [runtime run-id {:keys [sequence-no status metrics lease-id]
                   :or {status :running}}]
  (let [heartbeat (sqlite/record-agent-run-heartbeat! (:store runtime)
                                                      {:run-id run-id
                                                       :sequence-no sequence-no
                                                       :status status
                                                       :metrics metrics})]
    (when lease-id
      (sqlite/renew-agent-run-lease! (:store runtime) lease-id
                                     (plus-seconds default-lease-duration-seconds)))
    (emit-event! runtime
                 {:event-type :agent.run.heartbeat
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:sequence-no sequence-no
                            :status (name status)}})
    heartbeat))

(defn checkpoint!
  [runtime run-id {:keys [sequence-no checkpoint-type state]
                   :or {checkpoint-type :state}}]
  (let [checkpoint (sqlite/create-agent-run-checkpoint! (:store runtime)
                                                        {:run-id run-id
                                                         :sequence-no sequence-no
                                                         :checkpoint-type checkpoint-type
                                                         :state state})]
    (emit-event! runtime
                 {:event-type :agent.run.checkpointed
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:sequence-no sequence-no
                            :checkpoint-type (name checkpoint-type)}})
    checkpoint))

(defn enqueue-command!
  [runtime run-id {:keys [command-type payload request-id]}]
  (let [command (sqlite/enqueue-agent-run-command! (:store runtime)
                                                   {:run-id run-id
                                                    :command-type command-type
                                                    :payload payload
                                                    :request-id (or request-id
                                                                    (:request-id payload)
                                                                    (str (UUID/randomUUID)))})]
    (emit-event! runtime
                 {:event-type :agent.run.command.enqueued
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:command-id (:id command)
                            :command-type (:command-type command)
                            :request-id (:request-id command)}})
    command))

(defn pending-commands
  [runtime run-id]
  (sqlite/list-agent-run-commands (:store runtime) run-id {:status "pending"}))

(defn list-commands
  ([runtime run-id] (list-commands runtime run-id {}))
  ([runtime run-id opts]
   (sqlite/list-agent-run-commands (:store runtime) run-id opts)))

(defn list-heartbeats
  ([runtime run-id] (list-heartbeats runtime run-id {}))
  ([runtime run-id opts]
   (sqlite/list-agent-run-heartbeats (:store runtime) run-id opts)))

(defn list-checkpoints
  ([runtime run-id] (list-checkpoints runtime run-id {}))
  ([runtime run-id opts]
   (sqlite/list-agent-run-checkpoints (:store runtime) run-id opts)))

(defn acknowledge-command!
  [runtime run-id command-id]
  (sqlite/update-agent-run-command! (:store runtime) command-id {:status :acknowledged})
  (emit-event! runtime
               {:event-type :agent.run.command.acknowledged
                :entity-type :agent_run
                :entity-id run-id
                :payload {:command-id command-id}})
  command-id)

(defn complete-command!
  ([runtime run-id command-id status error]
   (complete-command! runtime run-id command-id status error nil))
  ([runtime run-id command-id status error response]
   (let [command (sqlite/update-agent-run-command! (:store runtime) command-id {:status status
                                                                                :error error
                                                                                :response response})]
     (emit-event! runtime
                  {:event-type :agent.run.command.completed
                   :entity-type :agent_run
                   :entity-id run-id
                   :payload {:command-id command-id
                             :request-id (:request-id command)
                             :status (name status)
                             :error error
                             :response response}})
     command)))

(defn transition-run!
  [runtime run-id status & [{:keys [last-error runner-metadata]}]]
  (let [run (sqlite/update-agent-run! (:store runtime) run-id
                                      {:status status
                                       :last-error last-error
                                       :runner-metadata runner-metadata})]
    (when-let [lease-id (:lease-id run)]
      (when (contains? #{:completed :failed :cancelled :expired} status)
        (sqlite/release-agent-run-lease! (:store runtime) lease-id)))
    (emit-event! runtime
                 {:event-type (keyword (str "agent.run." (name status)))
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:status (name status)
                            :last-error last-error}})
    run))

(defn log-run-output!
  [runtime run-id {:keys [stream line captured-at]}]
  (emit-event! runtime
               {:event-type :agent.run.output
                :entity-type :agent_run
                :entity-id run-id
                :payload {:stream (name stream)
                          :line line
                          :captured-at captured-at}}))

(defn- parse-instant [value]
  (when value
    (Instant/parse value)))

(defn- now-instant []
  (Instant/now))

(defn stale-run?
  ([runtime run] (stale-run? runtime run {}))
  ([runtime run {:keys [grace-seconds]
                 :or {grace-seconds default-stale-grace-seconds}}]
   (let [lease (or (:lease run)
                   (sqlite/latest-agent-run-lease (:store runtime) (:id run)))
         heartbeat (or (:heartbeat run)
                       (sqlite/latest-agent-run-heartbeat (:store runtime) (:id run)))
         now* (now-instant)
         lease-expired? (when-let [expires-at (some-> lease :expires-at parse-instant)]
                          (.isAfter now* expires-at))
         heartbeat-expired? (when-let [observed-at (some-> heartbeat :observed-at parse-instant)]
                              (.isAfter now* (.plusSeconds observed-at (long grace-seconds))))
         active? (contains? #{"requested" "launched" "running"} (:status run))]
     (boolean (and active?
                   (or lease-expired? heartbeat-expired?))))))

(defn recovery-plan
  [runtime run-id]
  (when-let [run (get-run runtime run-id)]
    (let [checkpoint (:checkpoint run)
          heartbeat (:heartbeat run)
          pending (pending-commands runtime run-id)]
      {:run-id run-id
       :status (:status run)
       :stale? (stale-run? runtime run)
       :checkpoint-seq (or (:sequence-no checkpoint) 0)
       :checkpoint-type (:checkpoint-type checkpoint)
       :checkpoint-state (:state checkpoint)
       :last-heartbeat-at (:observed-at heartbeat)
       :last-heartbeat-status (:status heartbeat)
       :pending-command-count (count pending)
       :pending-command-ids (mapv :id pending)
       :runner-options (:runner-options run)
       :recoverable? (contains? #{"requested" "launched" "running" "expired" "failed" "cancelled"} (:status run))})))

(defn wait-for-run!
  ([runtime run-id] (wait-for-run! runtime run-id {}))
  ([runtime run-id {:keys [timeout-ms interval-ms]
                    :or {timeout-ms 15000 interval-ms 250}}]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [run (get-run runtime run-id)]
         (cond
           (nil? run) nil
           (contains? #{"completed" "failed" "cancelled" "expired"} (:status run)) run
           (>= (System/currentTimeMillis) deadline) run
           :else (do
                   (Thread/sleep (long interval-ms))
                   (recur))))))))

(defn retry-run!
  [runtime run-id]
  (let [run (or (get-run runtime run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        checkpoint (:checkpoint run)
        attempt (inc (long (or (get-in run [:runner-options :recovery :attempt]) 0)))
        next-run (request-run! runtime
                               (create-run-request
                                {:agent-id (:agent-id run)
                                 :parent-run-id (or (:parent-run-id run) run-id)
                                 :name (:name run)
                                 :substrate (keyword (:substrate run))
                                 :capabilities (:capabilities run)
                                 :network-identity (:network-identity run)
                                 :requested-by "recovery"
                                 :runner-options (assoc (:runner-options run)
                                                        :recovery {:attempt attempt
                                                                   :retry-on-stale? (true? (get-in run [:runner-options :recovery :retry-on-stale?]))
                                                                   :max-attempts (or (get-in run [:runner-options :recovery :max-attempts]) 1)})
                                 :recovery {:checkpoint-seq (or (:sequence-no checkpoint) 0)
                                            :checkpoint (:state checkpoint)
                                            :previous-run-id run-id}}))]
    (emit-event! runtime
                 {:event-type :agent.run.retry.requested
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:replacement-run-id (:id next-run)
                            :attempt attempt}})
    next-run))

(defn reclaim-run!
  [runtime run-id]
  (let [run (or (get-run runtime run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        reclaimed (transition-run! runtime run-id :expired {:last-error "stale_worker_reclaimed"})]
    (emit-event! runtime
                 {:event-type :agent.run.reclaimed
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:previous-status (:status run)}})
    reclaimed))

(defn reclaim-stale-runs!
  [runtime]
  (let [runs (list-runs runtime {:limit 1000})]
    (reduce
     (fn [acc run]
       (if-not (stale-run? runtime run)
         acc
         (let [reclaimed (reclaim-run! runtime (:id run))
               retry-on-stale? (true? (get-in run [:runner-options :recovery :retry-on-stale?]))
               max-attempts (long (or (get-in run [:runner-options :recovery :max-attempts]) 1))
               attempt (long (or (get-in run [:runner-options :recovery :attempt]) 0))
               replacement (when (and retry-on-stale? (< attempt max-attempts))
                             (retry-run! runtime (:id run)))]
           (conj acc {:reclaimed reclaimed
                      :replacement replacement}))))
     []
     runs)))

(defn request-command!
  ([runtime run-id command]
   (request-command! runtime run-id command {}))
  ([runtime run-id command {:keys [timeout-ms]
                            :or {timeout-ms 10000}}]
   (let [request-id (str (UUID/randomUUID))
         broker-instance (:broker runtime)
         response (when broker-instance
                    (future
                      (broker/request! broker-instance
                                       (broker/run-commands-subject run-id)
                                       {:run-id run-id
                                        :request-id request-id
                                        :command-type (:command-type command)
                                        :payload (:payload command)}
                                       {:timeout-ms timeout-ms
                                        :wait? false})))
         command* (enqueue-command! runtime run-id (assoc command :request-id request-id))
         waited (wait-for-run! runtime run-id {:timeout-ms timeout-ms
                                               :interval-ms 250})
         command-state (first (sqlite/list-agent-run-commands (:store runtime) run-id {:request-id request-id
                                                                                       :limit 1}))]
     {:request-id request-id
      :command command*
      :run waited
      :response-subject (broker/reply-subject request-id)
      :completed-command command-state
      :broker-request (some-> response deref)})))

(defn runtime-health
  [runtime]
  (let [runs (sqlite/list-agent-runs (:store runtime) {:limit 1000})
        stale (count (filter #(stale-run? runtime %) runs))
        pending (reduce
                 (fn [acc run]
                   (+ acc (count (sqlite/list-agent-run-commands (:store runtime) (:id run) {:status "pending"}))))
                 0
                 runs)]
    {:healthy true
     :run-count (count runs)
     :stale-run-count stale
     :pending-command-count pending}))
