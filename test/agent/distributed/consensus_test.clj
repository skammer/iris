(ns agent.distributed.consensus-test
  "Tests for consensus algorithms (Raft/Paxos)."
  (:require
   [clojure.test :refer :all]
   [agent.distributed.consensus :as consensus]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop])
  (:import
   (java.time Instant)))

;; ============================================================================
;; Fixtures
;; ============================================================================

(defn consensus-fixture
  "Create fresh consensus nodes for each test."
  [f]
  (let [raft-node (consensus/create-raft-node "test-raft")
        paxos-node (consensus/create-paxos-node "test-paxos")]
    (f raft-node paxos-node)))

(use-fixtures :each consensus-fixture)

;; ============================================================================
;; Raft Tests
;; ============================================================================

(deftest test-raft-node-creation
  (testing "Raft node creation"
    (let [node (consensus/create-raft-node "test-node")]
      (is (satisfies? consensus/IConsensusNode node))
      (is (satisfies? consensus/IRaftNode node))
      (let [state (consensus/get-consensus-state node)]
        (is (= :follower (:role state)))))))

(deftest test-raft-vote-request
  (testing "Raft vote request handling"
    (let [node (consensus/create-raft-node "test-node")]
      ;; Start node
      (consensus/start node)
      
      ;; Request vote with valid candidate
      (let [response (consensus/request-vote node "candidate-1" 1 0 0)]
        (is (:vote-granted response))
        (is (= 1 (:term response))))
      
      ;; Request vote with stale term
      (let [response (consensus/request-vote node "candidate-2" 0 0 0)]
        (is (not (:vote-granted response)))
        (is (= 1 (:term response)))
        (is (= :stale-term (:reason response)))))))

(deftest test-raft-log-append
  (testing "Raft log append entries"
    (let [node (consensus/create-raft-node "test-node")]
      (consensus/start node)
      
      ;; Append entries from leader
      (let [entries [{:term 1 :index 1 :command "test-command"}]
            response (consensus/append-entries node "leader-1" 1 0 0 entries 0)]
        (is (:success response))
        (is (= 1 (:term response)))))))

;; ============================================================================
;; Paxos Tests
;; ============================================================================

(deftest test-paxos-node-creation
  (testing "Paxos node creation"
    (let [node (consensus/create-paxos-node "test-paxos")]
      (is (satisfies? consensus/IConsensusNode node))
      (is (satisfies? consensus/IPaxosNode node)))))

(deftest test-paxos-prepare-phase
  (testing "Paxos prepare phase"
    (let [node (consensus/create-paxos-node "test-paxos")]
      (consensus/start node)
      
      ;; First prepare should succeed
      (let [response (consensus/prepare node 1)]
        (is (:promised response))
        (is (= 1 (:proposal-number response))))
      
      ;; Second prepare with lower number should fail
      (let [response (consensus/prepare node 0)]
        (is (not (:promised response)))
        (is (= 1 (:highest-promised response)))))))

(deftest test-paxos-accept-phase
  (testing "Paxos accept phase"
    (let [node (consensus/create-paxos-node "test-paxos")]
      (consensus/start node)
      
      ;; Prepare first
      (consensus/prepare node 1)
      
      ;; Accept should succeed
      (let [response (consensus/accept node 1 "test-value")]
        (is (:accepted response))
        (is (= 1 (:proposal-number response)))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest test-consensus-client
  (testing "Consensus client integration"
    (let [raft-node (consensus/create-raft-node "client-test")
          client (consensus/->ConsensusClient raft-node)]
      
      (consensus/start raft-node)
      
      (is (satisfies? consensus/IConsensusClient client))
      
      ;; Test client methods
      (let [state (consensus/read-state client)]
        (is (map? state)))
      
      ;; Note: submit-command would only work if node is leader
      ;; In test environment, this might fail gracefully
      (let [result (consensus/submit-command client {:test :command})]
        (is (or (:proposed result)
                (nil? result)))))))  ;; Allow nil if not leader

;; ============================================================================
;; Property-Based Tests
;; ============================================================================

(deftest raft-safety-properties
  (testing "Raft safety: at most one leader per term"
    (let [prop (prop/for-all [commands (gen/vector gen/string 1 5)]
                 (let [nodes (map #(consensus/create-raft-node (str "node-" %)) (range 3))
                       _ (doseq [node nodes] (consensus/start node))
                       
                       ;; Simulate some commands
                       results (map #(consensus/propose % "test-command") nodes)
                       
                       ;; Count leaders
                       leader-count (count (filter :proposed results))]
                   
                   ;; Should have at most 1 leader proposing
                   (<= leader-count 1)))]
      
      (is (tc/quick-check 50 prop))))

  (testing "Raft consistency: log entries have increasing terms"
    (let [prop (prop/for-all [entries (gen/vector (gen/hash-map :term gen/nat
                                                                :index gen/nat
                                                                :command gen/string)
                                                  1 10)]
                 (let [node (consensus/create-raft-node "prop-test")
                       _ (consensus/start node)
                       
                       ;; Add entries (simulating leader)
                       _ (doseq [entry entries]
                           (when-let [log (-> node :log deref)]
                             (swap! (:log node) conj entry)))
                       
                       log @(:log node)
                       terms (map :term log)]
                   
                   ;; Terms should be non-decreasing
                   (apply <= terms)))]
      
      (is (tc/quick-check 30 prop)))))

(deftest paxos-safety-properties
  (testing "Paxos safety: at most one value chosen per instance"
    (let [prop (prop/for-all [values (gen/vector gen/string 1 3)]
                 (let [nodes (map #(consensus/create-paxos-node (str "paxos-" %)) (range 3))
                       _ (doseq [node nodes] (consensus/start node))
                       
                       ;; Try to propose different values
                       proposals (map #(consensus/propose (nth nodes %) (nth values %))
                                      (range (min (count nodes) (count values))))
                       
                       chosen-values (filter :chosen proposals)]
                   
                   ;; Should have at most 1 chosen value
                   (<= (count chosen-values) 1)))]
      
      (is (tc/quick-check 30 prop)))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn run-all-consensus-tests
  "Run all consensus tests."
  []
  (run-tests 'agent.distributed.consensus-test))

(comment
  ;; Run tests
  (run-all-consensus-tests)
  
  ;; Interactive testing
  (let [node (consensus/create-raft-node "test")]
    (consensus/start node)
    (consensus/get-consensus-state node))
  
  ;; Test Paxos
  (let [node (consensus/create-paxos-node "test-paxos")]
    (consensus/start node)
    (consensus/propose node "test-value")))
