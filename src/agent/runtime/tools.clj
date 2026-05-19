(ns agent.runtime.tools
  "Batch tool execution over agent.tools.core registries."
  (:require
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.set :as set])
  (:import
   (java.util.concurrent Callable ExecutorCompletionService Executors TimeUnit)))

(def default-mode :parallel)

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

(defn- enforce-approval-preflight! [registry tool description input context]
  (when (and (not (:yolo? context))
             ((:sensitive-fn tool) input))
    (if-let [approval-check (:approval-check registry)]
      (when-let [decision (approval-check {:tool description
                                           :input input
                                           :context context})]
        (when (:block decision)
          (throw (tools/tool-error :approval-required
                                   (or (:reason decision)
                                       "Sensitive tool requires approved request")
                                   {:tool-name (:name description)}))))
      (throw (tools/tool-error :approval-required
                               "Sensitive tool requires approval policy"
                               {:tool-name (:name description)})))))

(defn preflight-tool-call
  [registry call context opts source-index]
  (let [tool-name (normalize-tool-name (or (:tool-name call) (:name call)))
        tool (or (tools/get-tool registry tool-name)
                 (throw (tools/tool-error :tool-not-found
                                          (str "Unknown tool: " tool-name)
                                          {:tool-name tool-name})))
        description (tools/describe tool)
        input (call-input call)
        context* (tools/create-execution-context context)]
    (when-not (allowed-tool? context* tool-name)
      (throw (tools/tool-error :tool-blocked
                               "Tool not allowed in this capability bundle"
                               {:tool-name tool-name
                                :allowed-tools (vec (:allowed-tools context*))})))
    (enforce-permissions! description context*)
    (let [validated-input ((:validate-fn tool) input)
          hook-ctx {:tool description
                    :tool-call call
                    :input validated-input
                    :context context*}]
      (enforce-approval-preflight! registry tool description validated-input context*)
      (when-let [decision (when-let [before (:before-tool-call opts)]
                            (before hook-ctx))]
        (when (:block decision)
          (throw (tools/tool-error :tool-blocked
                                   (or (:reason decision) "Tool execution blocked")
                                   {:tool-name tool-name}))))
      {:source-index source-index
       :tool-call-id (call-id source-index call)
       :tool-name tool-name
       :tool tool
       :description description
       :input validated-input
       :context context*
       :call call})))

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
  (let [sink (:event-sink opts)
        start (now-ns)
        context* (assoc (:context preflight)
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

(defn- preflight-or-error [registry call context opts idx]
  (try
    (preflight-tool-call registry call context opts idx)
    (catch Exception e
      {:source-index idx
       :tool-call-id (call-id idx call)
       :tool-name (normalize-tool-name (or (:tool-name call) (:name call)))
       :preflight-error e
       :input (call-input call)
       :call call})))

(defn- finalize-results [results]
  (let [ordered (sort-by :source-index results)]
    {:results (vec ordered)
     :messages (mapv tool-result-message ordered)
     :terminate? (and (seq ordered) (every? :terminate? ordered))}))

(defn- execute-sequential! [registry preflights opts]
  (mapv (fn [preflight]
          (if-let [error (:preflight-error preflight)]
            (error-result preflight error)
            (execute-preflight! registry preflight opts)))
        preflights))

(defn- execute-parallel! [registry preflights opts]
  (let [errors (filterv :preflight-error preflights)
        ready (remove :preflight-error preflights)
        pool (Executors/newFixedThreadPool (max 1 (count ready)))
        ecs (ExecutorCompletionService. pool)]
    (try
      (doseq [preflight ready]
        (.submit ecs ^Callable #(execute-preflight! registry preflight opts)))
      (loop [remaining (count ready)
             results (mapv #(error-result % (:preflight-error %)) errors)]
        (if (zero? remaining)
          results
          (let [future (.take ecs)
                result (.get future)]
            (recur (dec remaining) (conj results result)))))
      (finally
        (.shutdown pool)
        (.awaitTermination pool 5 TimeUnit/SECONDS)))))

(defn- batches [preflights opts]
  (let [global-mode (normalize-tool-name (or (:mode opts) default-mode))]
    (loop [xs preflights
           acc []]
      (if (empty? xs)
        acc
        (let [mode (execution-mode-for (first xs) opts)]
          (if (= :sequential mode)
            (recur (rest xs) (conj acc [:sequential [(first xs)]]))
            (let [[parallel* rest*] (split-with #(not= :sequential (execution-mode-for % (assoc opts :mode global-mode))) xs)]
              (recur rest* (conj acc [:parallel (vec parallel*)])))))))))

(defn execute-batch!
  ([registry calls context] (execute-batch! registry calls context {}))
  ([registry calls context opts]
   (let [opts* (update opts :mode #(normalize-tool-name (or % default-mode)))
         preflights (mapv (fn [[idx call]]
                            (preflight-or-error registry call context opts* idx))
                          (map-indexed vector calls))
         results (mapcat (fn [[mode batch]]
                           (case mode
                             :sequential (execute-sequential! registry batch opts*)
                             :parallel (execute-parallel! registry batch opts*)))
                         (batches preflights opts*))]
     (finalize-results results))))
