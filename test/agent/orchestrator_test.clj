(ns agent.orchestrator-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.orchestrator :as orchestrator]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]))

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
        parent (orchestrator/spawn-agent! runtime {:name "Parent" :role "orchestrator"
                                                   :capabilities ["delegate" "route"]
                                                   :trusted-peers ["mesh-1"]
                                                   :allow-direct? true})
        child (orchestrator/spawn-agent! runtime {:name "Child" :role "worker"
                                                  :parent-id (:id parent)
                                                  :capabilities ["execute"]})
        direct (orchestrator/send-agent-message! runtime llm (:id child) {:content "hello"})
        interop-before (orchestrator/describe-agent-interop runtime (:id child))
        _ (orchestrator/register-agent-capabilities! runtime (:id child)
                                                     {:capabilities ["execute" "report"]
                                                      :allow-direct? true
                                                      :trusted-peers [(:id parent)]
                                                      :trust-policies {(:id parent)
                                                                       {:message-types ["delegate.request"]
                                                                        :routes ["direct"]
                                                                        :required-capabilities ["delegate"]}}
                                                      :interop-rate-limit-per-minute 2})
        interop-message (orchestrator/send-interop-message! runtime
                                                            (:logical-address parent)
                                                            (:id child)
                                                            {:message-type "delegate.request"
                                                             :request-id "req-1"
                                                             :delivery-mode "at-most-once"
                                                             :route :auto
                                                             :content "collect facts"})
        duplicate-message (orchestrator/send-interop-message! runtime
                                                              (:logical-address parent)
                                                              (:id child)
                                                              {:message-type "delegate.request"
                                                               :request-id "req-1"
                                                               :delivery-mode "at-most-once"
                                                               :route :auto
                                                               :content "collect facts"})
        retried-message (orchestrator/retry-interop-message! runtime
                                                             (:id parent)
                                                             (:id interop-message))
        acked-message (orchestrator/acknowledge-interop-message! runtime
                                                                (:id child)
                                                                 (:id interop-message)
                                                                 {:ack-type "completed"})
        inbound-messages (orchestrator/list-interop-messages runtime (:id child) {:direction :inbound})
        federated-peer (orchestrator/register-federated-peer! runtime
                                                              {:id "mesh-1"
                                                               :base-url "https://mesh-1.example.invalid"
                                                               :capabilities ["interop"]})
        federated-message (orchestrator/send-interop-message! runtime
                                                              (:id parent)
                                                              "federation://mesh-1/remote-agent"
                                                              {:message-type "delegate.request"
                                                               :request-id "req-fed-1"
                                                               :content "ship result"})
        denied-message (try
                         (orchestrator/send-interop-message! runtime
                                                             (:id parent)
                                                             (:id child)
                                                             {:message-type "status.push"
                                                              :content "denied"})
                         nil
                         (catch Exception e
                           (:type (ex-data e))))
        channel (orchestrator/create-channel! runtime {:name "coord" :participants [(:id parent) (:id child)]})
        _ (orchestrator/post-channel-message! runtime (:id channel) {:sender-id (:id parent) :content "do task"})
        consumed (orchestrator/consume-agent-inbox! runtime llm (:id child))
        channel-messages (orchestrator/list-channel-messages runtime (:id channel))]
    (is (= 2 (count (orchestrator/list-agents runtime))))
    (is (= (str "agent://" (:id child)) (:logical-address interop-before)))
    (is (= ["execute"] (:capabilities interop-before)))
    (is (= "direct" (:route interop-message)))
    (is (= (:id interop-message) (:id duplicate-message)))
    (is (= 2 (:delivery-count retried-message)))
    (is (= "acked" (:status acked-message)))
    (is (= "completed" (:ack-type acked-message)))
    (is (= 1 (count inbound-messages)))
    (is (= "acked" (:status (first inbound-messages))))
    (is (= "mesh-1" (:id federated-peer)))
    (is (= "federated" (:route federated-message)))
    (is (= "forward_requested" (:status federated-message)))
    (is (= :permission-denied denied-message))
    (is (= "test-response" (get-in direct [:response :content])))
    (is (= 1 (count channel-messages)))
    (is (= 3 (:consumed consumed)))
    (is (= "test-response" (get-in consumed [:response :content])))))

(deftest federated-interop-denied-for-untrusted-peer-test
  ;; Federated delivery must enforce the sender's trust of the target peer,
  ;; not bypass trust/route enforcement the way local delivery applies it.
  (let [runtime (orchestrator/create-orchestrator)
        sender (orchestrator/spawn-agent! runtime {:name "Sender"
                                                   :capabilities ["execute"]})
        _ (orchestrator/register-federated-peer! runtime
                                                 {:id "mesh-9"
                                                  :base-url "https://mesh-9.internal"})
        denied (try
                 (orchestrator/send-interop-message! runtime
                                                     (:id sender)
                                                     "federation://mesh-9/remote-agent"
                                                     {:message-type "delegate.request"
                                                      :content "leak"})
                 nil
                 (catch Exception e (:reason (ex-data e))))]
    (is (= :peer-not-trusted denied)
        "a sender that does not trust the federated peer is rejected")))

(defn- events-of-type [events event-type]
  (filter #(= event-type (:event-type %)) @events))

(deftest orchestrator-emits-drop-events-when-interop-inbox-full-test
  (let [events (atom [])
        runtime (orchestrator/create-orchestrator {:event-sink #(swap! events conj %)})
        parent (orchestrator/spawn-agent! runtime {:name "Parent"
                                                   :capabilities ["delegate"]
                                                   :allow-direct? true
                                                   :interop-rate-limit-per-minute 1000})
        child (orchestrator/spawn-agent! runtime {:name "Child"
                                                  :capabilities ["execute"]
                                                  :allow-direct? true
                                                  :interop-rate-limit-per-minute 1000})
        _ (orchestrator/register-agent-capabilities! runtime (:id child)
                                                     {:capabilities ["execute"]
                                                      :allow-direct? true
                                                      :trusted-peers [(:id parent)]
                                                      :interop-rate-limit-per-minute 1000})
        sent (doall
              (for [idx (range 70)]
                (orchestrator/send-interop-message! runtime
                                                    (:id parent)
                                                    (:id child)
                                                    {:message-type "request"
                                                     :request-id (str "req-" idx)
                                                     :content (str "msg-" idx)})))
        inbound (orchestrator/list-interop-messages runtime (:id child) {:direction :inbound})
        consumed (orchestrator/consume-agent-inbox! runtime (->TestProvider) (:id child))
        delivered-events (events-of-type events :agent.interop.message.delivered)
        dropped-events (events-of-type events :agent.interop.message.dropped)]
    (is (= 70 (count sent)))
    (is (= 70 (count inbound)))
    (is (= 64 (count delivered-events)))
    (is (= 6 (count dropped-events)))
    (is (= 64 (:consumed consumed)))
    (is (= 6 (count (filter #(= "dropped" (:status %)) inbound))))
    (is (every? #(= "agent-inbox-full" (get-in % [:payload :last-error]))
                dropped-events))))

(deftest orchestrator-emits-drop-events-when-channel-buffers-full-test
  (let [events (atom [])
        runtime (orchestrator/create-orchestrator {:event-sink #(swap! events conj %)})
        sender (orchestrator/spawn-agent! runtime {:name "Sender"})
        recipient (orchestrator/spawn-agent! runtime {:name "Recipient"})
        channel (orchestrator/create-channel! runtime {:name "coord"
                                                       :participants [(:id sender) (:id recipient)]})]
    (dotimes [idx 130]
      (orchestrator/post-channel-message! runtime
                                          (:id channel)
                                          {:sender-id (:id sender)
                                           :content (str "msg-" idx)}))
    (let [drops (events-of-type events :channel.message.dropped)
          bus-drops (filter #(= :channel-bus-full (get-in % [:payload :reason])) drops)
          inbox-drops (filter #(= :agent-inbox-full (get-in % [:payload :reason])) drops)
          posted-events (events-of-type events :channel.message.posted)
          consumed (orchestrator/consume-agent-inbox! runtime (->TestProvider) (:id recipient))]
      (is (= 130 (count posted-events)))
      (is (= 130 (count (orchestrator/list-channel-messages runtime (:id channel)))))
      (is (= 2 (count bus-drops)))
      (is (= 66 (count inbox-drops)))
      (is (= 68 (count drops)))
      (is (= 64 (:consumed consumed))))))

(deftest disabled-orchestrator-allows-reads-and-blocks-mutators-test
  (let [runtime (orchestrator/create-orchestrator {:enabled? false})]
    (is (empty? (orchestrator/list-agents runtime)))
    (is (empty? (orchestrator/list-channels runtime)))
    (is (true? (:healthy (orchestrator/health-check runtime))))
    (is (false? (:enabled (orchestrator/health-check runtime))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Orchestrator disabled"
                          (orchestrator/spawn-agent! runtime {:name "Blocked"})))
    (try
      (orchestrator/create-channel! runtime {:name "blocked"})
      (is false "create-channel should fail")
      (catch Exception e
        (is (= :orchestrator-disabled (:type (ex-data e))))
        (is (= :create-channel (:operation (ex-data e))))))))
