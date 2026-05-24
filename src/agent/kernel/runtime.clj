(ns agent.kernel.runtime
  (:require
   [agent.kernel.ops :as ops]
   [agent.kernel.schema :as schema]))

(declare execute-directive!)

(defn- tool-directive? [directive]
  (= :tool-call (:type directive)))

(defn- directive-tool-context [directive]
  (let [context (or (get-in directive [:payload :context]) {})]
    (cond-> context
      (:approval_id context) (assoc :approval-id (:approval_id context)))))

(defn- executable-tool-directive? [directive {:keys [yolo? execute-safe-tools?]}]
  (let [context (directive-tool-context directive)]
    (or yolo?
        execute-safe-tools?
        (:approval-id context)
        (:approval_id context))))

(defn- directive->batch-call [directive]
  (let [{:keys [tool-name input]} (:payload directive)
        context (directive-tool-context directive)]
    {:tool-name (keyword tool-name)
     :input input
     :id (:provider-tool-call-id context)
     :context context}))

(defn- blocked-tool-receipt [directive]
  (let [{:keys [tool-name input]} (:payload directive)]
    {:directive (:type directive)
     :status :approval-required
     :tool-name tool-name
     :input input}))

(defn- batch-result->receipt [result]
  (let [base {:directive :tool-call
              :tool-name (:tool-name result)
              :tool-call-id (:tool-call-id result)
              :input (:input result)}]
    (if (= :ok (:status result))
      (assoc base
             :status :ok
             :result (:result result))
      (let [error-type (:error-type result)]
        (case error-type
          :approval-required
          (assoc base
                 :status :approval-required
                 :reason (:error result))

          (:tool-blocked :permission-denied :path-not-allowed)
          (assoc base
                 :status :denied
                 :reason (:error result)
                 :error-type error-type)

          (assoc base
                 :status :error
                 :reason (:error result)
                 :error-type error-type))))))

(defn- execute-tool-directive-batch! [ops parent-agent-id directives opts]
  (if-not (satisfies? ops/KernelToolBatchOps ops)
    (mapv #(execute-directive! ops parent-agent-id % opts) directives)
    (let [executable (filterv #(executable-tool-directive? % opts) directives)
          blocked (keep-indexed (fn [idx directive]
                                  (when-not (executable-tool-directive? directive opts)
                                    [idx (blocked-tool-receipt directive)]))
                                directives)
          executed (if (seq executable)
                     (let [calls (mapv directive->batch-call executable)
                           batch (ops/execute-agent-tool-batch! ops parent-agent-id calls {} opts)]
                       (mapv batch-result->receipt (:results batch)))
                     [])
          executable-receipts (atom executed)]
      (mapv (fn [idx directive]
              (if-let [[_ receipt] (some #(when (= idx (first %)) %) blocked)]
                receipt
                (let [receipt (first @executable-receipts)]
                  (swap! executable-receipts subvec 1)
                  receipt)))
            (range)
            directives))))

(defn execute-directive!
  ([ops parent-agent-id directive]
   (execute-directive! ops parent-agent-id directive {}))
  ([ops parent-agent-id directive {:keys [yolo? execute-safe-tools?]}]
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
      (if (or yolo? execute-safe-tools? (:approval-id context*) (:approval_id context*))
        (try
          (let [result (ops/execute-agent-tool! ops parent-agent-id (keyword tool-name) input context*)]
            {:directive (:type directive)
             :status :ok
             :tool-name tool-name
             :tool-call-id (:provider-tool-call-id context*)
             :input input
             :result result})
          (catch Exception e
            (let [error-type (:type (ex-data e))]
              (case error-type
                :approval-required
                {:directive (:type directive)
                 :status :approval-required
                 :tool-name tool-name
                 :tool-call-id (:provider-tool-call-id context*)
                 :input input
                 :reason (.getMessage e)}

                (:tool-blocked :permission-denied :path-not-allowed)
                {:directive (:type directive)
                 :status :denied
                 :tool-name tool-name
                 :tool-call-id (:provider-tool-call-id context*)
                 :input input
                 :reason (.getMessage e)
                 :error-type error-type}

                (throw e)))))
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
        receipts (loop [remaining (:directives step)
                        acc []]
                   (if (empty? remaining)
                     acc
                     (let [directive (first remaining)]
                       (if (tool-directive? directive)
                         (let [[tool-directives rest*] (split-with tool-directive? remaining)]
                           (recur rest*
                                  (into acc
                                        (execute-tool-directive-batch! ops parent-agent-id (vec tool-directives) opts))))
                         (recur (rest remaining)
                                (conj acc (execute-directive! ops parent-agent-id directive opts)))))))]
    (ops/emit-kernel-event!
     ops
     {:event-type :agent.kernel.step.executed
      :entity-type :agent
      :entity-id parent-agent-id
      :payload {:directive-count (count (:directives step))
                :receipt-count (count receipts)
                :receipts receipts}})
    (assoc step :receipts receipts))))
