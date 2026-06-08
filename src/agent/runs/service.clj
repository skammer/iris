(ns agent.runs.service
  "System-facing durable run operations."
  (:require
   [agent.health :as health]
   [agent.runs.registry :as runtime]))

(defn create-runtime-service
  ([store event-sink]
   (create-runtime-service store event-sink nil))
  ([store event-sink broker-instance]
   (runtime/create-runtime-service {:store store
                                    :broker broker-instance
                                    :event-sink event-sink}))
  ([store event-sink broker-instance recorded-event-sink]
   (runtime/create-runtime-service {:store store
                                    :broker broker-instance
                                    :event-sink event-sink
                                    :recorded-event-sink recorded-event-sink})))

(defn request-run!
  [system request]
  (runtime/request-run! (:runtime-service system)
                        (runtime/create-run-request request)))

(defn list-runs
  ([system] (list-runs system {}))
  ([system opts]
   (runtime/list-runs (:runtime-service system) opts)))

(defn get-run
  [system run-id]
  (runtime/get-run (:runtime-service system) run-id))

(defn register-run!
  [system run-id registration]
  (runtime/register-run! (:runtime-service system) run-id registration))

(defn heartbeat-run!
  [system run-id heartbeat]
  (runtime/heartbeat! (:runtime-service system) run-id heartbeat))

(defn checkpoint-run!
  [system run-id checkpoint]
  (runtime/checkpoint! (:runtime-service system) run-id checkpoint))

(defn enqueue-run-command!
  [system run-id command]
  (runtime/enqueue-command! (:runtime-service system) run-id command))

(defn pending-run-commands
  [system run-id]
  (runtime/pending-commands (:runtime-service system) run-id))

(defn list-run-commands
  ([system run-id] (list-run-commands system run-id {}))
  ([system run-id opts]
   (runtime/list-commands (:runtime-service system) run-id opts)))

(defn list-run-heartbeats
  ([system run-id] (list-run-heartbeats system run-id {}))
  ([system run-id opts]
   (runtime/list-heartbeats (:runtime-service system) run-id opts)))

(defn list-run-checkpoints
  ([system run-id] (list-run-checkpoints system run-id {}))
  ([system run-id opts]
   (runtime/list-checkpoints (:runtime-service system) run-id opts)))

(defn recovery-plan
  [system run-id]
  (runtime/recovery-plan (:runtime-service system) run-id))

(defn wait-for-run!
  ([system run-id] (wait-for-run! system run-id {}))
  ([system run-id opts]
   (runtime/wait-for-run! (:runtime-service system) run-id opts)))

(defn reclaim-stale-runs!
  [system]
  (let [results (runtime/reclaim-stale-runs! (:runtime-service system))]
    (doseq [_ (filter :replacement results)]
      (health/bump-restart! (:health-registry system) :runtime))
    (health/mark-ok! (:health-registry system) :runtime)
    results))

(defn retry-run!
  [system run-id]
  (try
    (let [run (runtime/retry-run! (:runtime-service system) run-id)]
      (health/bump-restart! (:health-registry system) :runtime)
      (health/mark-ok! (:health-registry system) :runtime)
      run)
    (catch Exception e
      (health/mark-error! (:health-registry system) :runtime e)
      (throw e))))

(defn acknowledge-run-command!
  [system run-id command-id]
  (runtime/acknowledge-command! (:runtime-service system) run-id command-id))

(defn complete-run-command!
  ([system run-id command-id status error]
   (complete-run-command! system run-id command-id status error nil))
  ([system run-id command-id status error response]
   (runtime/complete-command! (:runtime-service system)
                              run-id
                              command-id
                              status
                              error
                              response)))

(defn transition-run!
  [system run-id status & [opts]]
  (runtime/transition-run! (:runtime-service system) run-id status opts))
