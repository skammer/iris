(ns agent.runtime.core
  "Durable distributed run registry and control-plane primitives."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.core :as runners])
  (:import
   (java.time Instant)
   (java.time.temporal ChronoUnit)
   (java.util UUID)))

(def default-lease-duration-seconds 60)

(defn- now [] (str (Instant/now)))

(defn- plus-seconds [seconds]
  (str (.plus (Instant/now) (long seconds) ChronoUnit/SECONDS)))

(defn- emit-event! [runtime event]
  (when-let [sink (:event-sink runtime)]
    (sink event)))

(defn create-runtime-service
  [{:keys [store event-sink]
    :or {event-sink (fn [_] nil)}}]
  {:store store
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
                         :network-identity (:network-identity request)})
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
  [runtime run-id {:keys [command-type payload]}]
  (let [command (sqlite/enqueue-agent-run-command! (:store runtime)
                                                   {:run-id run-id
                                                    :command-type command-type
                                                    :payload payload})]
    (emit-event! runtime
                 {:event-type :agent.run.command.enqueued
                  :entity-type :agent_run
                  :entity-id run-id
                  :payload {:command-id (:id command)
                            :command-type (:command-type command)}})
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
  [runtime run-id command-id status error]
  (sqlite/update-agent-run-command! (:store runtime) command-id {:status status
                                                                 :error error})
  (emit-event! runtime
               {:event-type :agent.run.command.completed
                :entity-type :agent_run
                :entity-id run-id
                :payload {:command-id command-id
                          :status (name status)
                          :error error}})
  command-id)

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

(defn runtime-health
  [runtime]
  (let [runs (sqlite/list-agent-runs (:store runtime) {:limit 1000})
        pending (reduce
                 (fn [acc run]
                   (+ acc (count (sqlite/list-agent-run-commands (:store runtime) (:id run) {:status "pending"}))))
                 0
                 runs)]
    {:healthy true
     :run-count (count runs)
     :pending-command-count pending}))
