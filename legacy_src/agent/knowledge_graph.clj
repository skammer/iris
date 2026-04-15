(ns agent.knowledge-graph
  "Knowledge graph integration for the agent system.
  Provides interfaces for storing and querying structured knowledge."
  (:require
   [asami.core :as d]
   [clojure.set :as set])
  (:refer-clojure :exclude [find]))

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

(defn apply-rule
  "Legacy placeholder for rule application.
  Archived path keeps inference API shape but does not derive new facts here."
  [_db _rule]
  [])

(defrecord AsamiKnowledgeGraph [conn uri]
  IKnowledgeGraph
  (store-fact [_ subject predicate object]
    @(d/transact conn {:tx-data [{:db/id subject
                                  predicate object}]}))
  
  (query [_ pattern]
    (let [db (d/db conn)]
      (d/q pattern db)))
  
  (find-entities [_ type]
    (let [db (d/db conn)]
      (d/q '[:find ?e
             :where [?e :type type]]
           db)))
  
  (get-facts [_ subject]
    (let [db (d/db conn)]
      (d/q '[:find ?p ?o
             :where [subject ?p ?o]]
           db)))
  
  (infer [_ rules]
    ;; Basic inference - in production would use Asami's built-in inference
    (let [db (d/db conn)]
      ;; Apply each rule
      (reduce
       (fn [db* rule]
         (let [new-facts (apply-rule db* rule)]
           (if (seq new-facts)
             @(d/transact conn {:tx-data new-facts})
             db*)))
       db
       rules))))

(defn create-in-memory-graph
  "Create an in-memory knowledge graph.
  Options:
  - :name (optional, default: 'agent-kg')"
  [opts]
  (let [name (or (:name opts) "agent-kg")
        uri (str "asami:mem://" name)]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      (->AsamiKnowledgeGraph conn uri))))

(defn store-triple
  "Convenience function to store a triple."
  [kg subject predicate object]
  (store-fact kg subject predicate object))

(defn query-pattern
  "Query with a simple pattern."
  [kg subject predicate object]
  (let [db (d/db (:conn kg))]
    (d/q '[:find ?s ?p ?o
           :in $ ?s ?p ?o
           :where [?s ?p ?o]]
         db subject predicate object)))

(defn add-entity
  "Add a complete entity with multiple properties."
  [kg entity-id type properties]
  (let [tx-data (concat
                 [{:db/id entity-id
                   :type type}]
                 (map (fn [[k v]] {:db/id entity-id k v}) properties))]
    @(d/transact (:conn kg) {:tx-data tx-data})))

(defn find-related
  "Find entities related to a given entity."
  [kg entity-id relation]
  (let [db (d/db (:conn kg))]
    (d/q '[:find ?target
           :in $ ?entity-id ?relation
           :where [?entity-id ?relation ?target]]
         db entity-id relation)))

(defn basic-inference-rules
  "Some basic inference rules for demonstration."
  []
  [{:name "transitive-friends"
    :pattern '[:find ?a ?c
               :where [?a :friend ?b]
                      [?b :friend ?c]]
    :conclusion [:friend]}
   
   {:name "type-inheritance"
    :pattern '[:find ?instance ?super-prop ?value
               :where [?instance :type ?type]
                      [?type :inherits ?super-type]
                      [?super-type ?super-prop ?value]]
    :conclusion [:inherited]}])

(comment
  ;; Example usage
  (def kg (create-in-memory-graph {:name "test-agent"}))

  ;; Store basic facts
  (store-triple kg :clojure :type :programming-language)
  (store-triple kg :clojure :paradigm :functional)
  (store-triple kg :clojure :creator "Rich Hickey")

  ;; Add entity with multiple properties
  (add-entity kg :agent-1 :ai-agent
              {:name "Test Agent"
               :capabilities [:reasoning :learning]
               :status :active})

  ;; Query
  (query-pattern kg :clojure :paradigm :functional)
  
  ;; Find all programming languages
  (find-entities kg :programming-language)
  
  ;; Get all facts about Clojure
  (get-facts kg :clojure)
  
  ;; Store relationships
  (store-triple kg :agent-1 :knows-about :clojure)
  (store-triple kg :agent-1 :knows-about :ai)
  
  ;; Find what agent knows about
  (find-related kg :agent-1 :knows-about)
  
  ;; Apply inference
  (infer kg (basic-inference-rules))
  )
