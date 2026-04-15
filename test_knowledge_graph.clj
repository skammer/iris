(ns agent.test-knowledge-graph
  (:require
   [agent.knowledge-graph :as kg]
   [clojure.test :refer :all]))

(deftest test-knowledge-graph-creation
  (testing "Create in-memory knowledge graph"
    (let [graph (kg/create-in-memory-graph {:name "test-graph"})]
      (is (instance? agent.knowledge_graph.AsamiKnowledgeGraph graph))
      (is (:conn graph))
      (is (:uri graph)))))

(deftest test-store-and-query
  (let [graph (kg/create-in-memory-graph {:name "test-store"})]
    
    (testing "Store triple"
      (kg/store-triple graph :test-subject :test-predicate :test-object)
      ;; Give time for async transaction
      (Thread/sleep 100))
    
    (testing "Query stored triple"
      (let [results (kg/query-pattern graph :test-subject :test-predicate :test-object)]
        (is (seq results))
        (is (= 1 (count results)))))

    (testing "Get facts about subject"
      (let [facts (kg/get-facts graph :test-subject)]
        (is (seq facts))
        (is (some #(= :test-predicate (first %)) facts))))))

(deftest test-add-entity
  (let [graph (kg/create-in-memory-graph {:name "test-entity"})]
    
    (testing "Add entity with properties"
      (kg/add-entity graph :person-1 :person
                     {:name "Test Person"
                      :age 30
                      :occupation "Developer"})
      (Thread/sleep 100))
    
    (testing "Find entities by type"
      (let [persons (kg/find-entities graph :person)]
        (is (seq persons))
        (is (= 1 (count persons)))))))

(deftest test-find-related
  (let [graph (kg/create-in-memory-graph {:name "test-related"})]
    
    (testing "Store relationships and find related"
      (kg/store-triple graph :parent :has-child :child-1)
      (kg/store-triple graph :parent :has-child :child-2)
      (Thread/sleep 100))
    
    (testing "Find related entities"
      (let [children (kg/find-related graph :parent :has-child)]
        (is (seq children))
        (is (= 2 (count children)))))))

(deftest test-basic-inference-rules
  (testing "Basic inference rules exist"
    (let [rules (kg/basic-inference-rules)]
      (is (seq rules))
      (is (every? :name rules))
      (is (every? :pattern rules))
      (is (every? :conclusion rules)))))

(deftest test-knowledge-graph-protocol
  (let [graph (kg/create-in-memory-graph {:name "test-protocol"})]
    
    (testing "IKnowledgeGraph protocol implementation"
      (is (satisfies? kg/IKnowledgeGraph graph))
      
      (testing "store-fact method"
        (kg/store-fact graph :protocol-test :test-key :test-value)
        (Thread/sleep 100))
      
      (testing "query method"
        (let [results (kg/query graph '[:find ?s ?p ?o
                                        :where [?s ?p ?o]])]
          (is (seq results))))
      
      (testing "find-entities method"
        (let [entities (kg/find-entities graph :test)]
          (is (coll? entities)))))))

(run-tests 'agent.test-knowledge-graph)