(ns agent.runs.service
  "Durable run control and runner lifecycle operations."
  (:require
   [agent.health :as health]
   [agent.runners.bubblewrap :as bubblewrap]
   [agent.runners.core :as runners]
   [agent.runners.docker-podman :as docker-podman]
   [agent.runners.local-unsandboxed :as local-unsandboxed]
   [agent.runners.options :as runner-options]
   [agent.runners.seatbelt :as seatbelt]
   [agent.runtime.core :as runtime]))

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

(defn- runner-exit-status [run exit-code]
  (cond
    (contains? #{"cancelled" "completed" "failed"} (:status run)) nil
    (zero? exit-code) :completed
    :else :failed))

(defn- create-exit-aware-local-unsandboxed-runner
  [runtime-service]
  (local-unsandboxed/create-local-unsandboxed-runner
   {:on-exit (fn [run-id {:keys [exit-code]}]
               (when-let [run (runtime/get-run runtime-service run-id)]
                 (when-let [status (runner-exit-status run exit-code)]
                   (runtime/transition-run! runtime-service
                                            run-id
                                            status
                                            {:last-error (when-not (zero? exit-code)
                                                            (str "Process exited with code " exit-code))
                                             :runner-metadata (assoc (:runner-metadata run)
                                                                     :exit-code exit-code)}))))
    :on-output (fn [run-id {:keys [stream line captured-at]}]
                 (runtime/log-run-output! runtime-service
                                          run-id
                                          {:stream stream
                                           :line line
                                           :captured-at captured-at}))}))

(defn create-runner-registry
  [runtime-service]
  {:local-unsandboxed (create-exit-aware-local-unsandboxed-runner runtime-service)
   :bubblewrap (bubblewrap/create-bubblewrap-runner
                {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)})
   :docker (docker-podman/create-docker-podman-runner
            {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)
             :engine-binary "docker"})
   :podman (docker-podman/create-docker-podman-runner
            {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)
             :engine-binary "podman"})
   :seatbelt (seatbelt/create-seatbelt-runner
              {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)})})

(defn- request-with-defaults
  [system request]
  (runtime/create-run-request
   (update request :substrate #(or % (runner-options/default-substrate system)))))

(defn request-run!
  [system request]
  (runtime/request-run! (:runtime-service system)
                        (request-with-defaults system request)))

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

(defn runner-status
  [system run-id]
  (when-let [run (get-run system run-id)]
    (when-let [runner (get (:runner-registry system) (keyword (:substrate run)))]
      (runners/status runner run-id))))

(defn prepare-runner-options
  [system run]
  (runner-options/prepare-runner-options system run))

(defn container-image-contract
  [system run-id]
  (when-let [run (get-run system run-id)]
    (when (#{"docker" "podman"} (:substrate run))
      (docker-podman/image-contract (prepare-runner-options system run)))))

(defn launch-run!
  [system run-id]
  (try
    (let [run (or (get-run system run-id)
                  (throw (ex-info "Run not found" {:type :run-not-found
                                                   :run-id run-id})))
          runner (or (get (:runner-registry system) (keyword (:substrate run)))
                     (throw (ex-info "No runner for substrate"
                                     {:type :runner-not-found
                                      :substrate (:substrate run)})))
          checkpoint-seq (or (get-in run [:checkpoint :sequence-no]) 0)
          run-spec (runners/create-run-spec
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
                     :runner-options (prepare-runner-options system run)})
          launch-result (:result (runtime/execute-activity!
                                  (:runtime-service system)
                                  {:run-id run-id
                                   :activity-name :runner.launch
                                   :input run-spec}
                                  #(runners/launch runner run-spec)))]
      (transition-run! system run-id :launched {:runner-metadata launch-result})
      (health/mark-ok! (:health-registry system) :runtime)
      (get-run system run-id))
    (catch Exception e
      (health/mark-error! (:health-registry system) :runtime e)
      (throw e))))

(defn signal-run!
  [system run-id command]
  (let [run (or (get-run system run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (ex-info "No runner for substrate"
                                   {:type :runner-not-found
                                    :substrate (:substrate run)})))
        command-type (cond
                       (keyword? command) command
                       (map? command) (keyword (:command-type command))
                       (string? command) (keyword command)
                       :else nil)
        signal-result (:result (runtime/execute-activity!
                                (:runtime-service system)
                                {:run-id run-id
                                 :activity-name (keyword (str "runner.signal." (name command-type)))
                                 :input command}
                                #(runners/signal runner run-id command)))]
    (when (contains? #{:cancel :terminate :kill} command-type)
      (transition-run! system run-id :cancelled {:runner-metadata (merge (:runner-metadata run)
                                                                         signal-result)}))
    signal-result))
