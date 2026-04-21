(ns agent.telemetry
  "First-class cost/latency telemetry and μ/log emission."
  (:require
   [agent.llm.core :as llm-core]
   [agent.logging :as logging])
  (:import
   (java.time Instant)))

(def terminal-run-statuses #{"completed" "failed" "cancelled" "expired"})

(defn- now-ms []
  (System/currentTimeMillis))

(defn- duration-ms [start-ns]
  (/ (double (- (System/nanoTime) start-ns)) 1000000.0))

(defn- parse-instant [value]
  (when value
    (Instant/parse value)))

(defn- event-ms [event]
  (if-let [created-at (:created-at event)]
    (.toEpochMilli (parse-instant created-at))
    (now-ms)))

(defn- bounded-conj [xs value max-size]
  (let [xs* (conj (vec (or xs [])) value)]
    (if (> (count xs*) max-size)
      (subvec xs* (- (count xs*) max-size))
      xs*)))

(defn- percentile [values p]
  (let [xs (sort (remove nil? values))
        n (count xs)]
    (when (pos? n)
      (nth (vec xs)
           (-> (* p n)
               Math/ceil
               long
               dec
               (max 0)
               (min (dec n)))))))

(defn- latency-summary [values]
  {:count (count values)
   :p50-ms (percentile values 0.50)
   :p95-ms (percentile values 0.95)})

(defn create-collector
  ([] (create-collector {}))
  ([{:keys [enabled max-latency-samples]
     :or {enabled true
          max-latency-samples 1000}}]
   {:enabled (true? enabled)
    :max-latency-samples (long max-latency-samples)
    :state (atom {:runs {}
                  :run-latencies []
                  :agents {}
                  :tools {}
                  :federation {}
                  :llm {:calls 0
                        :errors 0
                        :latencies []}})}))

(defn enabled? [collector]
  (true? (:enabled collector)))

(defn- add-run-latency [state run-id latency-ms status max-samples]
  (-> state
      (assoc-in [:runs run-id :latency-ms] latency-ms)
      (assoc-in [:runs run-id :status] status)
      (update :run-latencies bounded-conj latency-ms max-samples)))

(defn record-system-event!
  [collector event]
  (when (enabled? collector)
    (let [event-type (:event-type event)
          entity-type (:entity-type event)
          run-id (:entity-id event)
          payload (:payload event)
          observed-ms (event-ms event)
          max-samples (:max-latency-samples collector)]
      (when (= "agent_run" entity-type)
        (cond
          (= "agent.run.requested" event-type)
          (swap! (:state collector)
                 assoc-in [:runs run-id]
                 {:run-id run-id
                  :agent-id (:agent-id payload)
                  :status "requested"
                  :requested-at-ms observed-ms})

          (= "agent.run.registered" event-type)
          (swap! (:state collector)
                 update-in [:runs run-id]
                 merge
                 {:agent-id (:agent-id payload)
                  :status "running"
                  :started-at-ms observed-ms}))
        (when-let [status (:status payload)]
          (when (contains? terminal-run-statuses status)
            (let [latency-ms (get-in (swap! (:state collector)
                                            (fn [state]
                                              (let [run (get-in state [:runs run-id])
                                                    start-ms (or (:requested-at-ms run)
                                                                 (:started-at-ms run)
                                                                 observed-ms)
                                                    latency-ms (max 0 (- observed-ms start-ms))]
                                                (add-run-latency state run-id latency-ms status max-samples))))
                                     [:runs run-id :latency-ms])]
              (logging/log! :agent.telemetry/run-latency
                            {:run/id run-id
                             :agent/id (get-in @(:state collector) [:runs run-id :agent-id])
                             :run/status status
                             :latency/ms latency-ms}))))))))

(defn- safe-estimate-cost [provider messages model]
  (try
    (llm-core/estimate-cost provider messages model)
    (catch Exception _ nil)))

(defn usage-estimate [provider messages completion opts]
  (let [model (:model opts)
        estimate (safe-estimate-cost provider messages model)
        prompt-tokens (or (:prompt-tokens estimate)
                          (:prompt_tokens estimate)
                          (llm-core/count-tokens-estimate messages))
        completion-tokens (llm-core/count-tokens-estimate [{:role "assistant"
                                                            :content (or completion "")}])
        cached-tokens (or (:cached-tokens estimate) 0)
        total-tokens (or (:tokens estimate)
                         (+ prompt-tokens completion-tokens))]
    {:model model
     :prompt-tokens prompt-tokens
     :completion-tokens completion-tokens
     :cached-tokens cached-tokens
     :tokens total-tokens
     :cost-usd (:cost-usd estimate)}))

(defn record-llm-call!
  [collector {:keys [agent-id model duration-ms success? error] :as attrs}]
  (when (enabled? collector)
    (let [agent-id* (or agent-id "system")
          tokens (long (or (:tokens attrs) 0))
          prompt-tokens (long (or (:prompt-tokens attrs) 0))
          completion-tokens (long (or (:completion-tokens attrs) 0))
          cached-tokens (long (or (:cached-tokens attrs) 0))
          cost-usd (when (number? (:cost-usd attrs)) (:cost-usd attrs))
          success?* (not (false? success?))]
      (swap! (:state collector)
             (fn [state]
               (-> state
                   (update-in [:llm :calls] (fnil inc 0))
                   (cond-> (not success?*) (update-in [:llm :errors] (fnil inc 0)))
                   (update-in [:llm :latencies] bounded-conj duration-ms (:max-latency-samples collector))
                   (update-in [:agents agent-id* :calls] (fnil inc 0))
                   (cond-> (not success?*) (update-in [:agents agent-id* :errors] (fnil inc 0)))
                   (update-in [:agents agent-id* :tokens] (fnil + 0) tokens)
                   (update-in [:agents agent-id* :prompt-tokens] (fnil + 0) prompt-tokens)
                   (update-in [:agents agent-id* :completion-tokens] (fnil + 0) completion-tokens)
                   (update-in [:agents agent-id* :cached-tokens] (fnil + 0) cached-tokens)
                   (cond-> cost-usd (update-in [:agents agent-id* :cost-usd] (fnil + 0.0) cost-usd)))))
      (logging/log! :agent.telemetry/llm-call
                    (cond-> {:agent/id agent-id*
                             :llm/model model
                             :latency/ms duration-ms
                             :tokens/total tokens
                             :tokens/prompt prompt-tokens
                             :tokens/completion completion-tokens
                             :tokens/cached cached-tokens
                             :success success?*}
                      cost-usd (assoc :cost/usd cost-usd)
                      error (assoc :error/message (.getMessage ^Throwable error)))))))

(defn complete-with-telemetry!
  [collector provider messages opts attrs]
  (let [start-ns (System/nanoTime)
        opts* (merge (select-keys attrs [:model]) opts)]
    (try
      (let [completion (llm-core/complete provider messages opts)
            usage (usage-estimate provider messages completion opts*)]
        (record-llm-call! collector
                          (merge attrs
                                 usage
                                 {:duration-ms (duration-ms start-ns)
                                  :success? true}))
        completion)
      (catch Exception e
        (record-llm-call! collector
                          (merge attrs
                                 (usage-estimate provider messages "" opts*)
                                 {:duration-ms (duration-ms start-ns)
                                  :success? false
                                  :error e}))
        (throw e)))))

(defn record-tool!
  [collector {:keys [tool-name duration-ms success? error user] :as attrs}]
  (when (enabled? collector)
    (let [tool-name* (name tool-name)
          success?* (not (false? success?))]
      (swap! (:state collector)
             (fn [state]
               (-> state
                   (update-in [:tools tool-name* :calls] (fnil inc 0))
                   (cond-> (not success?*) (update-in [:tools tool-name* :errors] (fnil inc 0)))
                   (update-in [:tools tool-name* :latencies] bounded-conj duration-ms (:max-latency-samples collector)))))
      (logging/log! :agent.telemetry/tool-execution
                    (cond-> {:tool/name tool-name*
                             :latency/ms duration-ms
                             :success success?*
                             :user user}
                      error (assoc :error/message (.getMessage ^Throwable error)
                                   :error/type (or (:type (ex-data error))
                                                   (.getName (class error)))))))))

(defn record-federation-send!
  [collector {:keys [peer-id duration-ms attempt success? status error]}]
  (when (enabled? collector)
    (let [peer-id* (or peer-id "unknown")
          success?* (not (false? success?))]
      (swap! (:state collector)
             (fn [state]
               (-> state
                   (update-in [:federation peer-id* :calls] (fnil inc 0))
                   (cond-> (not success?*) (update-in [:federation peer-id* :errors] (fnil inc 0)))
                   (update-in [:federation peer-id* :retries] (fnil + 0) (max 0 (dec (long (or attempt 1)))))
                   (update-in [:federation peer-id* :latencies] bounded-conj duration-ms (:max-latency-samples collector)))))
      (logging/log! :agent.telemetry/federation-send
                    (cond-> {:peer/id peer-id*
                             :latency/ms duration-ms
                             :attempt attempt
                             :success success?*
                             :http/status status}
                      error (assoc :error/message (.getMessage ^Throwable error)
                                   :error/type (or (:type (ex-data error))
                                                   (.getName (class error)))))))))

(defn- tool-summary [tool]
  (let [calls (long (or (:calls tool) 0))
        errors (long (or (:errors tool) 0))]
    (-> tool
        (dissoc :latencies)
        (assoc :calls calls
               :errors errors
               :error-rate (if (pos? calls) (/ (double errors) calls) 0.0)
               :latency-ms (latency-summary (:latencies tool))))))

(defn- federation-summary [peer]
  (let [calls (long (or (:calls peer) 0))
        errors (long (or (:errors peer) 0))]
    (-> peer
        (dissoc :latencies)
        (assoc :calls calls
               :errors errors
               :error-rate (if (pos? calls) (/ (double errors) calls) 0.0)
               :latency-ms (latency-summary (:latencies peer))))))

(defn snapshot [collector]
  (let [state (if (enabled? collector) @(:state collector) {})
        run-latencies (:run-latencies state)
        llm (:llm state)
        llm-calls (long (or (:calls llm) 0))
        llm-errors (long (or (:errors llm) 0))]
    {:enabled (enabled? collector)
     :runs {:count (count (:runs state))
            :terminal-count (count run-latencies)
            :latency-ms (latency-summary run-latencies)}
     :agents (:agents state)
     :tools (into {}
                  (map (fn [[tool-name tool]]
                         [tool-name (tool-summary tool)]))
                  (:tools state))
     :federation (into {}
                       (map (fn [[peer-id peer]]
                              [peer-id (federation-summary peer)]))
                       (:federation state))
     :llm {:calls llm-calls
           :errors llm-errors
           :error-rate (if (pos? llm-calls) (/ (double llm-errors) llm-calls) 0.0)
           :latency-ms (latency-summary (:latencies llm))}}))

(defn health-check [collector]
  {:healthy true
   :enabled (enabled? collector)
   :summary (select-keys (snapshot collector) [:runs :llm])})
