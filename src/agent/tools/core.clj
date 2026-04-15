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

(defrecord ToolRegistry [tools])

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
  [name description & {:keys [version category input-schema required-permissions timeout-ms]
                       :or {version "1.0.0"
                            required-permissions #{}
                            timeout-ms 30000}}]
  {:name name
   :description description
   :version version
   :category category
   :input-schema input-schema
   :required-permissions required-permissions
   :timeout-ms timeout-ms})

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
       (update :user #(or % "system"))
       (update :request-id #(or % (str (java.util.UUID/randomUUID)))))))

(defn create-registry
  ([] (->ToolRegistry {}))
  ([tools]
   (->ToolRegistry tools)))

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
           required (:required-permissions (describe tool))
           actual (:permissions context*)]
       (when-not (set/subset? required actual)
         (throw (permission-error required actual)))
       (execute tool input context*)))))

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
