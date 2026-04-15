(ns agent.tools.core
  "Core tool protocols and interfaces.
  Provides abstract interfaces for external tool integration with security and validation."
  (:require
   [clojure.spec.alpha :as s]
   [cheshire.core :as json]
   [clojure.walk :as walk])
  (:import
   (java.time Instant)))

;; ======================
;; Tool Protocols
;; ======================

(defprotocol ITool
  "Protocol for external tool execution."
  
  (execute [this input context]
    "Execute tool with input and execution context.
    input: map with tool-specific parameters
    context: map with execution context (user, permissions, etc.)
    Returns: tool execution result")
  
  (describe [this]
    "Get tool description including schema.
    Returns: map with :name, :description, :version, :schema, etc.")
  
  (validate-input [this input]
    "Validate input against tool schema.
    Returns: validated input or throws exception"))

(defprotocol IToolWithPermissions
  "Protocol for tools with permission requirements."
  
  (required-permissions [this]
    "Get required permissions for this tool.
    Returns: set of required permission keywords")
  
  (check-permissions [this context]
    "Check if execution context has required permissions.
    context: map with :user and :permissions
    Returns: boolean indicating if permissions are satisfied"))

(defprotocol IToolWithMonitoring
  "Protocol for tools with monitoring capabilities."
  
  (get-metrics [this]
    "Get tool execution metrics.
    Returns: map with :executions, :errors, :avg-latency, etc.")
  
  (health-check [this]
    "Check tool health.
    Returns: map with :healthy boolean and :details"))

(defprotocol IToolWithConfiguration
  "Protocol for tools with dynamic configuration."
  
  (update-config [this new-config]
    "Update tool configuration.
    new-config: map with updated configuration
    Returns: updated tool instance")
  
  (get-config [this]
    "Get current tool configuration.
    Returns: configuration map"))

;; ======================
;; Tool Registry
;; ======================

(defprotocol IToolRegistry
  "Protocol for managing multiple tools."
  
  (register-tool [this name tool]
    "Register a tool with a name.
    name: keyword identifier for the tool
    tool: ITool instance
    Returns: updated registry")
  
  (get-tool [this name]
    "Get tool by name.
    Returns: tool instance or nil")
  
  (list-tools [this]
    "List all registered tools.
    Returns: map of name->tool-description")
  
  (find-tools [this criteria]
    "Find tools matching criteria.
    criteria: map with :name, :category, :capabilities, etc.
    Returns: vector of matching tool names and descriptions")
  
  (remove-tool [this name]
    "Remove tool from registry.
    Returns: updated registry"))

;; ======================
;; Tool Execution Context
;; ======================

(defrecord ToolExecutionContext [user permissions session-id request-id timestamp]
  "Context for tool execution."
  
  Object
  (toString [this]
    (str "ToolExecutionContext[user=" user ", session=" session-id "]")))

(defn create-execution-context
  "Create tool execution context."
  [user permissions & {:keys [session-id request-id timestamp]
                       :or {session-id (str (java.util.UUID/randomUUID))
                            request-id (str (java.util.UUID/randomUUID))
                            timestamp (Instant/now)}}]
  (->ToolExecutionContext user permissions session-id request-id timestamp))

;; ======================
;; Common Types and Specs
;; ======================

(s/def ::tool-name keyword?)
(s/def ::tool-description string?)
(s/def ::tool-version string?)
(s/def ::tool-category #{:api :database :filesystem :shell :email :calendar :payment})

(s/def ::input-schema map?)
(s/def ::output-schema map?)
(s/def ::tool-schema (s/keys :req-un [::input-schema ::output-schema]))

(s/def ::permission keyword?)
(s/def ::permissions (s/coll-of ::permission :kind set?))

(s/def ::execution-result
  (s/keys :req-un [::success]
          :opt-un [::data ::error ::execution-time-ms]))

(s/def ::tool-description-map
  (s/keys :req-un [::tool-name ::tool-description ::tool-version ::tool-schema]
          :opt-un [::tool-category ::required-permissions ::timeout-ms ::rate-limit]))

(s/def ::execution-context
  (s/keys :req-un [::user ::permissions]
          :opt-un [::session-id ::request-id ::timestamp]))

;; ======================
;; Common Utilities
;; ======================

(defn validate-with-schema
  "Validate data against JSON Schema."
  [schema data]
  ;; Simplified validation - in production would use actual JSON Schema validator
  (when (and schema (not (map? data)))
    (throw (ex-info "Input must be a map" {:schema schema :data data})))
  data)

(defn create-tool-description
  "Create standardized tool description."
  [name description version input-schema output-schema & {:keys [category permissions timeout-ms]}]
  {:name name
   :description description
   :version version
   :schema {:input input-schema :output output-schema}
   :category category
   :required-permissions (or permissions #{})
   :timeout-ms (or timeout-ms 30000)})

(defn execute-with-timeout
  "Execute function with timeout."
  [f timeout-ms]
  (let [future-result (future (f))
        result (deref future-result timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do
        (future-cancel future-result)
        (throw (ex-info "Tool execution timeout" {:timeout-ms timeout-ms})))
      result)))

(defn safe-execute
  "Execute tool with error handling."
  [tool input context]
  (try
    (let [start-time (System/currentTimeMillis)
          validated-input (validate-input tool input)
          result (execute tool validated-input context)
          execution-time (- (System/currentTimeMillis) start-time)]
      {:success true
       :data result
       :execution-time-ms execution-time
       :timestamp (Instant/now)})
    (catch Exception e
      {:success false
       :error (.getMessage e)
       :error-details (ex-data e)
       :timestamp (Instant/now)})))

;; ======================
;; Error Handling
;; ======================

(defn tool-error
  "Create a tool error."
  ([type message] (tool-error type message {}))
  ([type message details]
   (ex-info message (merge {:type type} details))))

(defn permission-error
  "Create permission error."
  [required actual]
  (tool-error :permission-denied
              "Insufficient permissions"
              {:required-permissions required
               :actual-permissions actual}))

(defn validation-error
  "Create validation error."
  [schema errors]
  (tool-error :validation-failed
              "Input validation failed"
              {:schema schema :errors errors}))

;; ======================
;; Tool Factory
;; ======================

(defmulti create-tool
  "Create tool based on type."
  (fn [type config] type))

(defmethod create-tool :default
  [type config]
  (throw (ex-info (str "Unknown tool type: " type) {:type type :config config})))

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Protocol usage example
  (defprotocol IExampleTool
    (execute [this input context]))
  
  ;; Creating a tool that implements the protocol
  (defrecord ExampleTool [config]
    ITool
    (execute [this input context]
      {:result "Example execution" :input input})
    
    (describe [this]
      (create-tool-description
       :example-tool
       "An example tool for demonstration"
       "1.0.0"
       {:type "object"
        :properties {:param {:type "string"}}}
       {:type "object"
        :properties {:result {:type "string"}}}
       :category :api
       :permissions #{:read-data}
       :timeout-ms 5000))
    
    (validate-input [this input]
      (validate-with-schema
       {:type "object"
        :properties {:param {:type "string"}}}
       input))
    
    IToolWithPermissions
    (required-permissions [this]
      #{:read-data})
    
    (check-permissions [this context]
      (contains? (:permissions context) :read-data))
    
    IToolWithMonitoring
    (get-metrics [this]
      {:executions 0 :errors 0 :avg-latency-ms 0})
    
    (health-check [this]
      {:healthy true :details "Tool is healthy"})
    
    IToolWithConfiguration
    (update-config [this new-config]
      (->ExampleTool (merge config new-config)))
    
    (get-config [this]
      config))
  
  ;; Creating and using a tool
  (def example-tool (->ExampleTool {:api-key "test"}))
  
  (def context (create-execution-context
                "user-1"
                #{:read-data :write-data}))
  
  (execute example-tool {:param "test"} context)
  
  (describe example-tool)
  
  ;; Using specs
  (s/valid? ::tool-name :example-tool)
  (s/valid? ::permissions #{:read-data :write-data})
  
  ;; Safe execution
  (safe-execute example-tool {:param "test"} context)
  
  ;; Execution with timeout
  (execute-with-timeout
   #(execute example-tool {:param "test"} context)
   5000)
  
  ;; Error handling
  (try
    (execute example-tool {} context)
    (catch ToolError e
      (println "Tool error:" (.getMessage e))))
  
  ;; Permission checking
  (check-permissions example-tool {:permissions #{:read-data}})
  
  ;; Tool description
  (create-tool-description
   :weather-tool
   "Get weather information"
   "1.0.0"
   {:type "object"
    :properties {:city {:type "string"}}}
   {:type "object"
    :properties {:temperature {:type "number"}
                 :conditions {:type "string"}}}
   :category :api
   :permissions #{:access-weather}
   :timeout-ms 10000))
