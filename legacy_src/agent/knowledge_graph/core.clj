(ns agent.knowledge-graph.core
  "Core knowledge graph protocols and interfaces.
  Provides abstract interfaces for knowledge graph backends with extended capabilities."
  (:require
   [clojure.spec.alpha :as s]))

;; ======================
;; Extended Knowledge Graph Protocol
;; ======================

(defprotocol IKnowledgeGraph
  "Protocol for knowledge graph operations."
  
  (store-fact [this subject predicate object]
    "Store a fact (triple) in the knowledge graph.")
  
  (query [this pattern]
    "Query the knowledge graph with a pattern.")
  
  (find-entities [this type]
    "Find all entities of a given type.")
  
  (get-facts [this subject]
    "Get all facts about a subject.")
  
  (infer [this rules]
    "Apply inference rules to derive new knowledge."))

(defprotocol IKnowledgeGraphWithFeatures
  "Protocol for knowledge graph backend features."
  
  (supports-inference? [this]
    "Check if backend supports inference.")
  
  (supports-transactions? [this]
    "Check if backend supports ACID transactions.")
  
  (supports-versioning? [this]
    "Check if backend supports versioning.")
  
  (get-capabilities [this]
    "Get backend capabilities map.")
  
  (optimize-query [this query]
    "Optimize query for this backend."))

(defprotocol IKnowledgeGraphWithManagement
  "Protocol for knowledge graph management operations."
  
  (backup [this path]
    "Create backup of knowledge graph.")
  
  (restore [this path]
    "Restore knowledge graph from backup.")
  
  (stats [this]
    "Get database statistics.")
  
  (health-check [this]
    "Check backend health.")
  
  (clear [this]
    "Clear all data from knowledge graph."))

(defprotocol IKnowledgeGraphWithTransactions
  "Protocol for transaction support."
  
  (begin-transaction [this]
    "Begin a new transaction.")
  
  (commit-transaction [this tx]
    "Commit transaction.")
  
  (rollback-transaction [this tx]
    "Rollback transaction.")
  
  (with-transaction [this f]
    "Execute function within a transaction."))

;; ======================
;; Backend Registry
;; ======================

(defprotocol IKnowledgeGraphRegistry
  "Protocol for managing multiple knowledge graph backends."
  
  (register-backend [this name backend]
    "Register a backend with a name.")
  
  (get-backend [this name]
    "Get backend by name.")
  
  (list-backends [this]
    "List all registered backends.")
  
  (select-backend [this criteria]
    "Select backend based on criteria.")
  
  (remove-backend [this name]
    "Remove backend from registry."))

;; ======================
;; Abstract Query Representation
;; ======================

(defrecord Query [type pattern bindings options]
  "Abstract query representation."
  
  Object
  (toString [this]
    (str "Query[type=" type ", pattern=" pattern "]")))

(defrecord TriplePattern [subject predicate object]
  "Triple pattern for graph queries."
  
  Object
  (toString [this]
    (str "(" subject " " predicate " " object ")")))

(defrecord PropertyPattern [entity property value]
  "Property pattern for property graph queries."
  
  Object
  (toString [this]
    (str "[" entity "." property " = " value "]")))

;; ======================
;; Common Types and Specs
;; ======================

(s/def ::entity-id (s/or :string string? :keyword keyword? :long integer?))
(s/def ::predicate (s/or :string string? :keyword keyword?))
(s/def ::value any?)
(s/def ::triple (s/tuple ::entity-id ::predicate ::value))

(s/def ::query-type #{:datalog :cypher :sparql :gremlin})
(s/def ::query-pattern (s/or :string string? :vector vector? :map map?))
(s/def ::query-bindings map?)
(s/def ::query-options map?)

(s/def ::abstract-query
  (s/keys :req-un [::query-type ::query-pattern]
          :opt-un [::query-bindings ::query-options]))

(s/def ::backend-type #{:asami :neo4j :postgres :janusgraph :rdfox :stardog})
(s/def ::backend-config map?)

(s/def ::capabilities
  (s/keys :opt-un [::supports-inference ::supports-transactions
                   ::supports-versioning ::max-triples ::query-languages]))

(s/def ::health-status
  (s/keys :req-un [::healthy]
          :opt-un [::latency-ms ::triple-count ::last-backup]))

(s/def ::backend-stats
  (s/keys :opt-un [::triple-count ::entity-count ::relation-count
                   ::storage-size ::query-count]))

;; ======================
;; Common Utilities
;; ======================

(defn create-triple-pattern
  "Create a triple pattern for queries."
  [subject predicate object]
  (->TriplePattern subject predicate object))

(defn create-property-pattern
  "Create a property pattern for property graph queries."
  [entity property value]
  (->PropertyPattern entity property value))

(defn normalize-triple
  "Normalize triple components."
  [subject predicate object]
  [(if (keyword? subject) (name subject) (str subject))
   (if (keyword? predicate) (name predicate) (str predicate))
   object])

(defn triple->map
  "Convert triple to map representation."
  [[subject predicate object]]
  {:subject subject :predicate predicate :object object})

(defn map->triple
  "Convert map to triple."
  [{:keys [subject predicate object]}]
  [subject predicate object])

(defn create-abstract-query
  "Create abstract query representation."
  [type pattern & {:keys [bindings options]}]
  (->Query type pattern (or bindings {}) (or options {})))

;; ======================
;; Error Handling
;; ======================

(defn kg-error
  "Create a knowledge graph error."
  ([type message] (kg-error type message {}))
  ([type message details]
   (ex-info message (merge {:type type} details))))

(defn validate-triple
  "Validate triple before storage."
  [subject predicate object]
  (when (or (nil? subject) (nil? predicate) (nil? object))
    (throw (kg-error :invalid-triple
                     "Triple components cannot be nil"
                     {:subject subject :predicate predicate :object object})))
  true)

;; ======================
;; Query Translation
;; ======================

(defmulti translate-query
  "Translate abstract query to backend-specific format."
  (fn [backend-type query] backend-type))

(defmethod translate-query :default
  [backend-type query]
  (throw (ex-info (str "No translator for backend type: " backend-type)
                  {:backend-type backend-type :query query})))

;; ======================
;; Backend Factory
;; ======================

(defmulti create-backend
  "Create knowledge graph backend based on type."
  (fn [type config] type))

(defmethod create-backend :default
  [type config]
  (throw (ex-info (str "Unknown backend type: " type) {:type type :config config})))

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Protocol usage example
  (defprotocol IExampleBackend
    (store-fact [this subject predicate object]))
  
  ;; Creating a backend that implements the protocol
  (defrecord ExampleBackend [config]
    IKnowledgeGraph
    (store-fact [this subject predicate object]
      (println "Storing:" subject predicate object))
    
    (query [this pattern]
      [{:subject "example" :predicate "type" :object "test"}])
    
    (find-entities [this type]
      ["entity-1" "entity-2"])
    
    (get-facts [this subject]
      [[subject "type" "example"]])
    
    (infer [this rules]
      (println "Inferring with" (count rules) "rules"))
    
    IKnowledgeGraphWithFeatures
    (supports-inference? [this] true)
    (supports-transactions? [this] false)
    (supports-versioning? [this] false)
    
    (get-capabilities [this]
      {:supports-inference true
       :supports-transactions false
       :max-triples 1000000
       :query-languages [:datalog]})
    
    (optimize-query [this query]
      query)
    
    IKnowledgeGraphWithManagement
    (backup [this path]
      (println "Backing up to" path))
    
    (restore [this path]
      (println "Restoring from" path))
    
    (stats [this]
      {:triple-count 100 :entity-count 50})
    
    (health-check [this]
      {:healthy true :latency-ms 10})
    
    (clear [this]
      (println "Clearing all data")))
  
  ;; Creating and using a backend
  (def example-backend (->ExampleBackend {:type :example}))
  
  (store-fact example-backend "entity-1" "type" "person")
  
  (query example-backend '[:find ?e :where [?e :type "person"]])
  
  ;; Using specs
  (s/valid? ::entity-id "entity-1")
  (s/valid? ::triple ["entity-1" "type" "person"])
  
  ;; Abstract queries
  (def abstract-query (create-abstract-query
                       :datalog
                       '[:find ?e :where [?e :type "person"]]
                       :bindings {}
                       :options {:limit 10}))
  
  ;; Error handling
  (try
    (store-fact example-backend nil "type" "person")
    (catch KGError e
      (println "KG error:" (.getMessage e))))
  
  ;; Query translation
  (def translated (translate-query :asami abstract-query)))
