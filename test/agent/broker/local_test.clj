(ns agent.broker.local-test
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]))

(defn- take-with-timeout [ch]
  (let [timeout-ch (async/timeout 1000)
        [value port] (async/alts!! [ch timeout-ch])]
    (when-not (= port timeout-ch)
      value)))

(deftest local-broker-exact-and-wildcard-subscriptions-test
  (let [instance (local/create-broker)
        exact (broker/subscribe! instance "runs.run-1.output")
        wildcard (broker/subscribe! instance "runs.run-1.>")
        _ (broker/publish! instance {:subject "runs.run-1.output"
                                     :payload {:line "hello"}})
        exact-msg (take-with-timeout (:channel exact))
        wildcard-msg (take-with-timeout (:channel wildcard))]
    (is (= "runs.run-1.output" (:subject exact-msg)))
    (is (= {:line "hello"} (:payload exact-msg)))
    (is (= "runs.run-1.output" (:subject wildcard-msg)))
    (is (= 1 (:published_count (broker/health-check instance))))
    (broker/unsubscribe! instance exact)
    (broker/unsubscribe! instance wildcard)))

(deftest event-routing-subjects-test
  (let [event {:event-type :agent.run.command.completed
               :entity-type :agent_run
               :entity-id "run-42"}
        subjects (set (broker/event->subjects event))]
    (is (contains? subjects "events.all"))
    (is (contains? subjects "runs.run-42.events"))
    (is (contains? subjects "runs.run-42.commands"))))

(deftest event-routing-is-nil-safe-test
  (is (= ["events.all"] (broker/event->subjects {})))
  (is (= ["events.all"]
         (broker/event->subjects {:event-type :agent.run.output
                                  :entity-type :agent_run})))
  (is (= #{"events.all" "runs.run-42.events"}
         (set (broker/event->subjects {:event-type :other.agent.run.command.completed
                                       :entity-type :agent_run
                                       :entity-id "run-42"})))))

(deftest run-event-routing-subjects-test
  (let [base {:entity-type :agent_run
              :entity-id "run-42"}]
    (is (= #{"events.all" "runs.run-42.events" "runs.run-42.output"}
           (set (broker/event->subjects (assoc base :event-type :agent.run.output)))))
    (is (= #{"events.all" "runs.run-42.events" "runs.run-42.heartbeats"}
           (set (broker/event->subjects (assoc base :event-type :agent.run.heartbeat)))))
    (is (= #{"events.all" "runs.run-42.events" "runs.run-42.checkpoints"}
           (set (broker/event->subjects (assoc base :event-type :agent.run.checkpointed)))))
    (is (= #{"events.all" "runs.run-42.events" "runs.run-42.commands"}
           (set (broker/event->subjects (assoc base :event-type :agent.run.command.completed)))))))

(deftest request-without-wait-does-not-leak-subscriptions-test
  (let [instance (local/create-broker)
        result (broker/request! instance
                                "runs.run-1.commands"
                                {:command-type :noop}
                                {:wait? false})]
    (is (string? (:request-id result)))
    (is (= (broker/reply-subject (:request-id result)) (:reply-to result)))
    (is (= 0 (:subscription_count (broker/health-check instance))))
    (is (= 1 (:published_count (broker/health-check instance))))))

(deftest request-timeout-cleans-subscription-test
  (let [instance (local/create-broker)
        result (broker/request! instance
                                "runs.run-1.commands"
                                {:command-type :noop}
                                {:timeout-ms 10})]
    (is (true? (:timed-out result)))
    (is (= 0 (:subscription_count (broker/health-check instance))))))

(deftest request-response-cleans-subscription-test
  (let [instance (local/create-broker)
        responder (broker/subscribe! instance "runs.run-1.commands")
        worker (future
                 (let [message (take-with-timeout (:channel responder))]
                   (broker/publish! instance {:subject (:reply-to message)
                                              :payload {:ok true}})))
        result (broker/request! instance
                                "runs.run-1.commands"
                                {:command-type :noop}
                                {:timeout-ms 1000})]
    @worker
    (broker/unsubscribe! instance responder)
    (is (= {:subject (:reply-to result)
            :payload {:ok true}}
           (:response result)))
    (is (= 0 (:subscription_count (broker/health-check instance))))))

(deftest block-slow-client-times-out-test
  (let [instance (local/create-broker)
        subscription (broker/subscribe! instance
                                        "runs.run-1.output"
                                        {:buffer-strategy :fixed
                                         :buffer-size 1
                                         :slow-client :block
                                         :block-timeout-ms 10})]
    (broker/publish! instance {:subject "runs.run-1.output" :payload {:line "one"}})
    (broker/publish! instance {:subject "runs.run-1.output" :payload {:line "two"}})
    (is (= {:line "one"} (:payload (take-with-timeout (:channel subscription)))))
    (is (= 1 @(:dropped-count subscription)))
    (broker/unsubscribe! instance subscription)))

(deftest replay-returns-vector-test
  (let [instance (local/create-broker {:replay-fn (fn [pattern opts]
                                                   (list {:subject pattern
                                                          :payload opts}))})
        empty-instance (local/create-broker)]
    (is (= [{:subject "runs.run-1.events"
             :payload {:limit 1}}]
           (broker/replay! instance "runs.run-1.events" {:limit 1})))
    (is (= [] (broker/replay! empty-instance "runs.run-1.events")))))
