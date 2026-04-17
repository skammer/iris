(ns agent.broker.local-test
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local]
   [clojure.core.async :as async]
   [clojure.test :refer :all]))

(deftest local-broker-exact-and-wildcard-subscriptions-test
  (let [instance (local/create-broker)
        exact (broker/subscribe! instance "runs.run-1.output")
        wildcard (broker/subscribe! instance "runs.run-1.>")
        _ (broker/publish! instance {:subject "runs.run-1.output"
                                     :payload {:line "hello"}})
        exact-msg (async/<!! (:channel exact))
        wildcard-msg (async/<!! (:channel wildcard))]
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
