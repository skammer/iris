(ns agent.tools.core
  "Rewritten tool registry and execution helpers."
  (:require
   [clojure.set :as set]))

(defprotocol ITool
  (execute [this input context])
  (describe [this])
  (health-check [this]))

(defrecord BasicTool [description execute-fn validate-fn health-fn]
  ITool
  (execute [_ input context]
    (execute-fn (validate-fn input) context))
  (describe [_]
    description)
  (health-check [_]
    (health-fn)))

(defrecord ToolRegistry [tools before-execute after-execute event-sink])

(defn tool-error
  ([type message] (tool-error type message {}))
  ([type message details]
   (ex-info message (merge {:type type} details))))

(defn permission-error [required actual]
  (tool-error :permission-denied
              "Insufficient permissions"
              {:required-permissions required
               :actual-permissions actual}))

(defn validation-error [message details]
  (tool-error :validation-failed message details))

(defn create-tool-description
  [name description & {:keys [version category input-schema required-permissions timeout-ms source source-details]
                       :or {version "1.0.0"
                            required-permissions #{}
                            timeout-ms 30000
                            source :builtin}}]
  {:name name
   :description description
   :version version
   :category category
   :input-schema input-schema
   :required-permissions required-permissions
   :timeout-ms timeout-ms
   :source source
   :source-details source-details})

(defn create-tool
  [{:keys [description execute-fn validate-fn health-fn]}]
  (->BasicTool description
               execute-fn
               (or validate-fn identity)
               (or health-fn (fn [] {:healthy true}))))

(defn create-execution-context
  ([] (create-execution-context {}))
  ([context]
   (-> context
       (update :permissions #(set (or % #{})))
       (#(if (contains? % :allowed-tools)
           (update % :allowed-tools
                   (fn [tools]
                     (set (map (fn [tool]
                                 (if (string? tool) (keyword tool) tool))
                               tools))))
           %))
       (update :user #(or % "system"))
       (update :request-id #(or % (str (java.util.UUID/randomUUID)))))))

(defn create-registry
  ([] (create-registry {}))
  ([{:keys [tools before-execute after-execute event-sink]
     :or {tools {}}}]
   (->ToolRegistry tools before-execute after-execute event-sink)))

(defn- emit-event!
  [registry event]
  (when-let [sink (:event-sink registry)]
    (sink event)))

(defn- hook-context [tool-description input context]
  {:tool tool-description
   :input input
   :context context})

(defn register-tool
  [registry tool]
  (let [tool-name (:name (describe tool))]
    (assoc registry :tools (assoc (:tools registry) tool-name tool))))

(defn get-tool
  [registry tool-name]
  (get (:tools registry) tool-name))

(defn list-tools
  [registry]
  (->> (:tools registry)
       (sort-by key)
       (mapv (fn [[_ tool]] (describe tool)))))

(defn execute-tool
  ([registry tool-name input]
   (execute-tool registry tool-name input {}))
  ([registry tool-name input context]
   (let [tool (get-tool registry tool-name)]
     (when-not tool
       (throw (tool-error :tool-not-found
                          (str "Unknown tool: " tool-name)
                          {:tool-name tool-name})))
     (let [context* (create-execution-context context)
           tool-description (describe tool)
           required (:required-permissions tool-description)
           actual (:permissions context*)
           allowed-tools (:allowed-tools context*)]
       (when (and (contains? context* :allowed-tools)
                  (not (or (contains? allowed-tools tool-name)
                           (contains? allowed-tools :*)
                           (contains? allowed-tools (keyword (name tool-name))))))
         (throw (tool-error :tool-blocked
                            "Tool not allowed in this capability bundle"
                            {:tool-name tool-name
                             :allowed-tools (vec allowed-tools)})))
       (when-not (set/subset? required actual)
         (throw (permission-error required actual)))
       (let [validated-input ((or (:validate-fn tool) identity) input)]
         (emit-event! registry
                      {:event-type :tool.execution.requested
                       :entity-type :tool
                       :entity-id (name tool-name)
                       :request-id (:request-id context*)
                       :payload {:tool-name (name tool-name)
                                 :source (name (:source tool-description))
                                 :user (:user context*)
                                 :input validated-input}})
         (when-let [decision (when-let [before-execute (:before-execute registry)]
                               (before-execute (hook-context tool-description validated-input context*)))]
           (when (:block decision)
             (emit-event! registry
                          {:event-type :tool.execution.blocked
                           :entity-type :tool
                           :entity-id (name tool-name)
                           :request-id (:request-id context*)
                           :payload {:tool-name (name tool-name)
                                     :reason (:reason decision)}})
             (throw (tool-error :tool-blocked
                                (or (:reason decision) "Tool execution blocked")
                                {:tool-name tool-name}))))
         (try
           (let [result ((:execute-fn tool) validated-input context*)
                 final-result (if-let [postprocess (:after-execute registry)]
                                (let [hook-result (postprocess (assoc (hook-context tool-description validated-input context*)
                                                                      :result result
                                                                      :is-error false))]
                                  (or (:result hook-result) result))
                                result)]
             (emit-event! registry
                          {:event-type :tool.execution.succeeded
                           :entity-type :tool
                           :entity-id (name tool-name)
                           :request-id (:request-id context*)
                           :payload {:tool-name (name tool-name)
                                     :source (name (:source tool-description))
                                     :input validated-input
                                     :result final-result}})
             final-result)
           (catch Exception e
             (when-let [postprocess (:after-execute registry)]
               (postprocess (assoc (hook-context tool-description validated-input context*)
                                   :error e
                                   :is-error true)))
             (emit-event! registry
                          {:event-type :tool.execution.failed
                           :entity-type :tool
                           :entity-id (name tool-name)
                           :request-id (:request-id context*)
                           :payload {:tool-name (name tool-name)
                                     :source (name (:source tool-description))
                                     :input validated-input
                                     :error (.getMessage e)}})
             (throw e))))))))

(defn registry-health
  [registry]
  (let [tools (:tools registry)
        statuses (->> tools
                      (sort-by key)
                      (mapv (fn [[name tool]]
                              {:name name
                               :health (health-check tool)})))
        healthy? (every? #(true? (get-in % [:health :healthy] true)) statuses)]
    {:healthy healthy?
     :count (count tools)
     :tools statuses}))
