(ns agent.knowledge-graph.backends.neo4j
  "Neo4j knowledge graph backend implementation."
  (:require
   [agent.knowledge-graph.core :as kg-core]
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.walk :as walk])
  (:import
   (java.net URLEncoder)))

;; ======================
;; Neo4j Backend
;; ======================

(defrecord Neo4jBackend [uri username password encryption config]
  kg-core/IKnowledgeGraph
  (store-fact [this subject predicate object]
    (let [cypher "CREATE (s {id: $subject})-[:$predicate]->(o {id: $object})"
          params {:subject (str subject)
                  :predicate (str predicate)
                  :object (str object)}]
      (execute-cypher this cypher params)))
  
  (query [this pattern]
    (if (string? pattern)
      ;; Assume pattern is Cypher query
      (execute-cypher this pattern {})
      ;; Convert abstract pattern to Cypher
      (let [cypher (pattern->cypher pattern)]
        (execute-cypher this cypher {}))))
  
  (find-entities [this type]
    (let [cypher "MATCH (e {type: $type}) RETURN e.id as id"
          params {:type (str type)}
          result (execute-cypher this cypher params)]
      (map :id result)))
  
  (get-facts [this subject]
    (let [cypher "MATCH (s {id: $subject})-[r]->(o) RETURN type(r) as predicate, o.id as object"
          params {:subject (str subject)}
          result (execute-cypher this cypher params)]
      (map (fn [{:keys [predicate object]}]
             [subject predicate object])
           result)))
  
  (infer [this rules]
    ;; Neo4j supports rule-based inference via APOC procedures
    (doseq [rule rules]
      (let [cypher (rule->cypher rule)]
        (execute-cypher this cypher {})))
    this)

  kg-core/IKnowledgeGraphWithFeatures
  (supports-inference? [this] true)
  (supports-transactions? [this] true)
  (supports-versioning? [this] false)
  
  (get-capabilities [this]
    {:supports-inference true
     :supports-transactions true
     :supports-versioning false
     :max-triples 1000000000  ; Neo4j can handle billions
     :query-languages [:cypher]
     :features [:apoc-procedures :graph-algorithms :fulltext-search]})
  
  (optimize-query [this query]
    ;; Add query hints for Neo4j
    (if (string? query)
      (str "PROFILE " query)  ; Add PROFILE for query analysis
      query))

  kg-core/IKnowledgeGraphWithManagement
  (backup [this path]
    ;; Neo4j backup via APOC or admin API
    (let [cypher "CALL apoc.export.cypher.all($path, {})"
          params {:path path}]
      (execute-cypher this cypher params)))
  
  (restore [this path]
    ;; Neo4j restore
    (let [cypher "CALL apoc.cypher.runFile($path)"
          params {:path path}]
      (execute-cypher this cypher params)))
  
  (stats [this]
    (let [node-count (execute-cypher this "MATCH (n) RETURN count(n) as count" {})
          rel-count (execute-cypher this "MATCH ()-[r]->() RETURN count(r) as count" {})]
      {:triple-count (get (first rel-count) :count 0)
       :entity-count (get (first node-count) :count 0)
       :relation-count (get (first rel-count) :count 0)
       :storage-size 0  ; Would need admin API for this
       :query-count 0}))
  
  (health-check [this]
    (try
      (let [start-time (System/currentTimeMillis)
            result (execute-cypher this "RETURN 1 as health" {})
            latency (- (System/currentTimeMillis) start-time)]
        {:healthy (seq result)
         :latency-ms latency
         :last-checked (System/currentTimeMillis)})
      (catch Exception e
        {:healthy false
         :error (.getMessage e)
         :last-checked (System/currentTimeMillis)})))
  
  (clear [this]
    (execute-cypher this "MATCH (n) DETACH DELETE n" {}))

  kg-core/IKnowledgeGraphWithTransactions
  (begin-transaction [this]
    ;; Neo4j supports transactions via HTTP API
    (let [response (http/post (str uri "/transaction")
                              {:basic-auth [username password]
                               :headers {"Content-Type" "application/json"}
                               :as :json})]
      (-> response :body :commit)))
  
  (commit-transaction [this tx-id]
    (http/post (str uri "/transaction/" tx-id "/commit")
               {:basic-auth [username password]
                :headers {"Content-Type" "application/json"}
                :as :json}))
  
  (rollback-transaction [this tx-id]
    (http/delete (str uri "/transaction/" tx-id)
                 {:basic-auth [username password]
                  :as :json}))
  
  (with-transaction [this f]
    (let [tx-id (begin-transaction this)]
      (try
        (let [result (f this)]
          (commit-transaction this tx-id)
          result)
        (catch Exception e
          (rollback-transaction this tx-id)
          (throw e))))))

;; ======================
;; Helper Functions
;; ======================

(defn execute-cypher
  "Execute Cypher query against Neo4j."
  [backend cypher params]
  (let [url (str (:uri backend) "/cypher")
        body {:query cypher
              :params params}
        response (http/post url
                            {:basic-auth [(:username backend) (:password backend)]
                             :headers {"Content-Type" "application/json"
                                       "Accept" "application/json"}
                             :body (json/generate-string body)
                             :as :json})]
    (-> response :body :data)))

(defn pattern->cypher
  "Convert abstract pattern to Cypher query."
  [pattern]
  (cond
    (vector? pattern)
    (let [[subject predicate object] pattern]
      (str "MATCH (s {id: '" subject "'})-[:" predicate "]->(o {id: '" object "'}) "
           "RETURN s.id as subject, type(r) as predicate, o.id as object"))
    
    (map? pattern)
    (let [{:keys [subject predicate object]} pattern]
      (str "MATCH (s {id: '" subject "'})-[:" predicate "]->(o {id: '" object "'}) "
           "RETURN s.id as subject, type(r) as predicate, o.id as object"))
    
    :else
    (throw (kg-core/kg-error :invalid-pattern
                             "Cannot convert pattern to Cypher"
                             {:pattern pattern}))))

(defn rule->cypher
  "Convert inference rule to Cypher."
  [rule]
  (let [{:keys [head body]} rule]
    ;; Simple rule translation
    (str "MATCH " (str/join ", " (map #(str "(" % ")") body))
         " WHERE " (str/join " AND " (map #(str % ".type = '" % "'") body))
         " CREATE " head)))

(defn create-index
  "Create index on property for better performance."
  [backend label property]
  (let [cypher (str "CREATE INDEX ON :" label "(" property ")")]
    (execute-cypher backend cypher {})))

(defn create-constraint
  "Create uniqueness constraint."
  [backend label property]
  (let [cypher (str "CREATE CONSTRAINT ON (n:" label ") ASSERT n." property " IS UNIQUE")]
    (execute-cypher backend cypher {})))

;; ======================
;; Factory Functions
;; ======================

(defn create-neo4j-backend
  "Create a Neo4j knowledge graph backend.
  Options:
  - :uri (required, e.g., 'http://localhost:7474')
  - :username (required)
  - :password (required)
  - :encryption (optional, default false)
  - :config (optional, additional configuration)"
  [opts]
  (let [uri (or (:uri opts)
                (throw (ex-info "Neo4j URI required" {})))
        username (or (:username opts)
                     (System/getenv "NEO4J_USERNAME")
                     (throw (ex-info "Neo4j username required" {})))
        password (or (:password opts)
                     (System/getenv "NEO4J_PASSWORD")
                     (throw (ex-info "Neo4j password required" {})))]
    (->Neo4jBackend uri username password (:encryption opts false)
                    (dissoc opts :uri :username :password :encryption))))

;; ======================
;; Example Usage
;; ======================

(comment
  ;; Create backend
  (def neo4j (create-neo4j-backend
              {:uri "http://localhost:7474"
               :username "neo4j"
               :password "password"}))
  
  ;; Store facts
  (kg-core/store-fact neo4j "person-1" "type" "Person")
  (kg-core/store-fact neo4j "person-1" "name" "Alice")
  (kg-core/store-fact neo4j "person-1" "knows" "person-2")
  
  ;; Query
  (kg-core/query neo4j "MATCH (p:Person) RETURN p.name as name")
  
  ;; Find entities
  (kg-core/find-entities neo4j "Person")
  
  ;; Get facts about entity
  (kg-core/get-facts neo4j "person-1")
  
  ;; Inference
  (kg-core/infer neo4j [{:head "(p1)-[:knows_transitive]->(p2)"
                         :body ["(p1)-[:knows]->(p2)"]}])
  
  ;; Check capabilities
  (kg-core/supports-inference? neo4j)
  (kg-core/get-capabilities neo4j)
  
  ;; Management operations
  (kg-core/stats neo4j)
  (kg-core/health-check neo4j)
  
  ;; Transactions
  (kg-core/with-transaction neo4j
    (fn [tx-backend]
      (kg-core/store-fact tx-backend "temp" "type" "Temporary")
      ;; If anything throws, transaction is rolled back
      ))
  
  ;; Create indexes for performance
  (create-index neo4j "Person" "name")
  (create-constraint neo4j "Person" "id")
  
  ;; Clear database
  (kg-core/clear neo4j))