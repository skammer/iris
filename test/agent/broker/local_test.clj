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
        exact (broker/subscribe! instance "events.session-1.output")
        wildcard (broker/subscribe! instance "events.session-1.>")
        _ (broker/publish! instance {:subject "events.session-1.output"
                                     :payload {:line "hello"}})
        exact-msg (take-with-timeout (:channel exact))
        wildcard-msg (take-with-timeout (:channel wildcard))]
    (is (= "events.session-1.output" (:subject exact-msg)))
    (is (= {:line "hello"} (:payload exact-msg)))
    (is (= "events.session-1.output" (:subject wildcard-msg)))
    (is (= 1 (:published_count (broker/health-check instance))))
    (broker/unsubscribe! instance exact)
    (broker/unsubscribe! instance wildcard)))

(deftest event-routing-subjects-test
  (is (= ["events.all"]
         (broker/event->subjects {:event-type :session.created
                                  :entity-type :session
                                  :entity-id "session-1"}))))

(deftest event-routing-is-nil-safe-test
  (is (= ["events.all"] (broker/event->subjects {})))
  (is (= ["events.all"]
         (broker/event->subjects {:event-type :message-end
                                  :entity-type :session}))))

(deftest request-without-wait-does-not-leak-subscriptions-test
  (let [instance (local/create-broker)
        result (broker/request! instance
                                "tools.shell.commands"
                                {:command-type :noop}
                                {:wait? false})]
    (is (string? (:request-id result)))
    (is (= (broker/reply-subject (:request-id result)) (:reply-to result)))
    (is (= 0 (:subscription_count (broker/health-check instance))))
    (is (= 1 (:published_count (broker/health-check instance))))))

(deftest request-timeout-cleans-subscription-test
  (let [instance (local/create-broker)
        result (broker/request! instance
                                "tools.shell.commands"
                                {:command-type :noop}
                                {:timeout-ms 10})]
    (is (true? (:timed-out result)))
    (is (= 0 (:subscription_count (broker/health-check instance))))))

(deftest request-response-cleans-subscription-test
  (let [instance (local/create-broker)
        responder (broker/subscribe! instance "tools.shell.commands")
        worker (future
                 (let [message (take-with-timeout (:channel responder))]
                   (broker/publish! instance {:subject (:reply-to message)
                                              :payload {:ok true}})))
        result (broker/request! instance
                                "tools.shell.commands"
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
                                        "events.session-1.output"
                                        {:buffer-strategy :fixed
                                         :buffer-size 1
                                         :slow-client :block
                                         :block-timeout-ms 10})]
    (broker/publish! instance {:subject "events.session-1.output" :payload {:line "one"}})
    (broker/publish! instance {:subject "events.session-1.output" :payload {:line "two"}})
    (is (= {:line "one"} (:payload (take-with-timeout (:channel subscription)))))
    (is (= 1 @(:dropped-count subscription)))
    (broker/unsubscribe! instance subscription)))

(deftest replay-returns-vector-test
  (let [instance (local/create-broker {:replay-fn (fn [pattern opts]
                                                   (list {:subject pattern
                                                          :payload opts}))})
        empty-instance (local/create-broker)]
    (is (= [{:subject "events.all"
             :payload {:limit 1}}]
           (broker/replay! instance "events.all" {:limit 1})))
    (is (= [] (broker/replay! empty-instance "events.all")))))
