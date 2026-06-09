(ns agent.telemetry
  "First-class cost/latency telemetry collector and μ/log emission."
  (:require
   [agent.logging :as logging])
  (:import
   (java.time Instant)))

(def terminal-run-statuses #{"completed" "failed" "cancelled" "expired"})

(defn- now-ms []
  (System/currentTimeMillis))

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
                  :mcp {:calls 0
                        :errors 0
                        :latencies []}
                  :planner {:calls 0
                            :errors 0
                            :latencies []}
                  :directives {}
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

(defn- record-component-call!
  "One swap! for the per-component call accounting every record-* fn shares:
   bump :calls under `path`, bump :errors when the call failed, and append a
   bounded latency sample. `extra` (optional state transform) is applied
   inside the same swap!."
  ([collector path duration-ms success?]
   (record-component-call! collector path duration-ms success? nil))
  ([collector path duration-ms success? extra]
   (swap! (:state collector)
          (fn [state]
            (cond-> (-> state
                        (update-in (conj path :calls) (fnil inc 0))
                        (cond-> (not success?)
                          (update-in (conj path :errors) (fnil inc 0)))
                        (update-in (conj path :latencies)
                                   bounded-conj duration-ms (:max-latency-samples collector)))
              extra extra)))))

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
            (let [state* (swap! (:state collector)
                                (fn [state]
                                  (let [run (get-in state [:runs run-id])
                                        start-ms (or (:requested-at-ms run)
                                                     (:started-at-ms run)
                                                     observed-ms)
                                        latency-ms (max 0 (- observed-ms start-ms))]
                                    (add-run-latency state run-id latency-ms status max-samples))))
                  run (get-in state* [:runs run-id])]
              (logging/log! :agent.telemetry/run-latency
                            {:run/id run-id
                             :agent/id (:agent-id run)
                             :run/status status
                             :latency/ms (:latency-ms run)})))))
      (when (= "agent.kernel.step.executed" event-type)
        (let [receipts (get-in event [:payload :receipts])]
          (swap! (:state collector)
                 (fn [state]
                   (reduce (fn [acc receipt]
                             (update-in acc
                                        [:directives (name (:directive receipt)) :count]
                                        (fnil inc 0)))
                           state
                           receipts))))))))

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
      (record-component-call!
       collector [:llm] duration-ms success?*
       (fn [state]
         (-> state
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

(defn record-tool!
  [collector {:keys [tool-name duration-ms success? error user]}]
  (when (enabled? collector)
    (let [tool-name* (name tool-name)
          success?* (not (false? success?))]
      (record-component-call! collector [:tools tool-name*] duration-ms success?*)
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
      (record-component-call!
       collector [:federation peer-id*] duration-ms success?*
       (fn [state]
         (update-in state
                    [:federation peer-id* :retries]
                    (fnil + 0)
                    (max 0 (dec (long (or attempt 1)))))))
      (logging/log! :agent.telemetry/federation-send
                    (cond-> {:peer/id peer-id*
                             :latency/ms duration-ms
                             :attempt attempt
                             :success success?*
                             :http/status status}
                      error (assoc :error/message (.getMessage ^Throwable error)
                                   :error/type (or (:type (ex-data error))
                                                   (.getName (class error)))))))))

(defn record-mcp-call!
  [collector {:keys [server-url method duration-ms success? error]}]
  (when (enabled? collector)
    (let [success?* (not (false? success?))]
      (record-component-call! collector [:mcp] duration-ms success?*)
      (logging/log! :agent.telemetry/mcp-call
                    (cond-> {:mcp/server-url server-url
                             :mcp/method method
                             :latency/ms duration-ms
                             :success success?*}
                      error (assoc :error/message (.getMessage ^Throwable error)))))))

(defn record-planner!
  [collector {:keys [agent-id duration-ms success? error directive-count]}]
  (when (enabled? collector)
    (let [success?* (not (false? success?))]
      (record-component-call! collector [:planner] duration-ms success?*)
      (logging/log! :agent.telemetry/planner
                    (cond-> {:agent/id (or agent-id "system")
                             :latency/ms duration-ms
                             :success success?*
                             :directives/count (long (or directive-count 0))}
                      error (assoc :error/message (.getMessage ^Throwable error)))))))

(defn- call-summary [stats]
  (let [calls (long (or (:calls stats) 0))
        errors (long (or (:errors stats) 0))]
    (-> stats
        (dissoc :latencies)
        (assoc :calls calls
               :errors errors
               :error-rate (if (pos? calls) (/ (double errors) calls) 0.0)
               :latency-ms (latency-summary (:latencies stats))))))

(defn snapshot [collector]
  (let [state (if (enabled? collector) @(:state collector) {})
        run-latencies (:run-latencies state)
        summarize-vals (fn [m]
                         (into {}
                               (map (fn [[k stats]] [k (call-summary stats)]))
                               m))]
    {:enabled (enabled? collector)
     :runs {:count (count (:runs state))
            :terminal-count (count run-latencies)
            :latency-ms (latency-summary run-latencies)}
     :agents (:agents state)
     :tools (summarize-vals (:tools state))
     :federation (summarize-vals (:federation state))
     :mcp (call-summary (:mcp state))
     :planner (call-summary (:planner state))
     :directives (:directives state)
     :llm (call-summary (:llm state))}))

(defn health-check [collector]
  {:healthy true
   :enabled (enabled? collector)
   :summary (select-keys (snapshot collector) [:runs :llm])})
