(ns test.examples.consensus-demo-test
  "Test for consensus demo example"
  (:require
   [clojure.test :refer :all]
   [examples.consensus-demo :as demo]))

(deftest test-example-structure
  (testing "Example file loads successfully"
    (is (some? demo/create-raft-cluster))
    (is (fn? demo/create-raft-cluster))))

(deftest test-raft-cluster-creation
  (testing "Raft cluster creation with valid node count"
    (let [cluster (demo/create-raft-cluster 3)]
      (is (vector? cluster))
      (is (= 3 (count cluster)))
      (is (every? #(contains? % :node-id) cluster)))))

(deftest test-paxos-consensus
  (testing "Paxos consensus basic functionality"
    (let [paxos-nodes (demo/create-paxos-nodes 3)]
      (is (vector? paxos-nodes))
      (is (= 3 (count paxos-nodes))))))

(deftest test-fault-tolerance-scenario
  (testing "Fault tolerance scenario setup"
    (let [scenario (demo/create-fault-tolerance-scenario)]
      (is (map? scenario))
      (is (contains? scenario :nodes))
      (is (contains? scenario :failures)))))

(comment
  ;; Run tests
  (run-tests 'test.examples.consensus-demo-test)
  
  ;; Manual testing
  (demo/create-raft-cluster 3)
  (demo/create-paxos-nodes 3)
  (demo/create-fault-tolerance-scenario))