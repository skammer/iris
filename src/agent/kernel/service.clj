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
      #{:spawn-worker :tool-call :send-message :state-patch :complete}
      #{:tool-call}))

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
    ((:event-sink system) event))

  kernel-ops/KernelToolBatchOps
  (execute-agent-tool-batch! [_ agent-id calls context opts]
    (tool-service/execute-agent-tool-batch! system agent-id calls context opts)))

(defn kernel-ops
  [system]
  (->SystemKernelOps system))

(defn get-agent
  [system agent-id]
  (orchestrator/get-agent (:orchestrator system) agent-id))

(defn spawn-agent!
  [system spec]
  (orchestrator/spawn-agent! (:orchestrator system) spec))

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
    (spawn-agent! system {:name (:name spawn)
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
  (let [agent (or (get-agent system orchestrator-agent-id)
                  (throw (ex-info "Agent not found"
                                  {:type :agent-not-found
                                   :agent-id orchestrator-agent-id})))]
    (when-not (= "orchestrator" (:kind agent))
      (throw (ex-info "Agent is not an orchestrator"
                      {:type :validation-failed
                       :agent-id orchestrator-agent-id})))
    (let [step (kernel/orchestrator-spawn-worker-step
                {:task (:task worker-spec)
                 :worker-name (or (:name worker-spec) "Task Worker")
                 :worker-role (or (:role worker-spec) "worker")
                 :capability-bundle {:capabilities (or (:capabilities worker-spec) [])
                                     :tool-access (or (:tool-access worker-spec) [])}
                 :memory-scopes (or (:memory-scopes worker-spec) [])
                 :budgets (or (:budgets worker-spec) {})
                 :system-prompt (:system-prompt worker-spec)})]
      (execute-step! system orchestrator-agent-id step))))

(defn orchestrator-spawn-worker-direct!
  [system orchestrator-agent-id worker-spec]
  (let [agent (or (get-agent system orchestrator-agent-id)
                  (throw (ex-info "Agent not found"
                                  {:type :agent-not-found
                                   :agent-id orchestrator-agent-id})))]
    (when-not (= "orchestrator" (:kind agent))
      (throw (ex-info "Agent is not an orchestrator"
                      {:type :validation-failed
                       :agent-id orchestrator-agent-id})))
    (let [step (kernel/orchestrator-spawn-worker-step
                {:task (:task worker-spec)
                 :worker-name (or (:name worker-spec) "Task Worker")
                 :worker-role (or (:role worker-spec) "worker")
                 :capability-bundle {:capabilities (or (:capabilities worker-spec) [])
                                     :tool-access (or (:tool-access worker-spec) [])}
                 :memory-scopes (or (:memory-scopes worker-spec) [])
                 :budgets (or (:budgets worker-spec) {})
                 :system-prompt (:system-prompt worker-spec)})
          spawn (-> step :directives first :payload)
          worker (spawn-agent! system {:name (:name spawn)
                                       :kind "worker"
                                       :role (:role spawn)
                                       :parent-id orchestrator-agent-id
                                       :system-prompt (:system-prompt spawn)
                                       :capabilities (vec (or (:capabilities worker-spec) []))
                                       :tool-access (vec (or (:tool-access worker-spec) []))
                                       :memory-scopes (vec (or (:memory-scopes worker-spec) []))
                                       :budgets (or (:budgets worker-spec) {})
                                       :task (:task worker-spec)})
          receipt {:directive :spawn-worker
                   :status :ok
                   :worker-id (:id worker)}]
      ((:event-sink system)
       {:event-type :agent.kernel.step.executed
        :entity-type :agent
        :entity-id orchestrator-agent-id
        :payload {:directive-count 2
                  :receipt-count 1
                  :receipts [receipt]}})
      {:worker worker
       :receipts [receipt]})))

(defn list-agents
  [system]
  (orchestrator/list-agents (:orchestrator system)))

(defn list-agent-messages
  [system agent-id]
  (orchestrator/list-agent-messages (:orchestrator system) agent-id))

(defn send-agent-message!
  [system agent-id message]
  (orchestrator/send-agent-message! (:orchestrator system) (:llm-provider system) agent-id message))

(defn describe-agent-interop
  [system agent-ref]
  (orchestrator/describe-agent-interop (:orchestrator system) agent-ref))

(defn register-agent-capabilities!
  [system agent-ref spec]
  (orchestrator/register-agent-capabilities! (:orchestrator system) agent-ref spec))

(defn register-federated-peer!
  [system spec]
  (orchestrator/register-federated-peer! (:orchestrator system) spec))

(defn list-federated-peers
  [system]
  (orchestrator/list-federated-peers (:orchestrator system)))

(defn send-interop-message!
  [system from-agent-ref to-agent-ref message]
  (orchestrator/send-interop-message! (:orchestrator system) from-agent-ref to-agent-ref message))

(defn list-interop-messages
  ([system agent-ref]
   (orchestrator/list-interop-messages (:orchestrator system) agent-ref))
  ([system agent-ref opts]
   (orchestrator/list-interop-messages (:orchestrator system) agent-ref opts)))

(defn acknowledge-interop-message!
  [system agent-ref message-id opts]
  (orchestrator/acknowledge-interop-message! (:orchestrator system) agent-ref message-id opts))

(defn retry-interop-message!
  [system agent-ref message-id]
  (orchestrator/retry-interop-message! (:orchestrator system) agent-ref message-id))

(defn create-channel!
  [system spec]
  (orchestrator/create-channel! (:orchestrator system) spec))

(defn list-channels
  [system]
  (orchestrator/list-channels (:orchestrator system)))

(defn list-channel-messages
  [system channel-id]
  (orchestrator/list-channel-messages (:orchestrator system) channel-id))

(defn post-channel-message!
  [system channel-id message]
  (orchestrator/post-channel-message! (:orchestrator system) channel-id message))

(defn consume-agent-inbox!
  [system agent-id]
  (orchestrator/consume-agent-inbox! (:orchestrator system) (:llm-provider system) agent-id))
