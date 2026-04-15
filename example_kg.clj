(ns agent.example-kg
  (:require
   [agent.knowledge-graph :as kg]
   [agent.kg-integration :as kgi]
   [clojure.pprint :refer [pprint]]))

;; Example 1: Basic knowledge graph operations
(defn demo-basic-kg []
  (println "=== Basic Knowledge Graph Demo ===")
  (let [graph (kg/create-in-memory-graph {:name "demo-graph"})]
    
    ;; Store some facts
    (println "\n1. Storing facts...")
    (kg/store-triple graph :clojure :type :programming-language)
    (kg/store-triple graph :clojure :paradigm :functional)
    (kg/store-triple graph :clojure :creator "Rich Hickey")
    (kg/store-triple graph :clojure :year-created 2007)
    
    (kg/store-triple graph :python :type :programming-language)
    (kg/store-triple graph :python :paradigm :multi-paradigm)
    
    ;; Add an entity with multiple properties
    (println "\n2. Adding entity...")
    (kg/add-entity graph :agent-1 :ai-agent
                   {:name "Demo Agent"
                    :capabilities [:reasoning :learning :knowledge-graph]
                    :status :active
                    :knows-about [:clojure :python]})
    
    ;; Query
    (println "\n3. Querying all programming languages:")
    (pprint (kg/find-entities graph :programming-language))
    
    (println "\n4. Getting all facts about Clojure:")
    (pprint (kg/get-facts graph :clojure))
    
    (println "\n5. Finding what agent knows about:")
    (pprint (kg/find-related graph :agent-1 :knows-about))
    
    graph))

;; Example 2: Knowledge extraction and enhancement
(defn demo-knowledge-extraction []
  (println "\n=== Knowledge Extraction Demo ===")
  
  (let [text "Clojure is a functional programming language that runs on the JVM. It's great for building AI agents."]
    (println "Extracting knowledge from text:")
    (println "Text:" text)
    
    (let [facts (kgi/extract-simple-facts text)]
      (println "\nExtracted facts:")
      (pprint facts))
    
    (println "\nKeywords extracted:")
    (pprint (kgi/extract-keywords text))))

;; Example 3: Integration with agent flow
(defn demo-integrated-flow []
  (println "\n=== Integrated Flow Demo ===")
  
  ;; Simulate agent interaction
  (let [prompt "What programming languages are good for AI?"
        response "Clojure and Python are both excellent for AI development."]
    
    (println "Storing interaction...")
    (kgi/store-interaction prompt response)
    
    (println "Querying relevant knowledge for prompt...")
    (let [knowledge (kgi/query-relevant-knowledge prompt)]
      (println "Relevant knowledge found:" (count knowledge) "items"))))

;; Example 4: Inference demonstration
(defn demo-inference []
  (println "\n=== Inference Demo ===")
  
  (let [graph (kg/create-in-memory-graph {:name "inference-demo"})]
    
    ;; Store some relationships
    (kg/store-triple graph :alice :friend :bob)
    (kg/store-triple graph :bob :friend :charlie)
    (kg/store-triple graph :charlie :friend :david)
    
    (println "Initial facts stored:")
    (println "Alice -> friend -> Bob")
    (println "Bob -> friend -> Charlie")
    (println "Charlie -> friend -> David")
    
    ;; Apply transitive friend rule
    (println "\nApplying transitive friend inference...")
    (let [rules [{:name "transitive-friends"
                  :pattern '[:find ?a ?c
                             :where [?a :friend ?b]
                                    [?b :friend ?c]]
                  :conclusion [:friend]}]]
      (kg/infer graph rules))
    
    ;; Query to see inferred relationships
    (println "\nQuerying friend relationships:")
    (pprint (kg/query graph '[:find ?person ?friend
                              :where [?person :friend ?friend]]))))

;; Run all demos
(defn -main [& args]
  (println "Starting Knowledge Graph Demos...")
  
  (demo-basic-kg)
  (demo-knowledge-extraction)
  (demo-integrated-flow)
  (demo-inference)
  
  (println "\n=== All demos completed ==="))

(comment
  ;; Run in REPL
  (demo-basic-kg)
  (demo-knowledge-extraction)
  (demo-integrated-flow)
  (demo-inference)
  
  ;; Test with real data
  (def test-graph (kg/create-in-memory-graph {:name "test"}))
  
  ;; Store domain knowledge
  (kg/store-triple test-graph :llm :type :ai-technology)
  (kg/store-triple test-graph :llm :purpose "natural language understanding")
  (kg/store-triple test-graph :knowledge-graph :type :ai-technology)
  (kg/store-triple test-graph :knowledge-graph :purpose "structured knowledge representation")
  
  ;; Query
  (kg/find-entities test-graph :ai-technology)
  
  ;; Complex query
  (kg/query test-graph '[:find ?tech ?purpose
                         :where [?tech :type :ai-technology]
                                [?tech :purpose ?purpose]])
  )