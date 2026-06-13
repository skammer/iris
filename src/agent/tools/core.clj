(ns agent.tools.core
  "Tool registry and execution kernel. Defines tool contracts, input validation,
   permission checks, approval hooks, lifecycle hooks, and normalized execution
   receipts consumed by the runtime loop."
  (:require
   [agent.util :as util]
   [clojure.set :as set]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as json-schema]))

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

(defrecord ToolRegistry [tools before-execute after-execute event-sink approval-check])

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

(defn- schema-children [schema]
  (let [children (rest schema)]
    (if (map? (first children))
      (rest children)
      children)))

(defn- map-entry-schema [entry]
  (let [[k maybe-props & rest*] entry]
    [k (if (map? maybe-props) (first rest*) maybe-props)]))

(defn- parse-long-string [value]
  (let [value* (str/trim value)]
    (if (re-matches #"[+-]?\d+" value*)
      (try
        (Long/parseLong value*)
        (catch NumberFormatException _
          value))
      value)))

(defn- parse-double-string [value]
  (let [value* (str/trim value)]
    (if (re-matches #"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?" value*)
      (try
        (Double/parseDouble value*)
        (catch NumberFormatException _
          value))
      value)))

(declare coerce-schema-input)

(defn- coerce-map-input [schema value]
  (if-not (map? value)
    value
    (reduce (fn [acc entry]
              (let [[k child-schema] (map-entry-schema entry)]
                (if (contains? acc k)
                  (update acc k #(coerce-schema-input child-schema %))
                  acc)))
            value
            (schema-children schema))))

(defn- coerce-vector-input [schema value]
  (if-not (vector? value)
    value
    (let [children (schema-children schema)
          item-schema (if (map? (first children))
                        (second children)
                        (first children))]
      (if item-schema
        (mapv #(coerce-schema-input item-schema %) value)
        value))))

(defn- coerce-schema-input [schema value]
  (let [schema-type (if (vector? schema) (first schema) schema)]
    (cond
      (nil? value) nil
      (= :int schema-type) (if (string? value) (parse-long-string value) value)
      (= 'number? schema-type) (if (string? value) (parse-double-string value) value)
      (= number? schema-type) (if (string? value) (parse-double-string value) value)
      (= :boolean schema-type) (if (string? value)
                                 (case (str/lower-case (str/trim value))
                                   "true" true
                                   "false" false
                                   value)
                                 value)
      (and (vector? schema) (= :maybe schema-type))
      (coerce-schema-input (first (schema-children schema)) value)
      (and (vector? schema) (= :map schema-type))
      (coerce-map-input schema value)
      (and (vector? schema) (= :vector schema-type))
      (coerce-vector-input schema value)
      :else value)))

(defn- json-input-schema [input-schema]
  (try
    (json-schema/transform input-schema)
    (catch Exception e
      (throw (validation-error "input-schema must be a valid Malli schema"
                               {:input-schema input-schema
                                :error (.getMessage e)})))))

(def ^:private act-permission-fragments
  ["write" "exec" "reload" "send" "request" "call" "delete" "create" "mutate"])

(defn- normalize-operation [operation]
  (case operation
    (:read "read") :read
    (:act "act") :act
    nil))

(defn- action-set [actions]
  (set (keep (fn [action]
               (cond
                 (keyword? action) action
                 (string? action) (keyword (str/lower-case action))
                 :else nil))
	             actions)))

(defn- routing-category-set [categories]
  (set (keep (fn [category]
               (cond
                 (keyword? category) category
                 (string? category) (keyword (str/lower-case category))
                 :else nil))
             categories)))

(defn- act-permission? [permission]
  (let [value (str/lower-case (name permission))]
    (some #(str/includes? value %) act-permission-fragments)))

(defn- derived-operation [operation approval-sensitive? sensitive required-permissions]
  (or (normalize-operation operation)
      (if (or approval-sensitive?
              (true? sensitive)
              (some act-permission? required-permissions))
        :act
	        :read)))

(defn- derived-routing-categories [category operation]
  (let [category* (cond
                    (keyword? category) category
                    (string? category) (keyword (str/lower-case category))
                    :else category)]
    (case category*
      :respond #{:respond}
      :messaging #{:messaging}
      :api #{:web :read}
      :memory (if (= :act operation) #{:write :plan} #{:read :search :plan})
      :system (if (= :act operation) #{:write :run} #{:read :search})
      :mcp (if (= :act operation) #{:write :run :web} #{:read :search :web})
      (case operation
        :act #{:write}
        :read #{:read}
        #{:read}))))

(defn- action-value [input action-key]
  (let [value (get input action-key)]
    (cond
      (keyword? value) value
      (string? value) (keyword (str/lower-case value))
      :else value)))

(defn tool-execution-metadata
  "Return public execution-safety metadata for a tool description."
  [description]
  (select-keys description
               [:operation
                :routing-categories
                :parallel-safe?
                :approval-sensitive?
                :activates-tools?
                :action-key
                :read-only-actions
                :parallel-safe-actions]))

(defn- call-operation [metadata input]
  (let [action-key (:action-key metadata)
        action (when action-key (action-value input action-key))]
    (if (and action (contains? (:read-only-actions metadata) action))
      :read
      (:operation metadata))))

(defn parallel-safe-call?
  [description input]
  (let [metadata (tool-execution-metadata description)
        action-key (:action-key metadata)
        action (when action-key (action-value input action-key))
        action-safe? (and action (contains? (:parallel-safe-actions metadata) action))
        explicit-safe? (true? (:parallel-safe? metadata))
        operation (call-operation metadata input)]
    (and (not (:approval-sensitive? metadata))
         (not (:activates-tools? metadata))
         (or action-safe?
             (and explicit-safe?
                  (contains? #{:read :act} operation))))))

(defn create-tool-description
  [name description & {:keys [version category input-schema required-permissions timeout-ms source source-details sensitive execution-mode
                              operation routing-categories parallel-safe? approval-sensitive? activates-tools?
                              action-key read-only-actions parallel-safe-actions]
                       :or {version "1.0.0"
                            required-permissions #{}
                            timeout-ms 30000
                            source :builtin
                            sensitive false}}]
  (when-not input-schema
    (throw (validation-error "input-schema is required" {:tool-name name})))
  (let [sensitive? (if (ifn? sensitive) true (boolean sensitive))
        approval-sensitive?* (if (some? approval-sensitive?)
                               (boolean approval-sensitive?)
                               sensitive?)
        required-permissions* (set required-permissions)
        operation* (derived-operation operation approval-sensitive?* sensitive? required-permissions*)
        routing-categories* (or (not-empty (routing-category-set routing-categories))
                                (derived-routing-categories category operation*))]
    {:name name
     :description description
     :version version
     :category category
     :input-schema (json-input-schema input-schema)
     :malli-schema input-schema
     :required-permissions required-permissions*
     :timeout-ms timeout-ms
     :source source
     :source-details source-details
     :execution-mode execution-mode
     :operation operation*
     :routing-categories routing-categories*
     :parallel-safe? (boolean parallel-safe?)
     :approval-sensitive? approval-sensitive?*
     :activates-tools? (boolean activates-tools?)
     :action-key action-key
     :read-only-actions (action-set read-only-actions)
     :parallel-safe-actions (action-set parallel-safe-actions)
     :sensitive sensitive?
     :sensitive-predicate sensitive}))

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
  [{:keys [description execute-fn validate-fn health-fn coerce-fn]}]
  (when-not (:malli-schema description)
    (throw (validation-error "tool description must include Malli input-schema"
                             {:tool-name (:name description)})))
  (when-not execute-fn
    (throw (validation-error "execute-fn is required" {:tool-name (:name description)})))
  (let [schema (:malli-schema description)
        coerce* (or coerce-fn #(coerce-schema-input schema %))
        base-validator (schema-validator description)
        validator (if validate-fn
                    (fn [input] (validate-fn (base-validator (coerce* input))))
                    (fn [input] (base-validator (coerce* input))))]
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
  ([{:keys [tools before-execute after-execute event-sink approval-check]
     :or {tools {}}}]
   (->ToolRegistry tools before-execute after-execute event-sink approval-check)))

(defn- emit-event!
  [registry event]
  (when-let [sink (:event-sink registry)]
    (sink event)))

(defn- tool-end-payload
  [tool-description tool-name validated-input status details]
  (cond-> (merge {:tool-name (name tool-name)
                  :status (name status)}
                 details)
    (:source tool-description) (assoc :source (name (:source tool-description)))
    (some? validated-input) (assoc :input validated-input)))

(defn- emit-tool-end!
  [emit* context* tool-description tool-name validated-input start-ns status details]
  (emit* {:event-type :tool-execution-end
          :entity-type :tool
          :entity-id (name tool-name)
          :request-id (:request-id context*)
          :payload (tool-end-payload tool-description
                                     tool-name
                                     validated-input
                                     status
                                     (assoc details :duration-ms (util/duration-ms start-ns)))}))

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
      (let [decision (approval-check (hook-context tool-description input context))]
        (cond
          (:allow decision) nil
          (:block decision) (throw (tool-error :approval-required
                                               (or (:reason decision) "Sensitive tool requires approved request")
                                               {:tool-name (:name tool-description)}))
          :else (throw (tool-error :approval-required
                                   "Sensitive tool approval policy did not allow execution"
                                   {:tool-name (:name tool-description)})))))))

(defn- execute-effect! [_registry _tool-name _validated-input _context* f]
  (f))

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
           ;; When the runtime batch layer already ran allow-list/permission/
           ;; validation and owns the tool-execution events, skip them here to
           ;; avoid the double, divergent enforcement. Approval and the registry
           ;; before/after-execute hooks remain authoritative in this function.
           preflighted? (boolean (:preflighted? context*))
           emit* (fn [event] (when-not preflighted? (emit-event! registry event)))
           tool-description (describe tool)
           required (:required-permissions tool-description)
           actual (:permissions context*)
           allowed-tools (:allowed-tools context*)]
       (when (and (not preflighted?)
                  (contains? context* :allowed-tools)
                  (not (or (contains? allowed-tools tool-name)
                           (contains? allowed-tools :*)
                           (contains? allowed-tools (keyword (name tool-name))))))
         (throw (tool-error :tool-blocked
                            "Tool not allowed in this capability bundle"
                            {:tool-name tool-name
                             :allowed-tools (vec allowed-tools)})))
       (when-not (or preflighted? (set/subset? required actual))
         (throw (permission-error required actual)))
       (let [validated-input (if preflighted? input ((:validate-fn tool) input))
             start-ns (System/nanoTime)]
         (emit* {:event-type :tool-execution-start
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
             (emit-tool-end! emit*
                             context*
                             tool-description
                             tool-name
                             validated-input
                             start-ns
                             :blocked
                             {:reason (:reason decision)})
             (throw (tool-error :tool-blocked
                                (or (:reason decision) "Tool execution blocked")
                                {:tool-name tool-name}))))
         (try
           (enforce-approval! registry tool tool-description validated-input context*)
           (catch Exception e
             (emit-tool-end! emit*
                             context*
                             tool-description
                             tool-name
                             validated-input
                             start-ns
                             :blocked
                             {:reason (.getMessage e)})
             (throw e)))
         (try
           (let [result (execute-effect! registry
                                         tool-name
                                         validated-input
                                         context*
                                         #((:execute-fn tool) validated-input context*))
                 duration-ms (util/duration-ms start-ns)
                 final-result (if-let [postprocess (:after-execute registry)]
                                (let [hook-result (postprocess (assoc (hook-context tool-description validated-input context*)
                                                                      :result result
                                                                      :duration-ms duration-ms
                                                                      :is-error false))]
                                  (or (:result hook-result) result))
                                result)]
             (emit-tool-end! emit*
                             context*
                             tool-description
                             tool-name
                             validated-input
                             start-ns
                             :succeeded
                             {:result final-result})
             final-result)
           (catch Exception e
             (let [duration-ms (util/duration-ms start-ns)]
               (when-let [postprocess (:after-execute registry)]
                 (postprocess (assoc (hook-context tool-description validated-input context*)
                                     :error e
                                     :duration-ms duration-ms
                                     :is-error true)))
               (emit-tool-end! emit*
                               context*
                               tool-description
                               tool-name
                               validated-input
                               start-ns
                               :failed
                               {:error (.getMessage e)})
               (throw e)))))))))

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
