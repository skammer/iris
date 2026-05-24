(ns agent.tools.core
  "Rewritten tool registry and execution helpers."
  (:require
   [cheshire.core :as json]
   [clojure.set :as set]
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as json-schema])
  (:import
   (java.security MessageDigest)))

(defprotocol ITool
  (execute [this input context])
  (describe [this])
  (health-check [this]))

(defrecord BasicTool [description execute-fn validate-fn health-fn sensitive-fn]
  ITool
  (execute [_ input context]
    (execute-fn (validate-fn input) context))
  (describe [_]
    (dissoc description :malli-schema :sensitive-predicate))
  (health-check [_]
    (health-fn)))

(defrecord ToolRegistry [tools before-execute after-execute event-sink approval-check activity-executor])

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

(defn- json-input-schema [input-schema]
  (try
    (json-schema/transform input-schema)
    (catch Exception e
      (throw (validation-error "input-schema must be a valid Malli schema"
                               {:input-schema input-schema
                                :error (.getMessage e)})))))

(defn create-tool-description
  [name description & {:keys [version category input-schema required-permissions timeout-ms source source-details sensitive execution-mode prerequisites]
                       :or {version "1.0.0"
                            required-permissions #{}
                            timeout-ms 30000
                            source :builtin
                            sensitive false}}]
  (when-not input-schema
    (throw (validation-error "input-schema is required" {:tool-name name})))
  {:name name
   :description description
   :version version
   :category category
   :input-schema (json-input-schema input-schema)
   :malli-schema input-schema
   :required-permissions required-permissions
   :timeout-ms timeout-ms
   :source source
   :source-details source-details
   :execution-mode execution-mode
   :prerequisites prerequisites
   :sensitive (if (ifn? sensitive) true (boolean sensitive))
   :sensitive-predicate sensitive})

(defn- schema-validator [description]
  (let [schema (:malli-schema description)
        valid? (m/validator schema)]
    (fn [input]
      (if (valid? input)
        input
        (throw (validation-error
                "input failed schema validation"
                {:input input
                 :errors (me/humanize (m/explain schema input))}))))))

(defn- sensitive-fn [description]
  (let [sensitive (:sensitive-predicate description)]
    (cond
      (ifn? sensitive) sensitive
      (true? sensitive) (constantly true)
      :else (constantly false))))

(defn create-tool
  [{:keys [description execute-fn validate-fn health-fn]}]
  (when-not (:malli-schema description)
    (throw (validation-error "tool description must include Malli input-schema"
                             {:tool-name (:name description)})))
  (when-not execute-fn
    (throw (validation-error "execute-fn is required" {:tool-name (:name description)})))
  (let [base-validator (schema-validator description)
        validator (if validate-fn
                    (fn [input] (validate-fn (base-validator input)))
                    base-validator)]
    (->BasicTool description
                 execute-fn
                 validator
                 (or health-fn (fn [] {:healthy true}))
                 (sensitive-fn description))))

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
  ([{:keys [tools before-execute after-execute event-sink approval-check activity-executor]
     :or {tools {}}}]
   (->ToolRegistry tools before-execute after-execute event-sink approval-check activity-executor)))

(defn with-approval
  [registry approval-check]
  (assoc registry :approval-check approval-check))

(defn- emit-event!
  [registry event]
  (when-let [sink (:event-sink registry)]
    (sink event)))

(defn- hook-context [tool-description input context]
  {:tool tool-description
   :input input
   :context context})

(defn- sensitive-input? [tool input]
  (boolean ((:sensitive-fn tool) input)))

(defn- enforce-approval! [registry tool tool-description input context]
  (when (and (not (:yolo? context))
             (sensitive-input? tool input))
    (let [approval-check (:approval-check registry)]
      (when-not approval-check
        (throw (tool-error :approval-required
                           "Sensitive tool requires approval policy"
                           {:tool-name (:name tool-description)})))
      (when-let [decision (approval-check (hook-context tool-description input context))]
        (when (:block decision)
          (throw (tool-error :approval-required
                             (or (:reason decision) "Sensitive tool requires approved request")
                             {:tool-name (:name tool-description)})))))))

(defn- sha256-hex [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- canonical-json [value]
  (json/generate-string value {:canonical true}))

(defn- tool-activity-name [tool-name validated-input]
  (let [tool (name tool-name)]
    (str "tool." tool "." (subs (sha256-hex (canonical-json validated-input)) 0 16))))

(defn- execute-effect! [registry tool tool-name validated-input context* f]
  (if-let [activity-executor (:activity-executor registry)]
    (if-let [{:keys [run-id command-id activity-name]} (:activity context*)]
      (activity-executor {:run-id run-id
                          :command-id command-id
                          :activity-name (or activity-name
                                             (tool-activity-name tool-name validated-input))
                          :input {:tool-name (name tool-name)
                                  :input validated-input}}
                         f)
      (f))
    (f)))

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
       (let [validated-input ((:validate-fn tool) input)]
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
           (enforce-approval! registry tool tool-description validated-input context*)
           (catch Exception e
             (emit-event! registry
                          {:event-type :tool.execution.blocked
                           :entity-type :tool
                           :entity-id (name tool-name)
                           :request-id (:request-id context*)
                           :payload {:tool-name (name tool-name)
                                     :reason (.getMessage e)}})
             (throw e)))
         (let [start-ns (System/nanoTime)]
           (try
             (let [result (execute-effect! registry
                                           tool
                                           tool-name
                                           validated-input
                                           context*
                                           #((:execute-fn tool) validated-input context*))
                   duration-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
                   final-result (if-let [postprocess (:after-execute registry)]
                                  (let [hook-result (postprocess (assoc (hook-context tool-description validated-input context*)
                                                                        :result result
                                                                        :duration-ms duration-ms
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
               (let [duration-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)]
                 (when-let [postprocess (:after-execute registry)]
                   (postprocess (assoc (hook-context tool-description validated-input context*)
                                       :error e
                                       :duration-ms duration-ms
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
                 (throw e))))))))))

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
