(ns agent.runs.registry
  "Durable distributed run registry and control-plane primitives."
  (:require
   [agent.broker.core :as broker]
   [agent.defaults :as defaults]
   [agent.persistence.sqlite :as sqlite]
   [agent.util :as util]
   [clojure.core.async :as async])
  (:import
   (java.time Instant)
   (java.time.temporal ChronoUnit)
   (java.util UUID)))

(def default-lease-duration-seconds 60)
(def default-stale-grace-seconds 30)
(def default-substrate :external)
(def terminal-statuses #{"completed" "failed" "cancelled" "expired"})
(def terminal-command-statuses #{"completed" "failed" "cancelled"})
(def run-transitions
  {"requested" #{"running" "completed" "failed" "cancelled" "expired"}
   "running" #{"completed" "failed" "cancelled" "expired"}
   "completed" #{}
   "failed" #{}
   "cancelled" #{}
   "expired" #{}})

(declare get-run)

(def ^:private now util/now-str)

(defn- plus-seconds [seconds]
  (str (.plus (Instant/now) (long seconds) ChronoUnit/SECONDS)))

(defn- emit-event! [runtime event]
  (when-let [sink (:event-sink runtime)]
    (sink event)))

(defn- emit-recorded-event! [runtime event]
  (when-let [sink (or (:recorded-event-sink runtime)
                     (:event-sink runtime))]
    (sink event)))

(defn- event-watermark [runtime]
  (sqlite/latest-event-id (:store runtime)))

(defn- publish-run-events-after! [runtime run-id after-id]
  (doseq [event (reverse (sqlite/list-events (:store runtime) {:after-id after-id
                                                               :entity-type :agent_run
                                                               :entity-id run-id
                                                               :limit 1000}))]
    (emit-recorded-event! runtime event)))

(defn create-runtime-service
  [{:keys [store event-sink recorded-event-sink broker]
    :or {event-sink (fn [_] nil)}}]
  {:store store
   :broker broker
   :recorded-event-sink recorded-event-sink
   :event-sink event-sink})

(defn create-run-request
  [{:keys [idempotency-key agent-id parent-run-id name substrate capabilities network-identity run-options requested-by]
    :or {capabilities []}}]
  {:idempotency-key idempotency-key
   :agent-id (or agent-id (str "agent-" (UUID/randomUUID)))
   :parent-run-id parent-run-id
   :name name
   :substrate (or substrate default-substrate)
   :capabilities (vec capabilities)
   :network-identity network-identity
   :run-options run-options
   :requested-by (or requested-by "system")})

(defn request-run!
  [runtime request]
  (if-let [existing (when-let [key (:idempotency-key request)]
                      (sqlite/get-agent-run-by-idempotency-key (:store runtime) key))]
    (let [lease (or (sqlite/latest-agent-run-lease (:store runtime) (:id existing))
                    (sqlite/create-agent-run-lease! (:store runtime)
                                                    {:id (:lease-id existing)
                                                     :run-id (:id existing)
                                                     :holder-id "control-plane"
                                                     :expires-at (plus-seconds default-lease-duration-seconds)}))]
      (assoc (get-run runtime (:id existing)) :lease lease))
    (let [before-event-id (event-watermark runtime)
          run-id (str "run-" (UUID/randomUUID))
          lease-id (str "lease-" (UUID/randomUUID))
        run (sqlite/create-agent-run! (:store runtime)
                                      {:id run-id
                                       :idempotency-key (:idempotency-key request)
                                       :agent-id (:agent-id request)
                                       :parent-run-id (:parent-run-id request)
                                       :lease-id lease-id
                                       :name (:name request)
                                       :substrate (:substrate request)
                                       :status :requested
                                       :capabilities (:capabilities request)
                                       :network-identity (:network-identity request)
                                       :run-options (:run-options request)
                                       :requested-by (:requested-by request)})
        created? (= run-id (:id run))
        persisted-lease-id (or (:lease-id run) lease-id)
        lease (or (sqlite/latest-agent-run-lease (:store runtime) (:id run))
                  (sqlite/create-agent-run-lease! (:store runtime)
                                                  {:id persisted-lease-id
                                                   :run-id (:id run)
                                                   :holder-id "control-plane"
                                                   :expires-at (plus-seconds default-lease-duration-seconds)}))]
	    (when created?
	      (publish-run-events-after! runtime (:id run) before-event-id))
    (assoc run :lease lease))))

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

(defn- normalize-status [status]
  (name status))

(defn- terminal-run? [run]
  (contains? terminal-statuses (:status run)))

(defn- assert-run-transition! [run status]
  (let [from (:status run)
        to (normalize-status status)]
    (cond
      (= from to)
      nil

      (terminal-run? run)
      (throw (ex-info "Illegal terminal run transition"
                      {:type :illegal-run-transition
                       :run-id (:id run)
                       :from from
                       :to to}))

      (contains? (get run-transitions from #{}) to)
      nil

      :else
      (throw (ex-info "Illegal run transition"
                      {:type :illegal-run-transition
                       :run-id (:id run)
                       :from from
                       :to to}))))
  true)

(defn- transition-updates [status opts]
  (cond-> {:status status}
    (contains? opts :last-error)
    (assoc :last-error (:last-error opts))
    (contains? opts :runner-metadata)
    (assoc :runner-metadata (:runner-metadata opts))))

(defn- apply-run-transition!
  [runtime run-id status updates]
  (let [status* (normalize-status status)
        current (or (sqlite/get-agent-run (:store runtime) run-id)
                    (throw (ex-info "Run not found" {:type :run-not-found
                                                     :run-id run-id})))]
    (assert-run-transition! current status*)
    (if (and (= status* (:status current)) (empty? updates))
      current
      (let [before-event-id (event-watermark runtime)
            run (sqlite/update-agent-run! (:store runtime)
                                          run-id
                                          (assoc updates :status status*))]
        (when-let [lease-id (:lease-id run)]
          (when (and (not (contains? terminal-statuses (:status current)))
                     (contains? terminal-statuses status*))
            (sqlite/release-agent-run-lease! (:store runtime) lease-id)))
        (publish-run-events-after! runtime run-id before-event-id)
        run))))

(defn register-run!
  [runtime run-id registration]
  (apply-run-transition! runtime
                         run-id
                         :running
                         (cond-> {:started-at (now)}
                           (contains? registration :capabilities)
                           (assoc :capabilities (:capabilities registration))
                           (contains? registration :network-identity)
                           (assoc :network-identity (:network-identity registration))
                           (contains? registration :runner-metadata)
                           (assoc :runner-metadata (:runner-metadata registration)))))

(defn heartbeat!
  [runtime run-id {:keys [sequence-no status metrics lease-id]
                   :or {status :running}}]
  (let [existing (when sequence-no
                   (sqlite/get-agent-run-heartbeat-by-sequence (:store runtime) run-id sequence-no))
        before-event-id (when-not existing (event-watermark runtime))
        heartbeat (or existing
                      (sqlite/record-agent-run-heartbeat! (:store runtime)
                                                          {:run-id run-id
                                                           :sequence-no sequence-no
                                                           :status status
                                                           :metrics metrics}))]
    (when lease-id
      (sqlite/renew-agent-run-lease! (:store runtime) lease-id
                                     (plus-seconds default-lease-duration-seconds)))
    (when-not existing
      (publish-run-events-after! runtime run-id before-event-id))
    heartbeat))

(defn checkpoint!
  [runtime run-id {:keys [sequence-no checkpoint-type state]
                   :or {checkpoint-type :state}}]
  (let [existing (when sequence-no
                   (sqlite/get-agent-run-checkpoint-by-sequence-type (:store runtime)
                                                                     run-id
                                                                     sequence-no
                                                                     checkpoint-type))
        before-event-id (when-not existing (event-watermark runtime))
        checkpoint (or existing
                       (sqlite/create-agent-run-checkpoint! (:store runtime)
                                                            {:run-id run-id
                                                             :sequence-no sequence-no
                                                             :checkpoint-type checkpoint-type
                                                             :state state}))]
    (when-not existing
      (publish-run-events-after! runtime run-id before-event-id))
    checkpoint))

(defn enqueue-command!
  [runtime run-id {:keys [command-type payload request-id]}]
  (let [request-id* (or request-id
                        (:request-id payload)
                        (str (UUID/randomUUID)))
        existing (first (sqlite/list-agent-run-commands (:store runtime)
                                                        run-id
                                                        {:request-id request-id*
                                                         :limit 1}))
        before-event-id (when-not existing (event-watermark runtime))
        command (or existing
                    (sqlite/enqueue-agent-run-command! (:store runtime)
                                                   {:run-id run-id
                                                    :command-type command-type
                                                    :payload payload
                                                    :request-id request-id*}))]
    (when-not existing
      (publish-run-events-after! runtime run-id before-event-id))
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

(defn- make-activity-key
  [{:keys [run-id command-id activity-name]}]
  (str run-id ":" (or command-id "run") ":" (name activity-name)))

(defn execute-activity!
  [runtime {:keys [activity-key run-id command-id activity-name input]} f]
  (let [activity-key* (or activity-key
                          (make-activity-key {:run-id run-id
                                              :command-id command-id
                                              :activity-name activity-name}))
        existing (sqlite/get-agent-run-activity (:store runtime) activity-key*)]
    (if (= "completed" (:status existing))
      {:activity existing
       :result (:result existing)
       :cached? true}
      (let [_activity (or existing
                          (sqlite/start-agent-run-activity! (:store runtime)
                                                            {:activity-key activity-key*
                                                             :run-id run-id
                                                             :command-id command-id
                                                             :activity-name activity-name
                                                             :input input}))]
        (try
          (let [result (f)
                completed (sqlite/complete-agent-run-activity! (:store runtime)
                                                               activity-key*
                                                               {:status :completed
                                                                :result result})]
            {:activity completed
             :result result
             :cached? false})
          (catch Exception ex
            (sqlite/complete-agent-run-activity! (:store runtime)
                                                 activity-key*
                                                 {:status :failed
                                                  :error (.getMessage ex)})
            (throw ex)))))))

(defn- command-for-run!
  [runtime run-id command-id]
  (let [command (sqlite/get-agent-run-command (:store runtime) command-id)]
    (when-not (and command (= run-id (:run-id command)))
      (throw (ex-info "Command not found"
                      {:type :command-not-found
                       :run-id run-id
                       :command-id command-id})))
    command))

(defn acknowledge-command!
  [runtime run-id command-id]
  (let [existing (command-for-run! runtime run-id command-id)]
    (when-not (or (= "acknowledged" (:status existing))
                  (contains? terminal-command-statuses (:status existing)))
      (let [before-event-id (event-watermark runtime)]
        (sqlite/update-agent-run-command! (:store runtime) command-id {:status :acknowledged})
        (publish-run-events-after! runtime run-id before-event-id))))
  command-id)

(defn complete-command!
  ([runtime run-id command-id status error]
   (complete-command! runtime run-id command-id status error nil))
  ([runtime run-id command-id status error response]
   (let [status* (name status)
         _ (when-not (contains? terminal-command-statuses status*)
             (throw (ex-info "Command completion status must be terminal"
                             {:type :invalid-command-transition
                              :run-id run-id
                              :command-id command-id
                              :status status
                              :allowed terminal-command-statuses})))
         existing (command-for-run! runtime run-id command-id)
         before-event-id (when-not (contains? terminal-command-statuses (:status existing))
                           (event-watermark runtime))
         command (if (contains? terminal-command-statuses (:status existing))
                   existing
                   (sqlite/update-agent-run-command! (:store runtime) command-id {:status status*
                                                                                  :error error
                                                                                  :response response}))]
     (when-not (contains? terminal-command-statuses (:status existing))
       (publish-run-events-after! runtime run-id before-event-id))
     command)))

(defn transition-run!
  [runtime run-id status & [opts]]
  (apply-run-transition! runtime
                         run-id
                         status
                         (transition-updates status (or opts {}))))

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
   (let [active? (contains? #{"requested" "running"} (:status run))]
     (if-not active?
       false
       (let [lease (or (:lease run)
                       (sqlite/latest-agent-run-lease (:store runtime) (:id run)))
             heartbeat (or (:heartbeat run)
                           (sqlite/latest-agent-run-heartbeat (:store runtime) (:id run)))
             now* (now-instant)
             lease-expired? (when-let [expires-at (some-> lease :expires-at parse-instant)]
                              (.isAfter now* expires-at))
             heartbeat-expired? (when-let [observed-at (some-> heartbeat :observed-at parse-instant)]
                                  (.isAfter now* (.plusSeconds observed-at (long grace-seconds))))]
         (boolean (or lease-expired? heartbeat-expired?)))))))

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
       :run-options (:run-options run)
       :recoverable? (contains? #{"requested" "running" "expired" "failed" "cancelled"} (:status run))})))

(defn- terminal-run-event? [message run-id]
  (let [event (:payload message)]
    (and (= run-id (:entity-id event))
         (contains? terminal-statuses (get-in event [:payload :status])))))

(defn- wait-for-run-event!
  [runtime run-id timeout-ms]
  (let [broker-instance (:broker runtime)
        subscription (broker/subscribe! broker-instance (broker/run-events-subject run-id)
                                        {:buffer-strategy :sliding
                                         :buffer-size defaults/event-stream-buffer-size
                                         :slow-client :drop-new})
        timeout-ch (async/timeout timeout-ms)]
    (try
      (if-let [run (get-run runtime run-id)]
        (if (terminal-run? run)
          run
          (loop []
            (let [[message port] (async/alts!! [(:channel subscription) timeout-ch])]
              (cond
                (= port timeout-ch) (get-run runtime run-id)
                (nil? message) (get-run runtime run-id)
                (terminal-run-event? message run-id) (get-run runtime run-id)
                :else (recur)))))
        nil)
      (finally
        (broker/unsubscribe! broker-instance subscription)))))

(defn wait-for-run!
  ([runtime run-id] (wait-for-run! runtime run-id {}))
  ([runtime run-id {:keys [timeout-ms]
                    :or {timeout-ms 15000}}]
   (if (:broker runtime)
     (wait-for-run-event! runtime run-id timeout-ms)
     (get-run runtime run-id))))

(defn retry-run!
  [runtime run-id]
  (let [run (or (get-run runtime run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        checkpoint (:checkpoint run)
        attempt (inc (long (or (get-in run [:run-options :recovery :attempt]) 0)))
        next-run (request-run! runtime
                               (create-run-request
                                {:agent-id (:agent-id run)
                                 :parent-run-id (or (:parent-run-id run) run-id)
                                 :name (:name run)
                                 :substrate (keyword (:substrate run))
                                 :capabilities (:capabilities run)
                                 :network-identity (:network-identity run)
                                 :requested-by "recovery"
                                 :run-options (assoc (:run-options run)
                                                     :recovery {:attempt attempt
                                                                :retry-on-stale? (true? (get-in run [:run-options :recovery :retry-on-stale?]))
                                                                :max-attempts (or (get-in run [:run-options :recovery :max-attempts]) 1)
                                                                :checkpoint-seq (or (:sequence-no checkpoint) 0)
                                                                :checkpoint-state (:state checkpoint)
                                                                :previous-run-id run-id})}))]
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
               retry-on-stale? (true? (get-in run [:run-options :recovery :retry-on-stale?]))
               max-attempts (long (or (get-in run [:run-options :recovery :max-attempts]) 1))
               attempt (long (or (get-in run [:run-options :recovery :attempt]) 0))
               replacement (when (and retry-on-stale? (< attempt max-attempts))
                             (retry-run! runtime (:id run)))]
           (conj acc {:reclaimed reclaimed
                      :replacement replacement}))))
     []
     runs)))

(defn runtime-health
  [runtime]
  (let [runs (sqlite/list-agent-runs (:store runtime) {:limit 1000})
        stale (count (filter #(stale-run? runtime %) runs))
        pending (sqlite/count-pending-agent-run-commands (:store runtime))]
    {:healthy true
     :run-count (count runs)
     :stale-run-count stale
     :pending-command-count pending}))
