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

(defn- tool-receipt-base [directive context]
  (let [{:keys [tool-name input]} (:payload directive)]
    (cond-> {:directive (:type directive)
             :tool-name tool-name
             :input input}
      (:provider-tool-call-id context) (assoc :tool-call-id (:provider-tool-call-id context)))))

(defn- blocked-tool-receipt [directive]
  (assoc (tool-receipt-base directive (directive-tool-context directive))
         :status :approval-required))

(defn- supported-directive? [ops directive-type]
  (or (not (satisfies? ops/KernelCapabilities ops))
      (contains? (set (ops/supported-directives ops)) directive-type)))

(defn- unsupported-receipt [directive]
  {:directive (:type directive)
   :status :unsupported
   :reason (str "Directive " (name (:type directive)) " is not supported by this kernel host")})

(defn- batch-result->receipt [result]
  (let [base (cond-> {:directive :tool-call
                      :tool-name (:tool-name result)
                      :input (:input result)}
               (:tool-call-id result) (assoc :tool-call-id (:tool-call-id result)))]
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

(defn- exception->tool-receipt [directive context error]
  (let [base (tool-receipt-base directive context)
        error-type (:type (ex-data error))]
    (case error-type
      :chat-cancelled
      (throw error)

      :approval-required
      (assoc base
             :status :approval-required
             :reason (.getMessage error))

      (:tool-blocked :permission-denied :path-not-allowed)
      (assoc base
             :status :denied
             :reason (.getMessage error)
             :error-type error-type)

      (assoc base
             :status :error
             :reason (.getMessage error)
             :error-type error-type))))

(defn- executed-tool-receipts [executable batch]
  (let [results (mapv batch-result->receipt (:results batch))]
    (when-not (= (count executable) (count results))
      (throw (ex-info "Tool batch returned wrong result count"
                      {:type :kernel-batch-result-count-mismatch
                       :expected (count executable)
                       :actual (count results)})))
    (zipmap (map first executable) results)))

(defn- execute-tool-directive-batch! [ops parent-agent-id directives opts]
  (if-not (satisfies? ops/KernelToolBatchOps ops)
    (mapv #(execute-directive! ops parent-agent-id % opts) directives)
    (let [indexed (map-indexed vector directives)
          unsupported (keep (fn [[idx directive]]
                              (when-not (supported-directive? ops (:type directive))
                                [idx (unsupported-receipt directive)]))
                            indexed)
          executable (filterv (fn [[_ directive]]
                                (and (supported-directive? ops (:type directive))
                                     (executable-tool-directive? directive opts)))
                              indexed)
          blocked (keep (fn [[idx directive]]
                          (when (and (supported-directive? ops (:type directive))
                                     (not (executable-tool-directive? directive opts)))
                            [idx (blocked-tool-receipt directive)]))
                        indexed)
          executed (if (seq executable)
                     (let [calls (mapv (comp directive->batch-call second) executable)
                           batch (ops/execute-agent-tool-batch! ops parent-agent-id calls {} opts)]
                       (executed-tool-receipts executable batch))
                     {})
          receipts-by-index (merge (into {} unsupported)
                                   (into {} blocked)
                                   executed)]
      (mapv (fn [idx _directive]
              (get receipts-by-index idx))
            (range)
            directives))))

(defn execute-directive!
  ([ops parent-agent-id directive]
   (execute-directive! ops parent-agent-id directive {}))
  ([ops parent-agent-id directive {:keys [yolo? execute-safe-tools?]}]
   (let [directive (schema/validate-directive! directive)]
     (case (:type directive)
       :await
       {:directive (:type directive)
        :status :deferred}

       :tool-call
       (if-not (supported-directive? ops (:type directive))
         (unsupported-receipt directive)
         (let [{:keys [tool-name input context]} (:payload directive)
               context* (cond-> (or context {})
                          (:approval_id context) (assoc :approval-id (:approval_id context)))]
           (if (or yolo? execute-safe-tools? (:approval-id context*) (:approval_id context*))
             (try
               (let [result (ops/execute-agent-tool! ops parent-agent-id (keyword tool-name) input context*)]
                 (assoc (tool-receipt-base directive context*)
                        :status :ok
                        :result result))
               (catch Exception e
                 (exception->tool-receipt directive context* e)))
             (blocked-tool-receipt directive))))

       :complete
       (let [{:keys [result]} (:payload directive)]
         {:directive (:type directive)
          :status :completed
          :result result})

       (throw (ex-info "Unsupported directive"
                       {:type :validation-failed
                        :directive (:type directive)}))))))

(defn- event-receipt [receipt]
  (select-keys receipt [:directive :status :reason :tool-name :tool-call-id
                        :error-type]))

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
	                :receipts (mapv event-receipt receipts)}})
	    (assoc step :receipts receipts))))
