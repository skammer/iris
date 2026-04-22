(ns agent.kernel.runtime
  (:require
   [agent.kernel.ops :as ops]
   [agent.kernel.schema :as schema]))

(defn execute-directive!
  ([ops parent-agent-id directive]
   (execute-directive! ops parent-agent-id directive {}))
  ([ops parent-agent-id directive {:keys [yolo?]}]
  (let [directive (schema/validate-directive! directive)]
    (case (:type directive)
    :spawn-worker
    (let [{:keys [task name role capability-bundle memory-scopes budgets system-prompt]} (:payload directive)
          worker (ops/spawn-task-worker! ops {:task task
                                              :name name
                                              :role role
                                              :capability-bundle capability-bundle
                                              :memory-scopes memory-scopes
                                              :budgets budgets
                                              :system-prompt system-prompt
                                              :parent-id parent-agent-id})]
      {:directive (:type directive)
       :status :ok
       :worker-id (:id worker)})

    :await
    {:directive (:type directive)
     :status :deferred}

    :tool-call
    (let [{:keys [tool-name input context]} (:payload directive)
          context* (cond-> (or context {})
                     (:approval_id context) (assoc :approval-id (:approval_id context)))]
      (if (or yolo? (:approval-id context*) (:approval_id context*))
        (let [result (ops/execute-agent-tool! ops parent-agent-id (keyword tool-name) input context*)]
          {:directive (:type directive)
           :status :ok
           :tool-name tool-name
           :result result})
        {:directive (:type directive)
         :status :approval-required
         :tool-name tool-name
         :input input}))

    :send-message
    (let [{:keys [agent-id message]} (:payload directive)
          result (ops/send-agent-message! ops (or agent-id parent-agent-id) message)]
      {:directive (:type directive)
       :status :ok
       :agent-id (or agent-id parent-agent-id)
       :response (:response result)})

    :state-patch
    (let [{:keys [patch]} (:payload directive)
          state (ops/patch-agent-state! ops parent-agent-id patch)]
      {:directive (:type directive)
       :status :ok
       :state state})

    :complete
    (let [{:keys [result]} (:payload directive)]
      (ops/set-agent-status! ops parent-agent-id "completed")
      {:directive (:type directive)
       :status :completed
       :result result})

    (throw (ex-info "Unsupported directive"
                    {:type :validation-failed
                     :directive (:type directive)}))))))

(defn execute-step!
  ([ops parent-agent-id step]
   (execute-step! ops parent-agent-id step {}))
  ([ops parent-agent-id step opts]
  (let [step (schema/validate-step! step)
        receipts (mapv #(execute-directive! ops parent-agent-id % opts)
                       (:directives step))]
    (ops/emit-kernel-event!
     ops
     {:event-type :agent.kernel.step.executed
      :entity-type :agent
      :entity-id parent-agent-id
      :payload {:directive-count (count (:directives step))
                :receipt-count (count receipts)
                :receipts receipts}})
    (assoc step :receipts receipts))))
