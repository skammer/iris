(ns agent.orchestrator-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.orchestrator :as orchestrator]
   [clojure.core.async :as async]
   [clojure.test :refer :all]))

(defrecord TestProvider []
  llm-core/ILLMProvider
  (complete [_ _ _] "test-response")
  (stream [_ _ _]
    (let [ch (async/chan)]
      (async/close! ch)
      ch))
  (embed [_ _ _] [0.1])
  (list-models [_] [])
  (get-capabilities [_ _] {})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0}))

(deftest orchestrator-agent-and-channel-flow-test
  (let [runtime (orchestrator/create-orchestrator)
        llm (->TestProvider)
        parent (orchestrator/spawn-agent! runtime {:name "Parent" :role "orchestrator"})
        child (orchestrator/spawn-agent! runtime {:name "Child" :role "worker" :parent-id (:id parent)})
        direct (orchestrator/send-agent-message! runtime llm (:id child) {:content "hello"})
        channel (orchestrator/create-channel! runtime {:name "coord" :participants [(:id parent) (:id child)]})
        _ (orchestrator/post-channel-message! runtime (:id channel) {:sender-id (:id parent) :content "do task"})
        consumed (orchestrator/consume-agent-inbox! runtime llm (:id child))
        channel-messages (orchestrator/list-channel-messages runtime (:id channel))]
    (is (= 2 (count (orchestrator/list-agents runtime))))
    (is (= "test-response" (get-in direct [:response :content])))
    (is (= 1 (count channel-messages)))
    (is (= 1 (:consumed consumed)))
    (is (= "test-response" (get-in consumed [:response :content])))))
