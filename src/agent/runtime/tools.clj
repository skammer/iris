(ns agent.runtime.tools
  "Batch tool execution over agent.tools.core registries."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.set :as set])
  (:import
   (java.util.concurrent Callable ExecutorCompletionService Executors TimeUnit)))

(def default-mode :policy)
(def default-max-parallelism 6)

(defn- now-ns [] (System/nanoTime))

(defn- duration-ms [start-ns]
  (/ (double (- (System/nanoTime) start-ns)) 1000000.0))

(defn- normalize-tool-name [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- call-input [call]
  (or (:input call) (:arguments call) (:args call) {}))

(defn- call-id [idx call]
  (str (or (:id call) (:tool-call-id call) (:tool_call_id call) (str "tool-call-" idx))))

(defn- cancelled-error []
  (ex-info "Chat stopped" {:type :chat-cancelled}))

(defn- cancelled? [token]
  (cond
    (nil? token) false
    (instance? clojure.lang.IDeref token) (true? @token)
    (fn? token) (true? (token))
    :else (true? token)))

(defn- cancellation-token [opts]
  (or (:cancellation-token opts) (:cancelled? opts)))

(defn- throw-if-cancelled! [opts]
  (when (cancelled? (cancellation-token opts))
    (throw (cancelled-error))))

(defn- event! [sink event]
  (when sink
    (sink event)))

(defn- result-content [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (json/generate-string value)))

(defn- error-result [preflight ex]
  (let [data (ex-data ex)]
    {:source-index (:source-index preflight)
     :tool-call-id (:tool-call-id preflight)
     :tool-name (:tool-name preflight)
     :status :error
     :error-type (:type data)
     :error (.getMessage ex)
     :input (:input preflight)
     :terminate? false}))

(defn- tool-result-message [result]
  {:role "tool"
   :tool-call-id (:tool-call-id result)
   :name (some-> (:tool-name result) name)
   :content (if (= :ok (:status result))
              (result-content (:result result))
              (result-content {:error (:error result)
                               :type (some-> (:error-type result) name)}))})

(defn- allowed-tool? [context tool-name]
  (let [allowed (set (map normalize-tool-name (:allowed-tools context)))]
    (or (not (contains? context :allowed-tools))
        (contains? allowed tool-name)
        (contains? allowed :*))))

(defn- enforce-permissions! [description context]
  (let [required (:required-permissions description #{})
        actual (set (:permissions context))]
    (when-not (set/subset? required actual)
      (throw (tools/permission-error required actual)))))

(defn preflight-tool-call
  [registry call context opts source-index]
  (throw-if-cancelled! opts)
  (let [tool-name (normalize-tool-name (or (:tool-name call) (:name call)))
        tool (or (tools/get-tool registry tool-name)
                 (throw (tools/tool-error :tool-not-found
                                          (str "Unknown tool: " tool-name)
                                          {:tool-name tool-name})))
        description (tools/describe tool)
        input (call-input call)
        context* (tools/create-execution-context (merge context (:context call)))]
    (when-not (allowed-tool? context* tool-name)
      (throw (tools/tool-error :tool-blocked
                               "Tool not allowed in this capability bundle"
                               {:tool-name tool-name
                                :allowed-tools (vec (:allowed-tools context*))})))
    (enforce-permissions! description context*)
    (let [validated-input ((:validate-fn tool) input)
          preflight {:source-index source-index
                     :tool-call-id (call-id source-index call)
                     :tool-name tool-name
                     :tool tool
                     :description description
                     :input validated-input
                     :context context*
                     :sensitive? (boolean ((:sensitive-fn tool) validated-input))
                     :call call}
          hook-ctx {:tool description
                    :tool-call call
                    :input validated-input
                    :context context*}]
      (try
        ;; Approval is enforced authoritatively in tools.core/execute-tool, not
        ;; here. Running it twice through divergent code paths (allow-on-ambiguous
        ;; vs block-on-ambiguous) was the double-tool-enforcement bug.
        (when-let [decision (when-let [before (:before-tool-call opts)]
                              (before hook-ctx))]
          (when (:block decision)
            (throw (tools/tool-error :tool-blocked
                                     (or (:reason decision) "Tool execution blocked")
                                     {:tool-name tool-name}))))
        preflight
        (catch Exception e
          (throw (ex-info (.getMessage e)
                          (assoc (ex-data e) :preflight preflight)
                          e)))))))

(defn- update-fn [sink preflight]
  (fn [payload]
    (event! sink {:event-type :tool-execution-update
                  :entity-type :tool
                  :entity-id (name (:tool-name preflight))
                  :request-id (get-in preflight [:context :request-id])
                  :payload (merge {:tool-name (name (:tool-name preflight))
                                   :tool-call-id (:tool-call-id preflight)}
                                  (if (map? payload) payload {:value payload}))})))

(defn- execute-preflight! [registry preflight opts]
  (throw-if-cancelled! opts)
  (let [sink (:event-sink opts)
        start (now-ns)
        ;; :preflighted? tells execute-tool that allow-list/permission/validation
        ;; already ran here and that this layer owns the tool-execution events
        ;; (it carries tool-call-id/source-index that chat + UI correlate on).
        ;; execute-tool still enforces approval and runs its registry hooks.
        context* (assoc (:context preflight)
                        :preflighted? true
                        :on-tool-update (update-fn sink preflight)
                        :tool-update! (update-fn sink preflight))]
    (event! sink {:event-type :tool-execution-start
                  :entity-type :tool
                  :entity-id (name (:tool-name preflight))
                  :request-id (:request-id context*)
                  :payload {:tool-name (name (:tool-name preflight))
                            :tool-call-id (:tool-call-id preflight)
                            :source-index (:source-index preflight)}})
    (try
      (let [raw-result (tools/execute-tool registry (:tool-name preflight) (:input preflight) context*)
            result* {:source-index (:source-index preflight)
                     :tool-call-id (:tool-call-id preflight)
                     :tool-name (:tool-name preflight)
                     :status :ok
                     :input (:input preflight)
                     :result raw-result
                     :terminate? (true? (:terminate raw-result))
                     :duration-ms (duration-ms start)}
            override (when-let [after (:after-tool-call opts)]
                       (after (assoc preflight
                                     :context context*
                                     :result result*
                                     :duration-ms (:duration-ms result*))))
            result (cond-> result*
                     (contains? override :result) (assoc :result (:result override))
                     (contains? override :status) (assoc :status (:status override))
                     (contains? override :terminate?) (assoc :terminate? (:terminate? override))
                     (contains? override :terminate) (assoc :terminate? (:terminate override)))]
        (event! sink {:event-type :tool-execution-end
                      :entity-type :tool
                      :entity-id (name (:tool-name preflight))
                      :request-id (:request-id context*)
                      :payload {:tool-name (name (:tool-name preflight))
                                :tool-call-id (:tool-call-id preflight)
                                :status (name (:status result))
                                :duration-ms (:duration-ms result)}})
        result)
      (catch Exception e
        (let [result (assoc (error-result preflight e)
                            :duration-ms (duration-ms start))]
          (event! sink {:event-type :tool-execution-end
                        :entity-type :tool
                        :entity-id (name (:tool-name preflight))
                        :request-id (:request-id context*)
                        :payload {:tool-name (name (:tool-name preflight))
                                  :tool-call-id (:tool-call-id preflight)
                                  :status "error"
                                  :error (.getMessage e)
                                  :duration-ms (:duration-ms result)}})
          result)))))

(defn- execution-mode-for [preflight opts]
  (normalize-tool-name
   (or (:execution-mode (:call preflight))
       (get-in opts [:tool-execution-modes (:tool-name preflight)])
       (:execution-mode (:description preflight))
       (:mode opts)
       default-mode)))

(defn- legacy-sequential? [preflight opts]
  (= :sequential (execution-mode-for preflight opts)))

(defn- preflight-or-error [registry call context opts idx]
  (try
    (preflight-tool-call registry call context opts idx)
    (catch Exception e
      (merge {:source-index idx
              :tool-call-id (call-id idx call)
              :tool-name (normalize-tool-name (or (:tool-name call) (:name call)))
              :input (call-input call)
              :call call}
             (:preflight (ex-data e))
             {:preflight-error e}))))

(defn- finalize-results [results]
  (let [ordered (sort-by :source-index results)]
    {:results (vec ordered)
     :messages (mapv tool-result-message ordered)
     :terminate? (and (seq ordered) (every? :terminate? ordered))}))

(defn- execute-sequential! [registry preflights opts]
  (mapv (fn [preflight]
          (throw-if-cancelled! opts)
          (if-let [error (:preflight-error preflight)]
            (error-result preflight error)
            (execute-preflight! registry preflight opts)))
        preflights))

(defn- execute-parallel! [registry preflights opts]
  (let [errors (filterv :preflight-error preflights)
        ready (remove :preflight-error preflights)
        max-parallelism (max 1 (long (or (:max-parallelism opts)
                                         default-max-parallelism)))
        pool-size (max 1 (min (count ready) max-parallelism))
        pool (Executors/newFixedThreadPool pool-size)
        ecs (ExecutorCompletionService. pool)
        futures (atom [])]
    (try
      (doseq [preflight ready]
        (throw-if-cancelled! opts)
        (swap! futures conj
               (.submit ecs ^Callable #(execute-preflight! registry preflight opts))))
      (loop [remaining (count ready)
             results (mapv #(error-result % (:preflight-error %)) errors)]
        (if (zero? remaining)
          results
          (let [future (.take ecs)
                _ (throw-if-cancelled! opts)
                result (.get future)]
            (recur (dec remaining) (conj results result)))))
      (catch Exception e
        (when (or (= :chat-cancelled (some-> e ex-data :type))
                  (cancelled? (cancellation-token opts)))
          (doseq [future @futures]
            (.cancel future true))
          (.shutdownNow pool))
        (throw e))
      (finally
        (when-not (.isShutdown pool)
          (.shutdown pool))
        (.awaitTermination pool 5 TimeUnit/SECONDS)))))

(defn- approval-sensitive-call? [preflight]
  (or (:sensitive? preflight)
      (true? (get-in preflight [:description :approval-sensitive?]))))

(defn- activates-tools-call? [preflight]
  (true? (get-in preflight [:description :activates-tools?])))

(defn- batch-forces-sequential? [preflights]
  (some #(or (approval-sensitive-call? %)
             (activates-tools-call? %))
        preflights))

(defn- parallel-safe-preflight? [preflight opts]
  (and (not (:preflight-error preflight))
       (not (legacy-sequential? preflight opts))
       (not (approval-sensitive-call? preflight))
       (not (activates-tools-call? preflight))
       (tools/parallel-safe-call? (:description preflight) (:input preflight))))

(defn- sequential-batches [preflights]
  (mapv (fn [preflight] [:sequential [preflight]]) preflights))

(defn- batches [preflights opts]
  (cond
    (<= (count preflights) 1)
    (sequential-batches preflights)

    (= :sequential (normalize-tool-name (:mode opts)))
    (sequential-batches preflights)

    (batch-forces-sequential? preflights)
    (sequential-batches preflights)

    :else
    (loop [xs preflights
           acc []]
      (if (empty? xs)
        acc
        (if (parallel-safe-preflight? (first xs) opts)
          (let [[safe* rest*] (split-with #(parallel-safe-preflight? % opts) xs)
                safe* (vec safe*)]
            (recur rest*
                   (conj acc [(if (> (count safe*) 1) :parallel :sequential) safe*])))
          (recur (rest xs) (conj acc [:sequential [(first xs)]])))))))

(defn execute-batch!
  ([registry calls context] (execute-batch! registry calls context {}))
  ([registry calls context opts]
   (let [opts* (update opts :mode #(normalize-tool-name (or % default-mode)))
         _ (throw-if-cancelled! opts*)
         preflights (mapv (fn [[idx call]]
                            (preflight-or-error registry call context opts* idx))
                          (map-indexed vector calls))
         results (mapcat (fn [[mode batch]]
                           (case mode
                             :sequential (execute-sequential! registry batch opts*)
                             :parallel (execute-parallel! registry batch opts*)))
                         (batches preflights opts*))]
     (finalize-results results))))
