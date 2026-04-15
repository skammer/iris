(ns agent.test-multi-head
  (:require
   [agent.multi-head :as mh]
   [agent.llm :as llm]
   [agent.knowledge-graph :as kg]
   [clojure.test :refer :all]))

;; Mock implementations for testing
(def mock-llm-provider
  (reify llm/ILLMProvider
    (complete [_ messages _]
      "{\"choice\": \"Test Choice\", \"reasoning\": \"Test reasoning\", \"confidence\": 0.8, \"risks\": [\"risk1\"], \"benefits\": [\"benefit1\"]}")
    (stream [_ messages _] (async/chan))
    (embed [_ text _] [])))

(deftest test-decision-head-creation
  (testing "Create decision head"
    (let [kg (kg/create-in-memory-graph {:name "test-head"})
          head (mh/->DecisionHead :test "Test Head" "testing" mock-llm-provider kg)]
      (is (instance? agent.multi_head.DecisionHead head))
      (is (= :test (:id head)))
      (is (= "Test Head" (:name head)))
      (is (= "testing" (mh/specialty head))))))

(deftest test-evaluation-parsing
  (testing "Parse evaluation response"
    (let [response "{\"choice\": \"Option A\", \"reasoning\": \"Good choice\", \"confidence\": 0.9, \"risks\": [], \"benefits\": [\"fast\"]}"
          parsed (mh/parse-evaluation-response response)]
      (is (= "Option A" (:choice parsed)))
      (is (= "Good choice" (:reasoning parsed)))
      (is (= 0.9 (:confidence parsed)))
      (is (vector? (:risks parsed)))
      (is (vector? (:benefits parsed))))))

(deftest test-orchestrator-creation
  (testing "Create decision orchestrator"
    (let [kg (kg/create-in-memory-graph {:name "test-orchestrator"})
          orchestrator (mh/create-orchestrator mock-llm-provider kg)]
      (is (instance? agent.multi_head.DecisionOrchestrator orchestrator))
      (is (seq (:heads orchestrator)))
      (is (= 5 (count (:heads orchestrator)))))))

(deftest test-head-management
  (let [kg (kg/create-in-memory-graph {:name "test-management"})
        orchestrator (mh/create-orchestrator mock-llm-provider kg)]
    
    (testing "List heads"
      (let [heads (mh/list-heads orchestrator)]
        (is (seq heads))
        (is (every? :id heads))
        (is (every? :name heads))
        (is (every? :specialty heads))))
    
    (testing "Add and remove heads"
      (let [new-head (mh/->DecisionHead :custom "Custom" "custom" mock-llm-provider kg)
            with-head (mh/add-head orchestrator new-head)
            without-head (mh/remove-head with-head :custom)]
        (is (> (count (:heads with-head)) (count (:heads orchestrator))))
        (is (= (count (:heads without-head)) (count (:heads orchestrator))))))))

(deftest test-conflict-resolution
  (let [kg (kg/create-in-memory-graph {:name "test-conflict"})
        orchestrator (mh/create-orchestrator mock-llm-provider kg)]
    
    (testing "Unanimous decision"
      (let [evaluations [{:choice "A" :confidence 0.8}
                         {:choice "A" :confidence 0.9}
                         {:choice "A" :confidence 0.7}]]
        (is (= "A" (mh/resolve-conflict orchestrator evaluations)))))
    
    (testing "Tie with confidence weighting"
      (let [evaluations [{:choice "A" :confidence 0.9}
                         {:choice "B" :confidence 0.8}
                         {:choice "B" :confidence 0.7}]]
        (is (= "B" (mh/resolve-conflict orchestrator evaluations)))))
    
    (testing "Empty evaluations"
      (is (nil? (mh/resolve-conflict orchestrator []))))))

(deftest test-consensus-calculation
  (let [kg (kg/create-in-memory-graph {:name "test-consensus"})
        orchestrator (mh/create-orchestrator mock-llm-provider kg)]
    
    (testing "Full consensus"
      (let [evaluations [{:choice "A"} {:choice "A"} {:choice "A"}]]
        (is (= 1.0 (mh/consensus-level orchestrator evaluations)))))
    
    (testing "Partial consensus"
      (let [evaluations [{:choice "A"} {:choice "B"} {:choice "A"}]]
        (is (= 0.5 (mh/consensus-level orchestrator evaluations)))))
    
    (testing "No consensus"
      (let [evaluations [{:choice "A"} {:choice "B"} {:choice "C"}]]
        (is (= (/ 1 3) (mh/consensus-level orchestrator evaluations)))))
    
    (testing "Empty evaluations"
      (is (= 0.0 (mh/consensus-level orchestrator []))))))

(deftest test-protocol-implementation
  (let [kg (kg/create-in-memory-graph {:name "test-protocol"})
        head (mh/->DecisionHead :test "Test" "testing" mock-llm-provider kg)
        orchestrator (mh/create-orchestrator mock-llm-provider kg)]
    
    (testing "IDecisionHead protocol"
      (is (satisfies? mh/IDecisionHead head))
      
      (testing "evaluate method"
        (let [eval (mh/evaluate head "Test context" ["Option 1" "Option 2"])]
          (is (:head-id eval))
          (is (:head-name eval))
          (is (:choice eval))))
      
      (testing "confidence method"
        (is (= 0.8 (mh/confidence head {:confidence 0.8})))
        (is (= 0.5 (mh/confidence head {}))))  ; Default
    
    (testing "IDecisionOrchestrator protocol"
      (is (satisfies? mh/IDecisionOrchestrator orchestrator))
      
      (testing "make-decision method"
        (let [result (mh/make-decision orchestrator "Test" ["A" "B"])]
          (is (:decision result))
          (is (:evaluations result))
          (is (:consensus result))
          (is (:context result)))))))

(deftest test-standard-heads-creation
  (testing "Create standard heads"
    (let [kg (kg/create-in-memory-graph {:name "test-standard"})
          heads (mh/create-standard-heads mock-llm-provider kg)]
      (is (seq heads))
      (is (= 5 (count heads)))
      (is (every? #(satisfies? mh/IDecisionHead %) heads))
      
      ;; Check specific heads exist
      (let [head-ids (set (map :id heads))]
        (is (contains? head-ids :analytical))
        (is (contains? head-ids :creative))
        (is (contains? head-ids :practical))
        (is (contains? head-ids :ethical))
        (is (contains? head-ids :strategic))))))

(run-tests 'agent.test-multi-head)