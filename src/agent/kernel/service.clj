(ns agent.kernel.service
  "Kernel operations backed by a system map."
  (:require
   [agent.kernel :as kernel]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.orchestrator :as orchestrator]
   [agent.tools.service :as tool-service]))

(declare spawn-task-worker! send-agent-message! execute-agent-tool!)

(defrecord SystemKernelOps [system]
  kernel-ops/KernelCapabilities
  (supported-directives [_]
    (if (orchestrator/enabled? (:orchestrator system))
      #{:spawn-worker :tool-call :send-message :state-patch :complete :await}
      #{:tool-call :await}))

  kernel-ops/KernelOps
  (spawn-task-worker! [_ spec]
    (spawn-task-worker! system spec))
  (execute-agent-tool! [_ agent-id tool-name input context]
    (execute-agent-tool! system agent-id tool-name input context))
  (send-agent-message! [_ agent-id message]
    (send-agent-message! system agent-id message))
  (patch-agent-state! [_ agent-id patch]
    (orchestrator/patch-agent-state! (:orchestrator system) agent-id patch))
  (set-agent-status! [_ agent-id status]
    (orchestrator/set-agent-status! (:orchestrator system) agent-id status))
  (emit-kernel-event! [_ event]
    (when-let [sink (:event-sink system)]
      (sink event)))

  kernel-ops/KernelToolBatchOps
  (execute-agent-tool-batch! [_ agent-id calls context opts]
    (tool-service/execute-agent-tool-batch! system agent-id calls context opts)))

(defn kernel-ops
  [system]
  (->SystemKernelOps system))

(defn- get-agent!
  [system agent-id]
  (or (orchestrator/get-agent (:orchestrator system) agent-id)
      (throw (ex-info "Agent not found"
                      {:type :agent-not-found
                       :agent-id agent-id}))))

(defn- ensure-orchestrator!
  [system agent-id]
  (let [agent (get-agent! system agent-id)]
    (when-not (= "orchestrator" (:kind agent))
      (throw (ex-info "Agent is not an orchestrator"
                      {:type :validation-failed
                       :agent-id agent-id})))
    agent))

(defn execute-agent-tool!
  ([system agent-id tool-name input]
   (execute-agent-tool! system agent-id tool-name input {}))
  ([system agent-id tool-name input context]
   (tool-service/execute-agent-tool! system agent-id tool-name input context)))

(defn execute-directive!
  ([system parent-agent-id directive]
   (execute-directive! system parent-agent-id directive {}))
  ([system parent-agent-id directive opts]
   (kernel-runtime/execute-directive!
    (kernel-ops system)
    parent-agent-id
    directive
    (merge {:yolo? (true? (get-in system [:config :tools :yolo?]))
            :max-parallelism (get-in system [:config :tools :max-parallelism])}
           opts))))

(defn execute-step!
  ([system parent-agent-id step]
   (execute-step! system parent-agent-id step {}))
  ([system parent-agent-id step opts]
   (kernel-runtime/execute-step!
    (kernel-ops system)
    parent-agent-id
    step
    (merge {:yolo? (true? (get-in system [:config :tools :yolo?]))
            :max-parallelism (get-in system [:config :tools :max-parallelism])}
           opts))))

(defn spawn-task-worker!
  [system {:keys [task name role capability-bundle memory-scopes budgets system-prompt parent-id]
           :or {name "Task Worker"
                role "worker"
                capability-bundle {}
                memory-scopes []
                budgets {}}}]
  (let [step (kernel/orchestrator-spawn-worker-step {:task task
                                                     :worker-name name
                                                     :worker-role role
                                                     :capability-bundle capability-bundle
                                                     :memory-scopes memory-scopes
                                                     :budgets budgets
                                                     :system-prompt system-prompt})
        spawn (-> step :directives first :payload)]
    (orchestrator/spawn-agent! (:orchestrator system)
                               {:name (:name spawn)
                                :kind "worker"
                                :role (:role spawn)
                                :parent-id parent-id
                                :system-prompt (:system-prompt spawn)
                                :capabilities (vec (or (:capabilities capability-bundle) []))
                                :tool-access (vec (or (:tool-access capability-bundle) []))
                                :memory-scopes (vec memory-scopes)
                                :budgets budgets
                                :task task})))

(defn orchestrator-spawn-worker!
  [system orchestrator-agent-id worker-spec]
  (ensure-orchestrator! system orchestrator-agent-id)
  (let [step (kernel/orchestrator-spawn-worker-step
              {:task (:task worker-spec)
               :worker-name (or (:name worker-spec) "Task Worker")
               :worker-role (or (:role worker-spec) "worker")
               :capability-bundle {:capabilities (or (:capabilities worker-spec) [])
                                   :tool-access (or (:tool-access worker-spec) [])}
               :memory-scopes (or (:memory-scopes worker-spec) [])
               :budgets (or (:budgets worker-spec) {})
               :system-prompt (:system-prompt worker-spec)})
        executed (execute-step! system orchestrator-agent-id step)
        worker-id (some #(when (= :spawn-worker (:directive %)) (:worker-id %))
                        (:receipts executed))]
    {:worker (get-agent! system worker-id)
     :receipts (:receipts executed)}))

(defn send-agent-message!
  [system agent-id message]
  (orchestrator/send-agent-message! (:orchestrator system) (:llm-provider system) agent-id message))
