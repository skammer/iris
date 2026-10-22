(ns benchmark-broker
  (:require [agent.broker.core :as broker]
         [agent.broker.local :as local]
         [clojure.core.async :as async]))

(defn measure [scoped?]
  (let [instance (local/create-broker)
        subscriptions (mapv #(broker/subscribe! instance
                               (if scoped? (broker/session-events-subject (str %)) (broker/all-events-subject))
                               {:buffer-size 500 :slow-client :drop-new}) (range 100))
        start (System/nanoTime)]
    (try
      (dotimes [i 500]
        (let [event {:entity-type :session :entity-id (str (mod i 100)) :event-type :message-update}]
          (doseq [message (if scoped? (broker/event->messages event) [{:subject "events.all" :payload event}])]
            (broker/publish! instance message))))
      (let [elapsed (/ (- (System/nanoTime) start) 1e6)
            deliveries (reduce + (for [sub subscriptions]
                                   (loop [n 0] (if (async/poll! (:channel sub)) (recur (inc n)) n))))]
        {:scoped? scoped? :ms elapsed :deliveries deliveries})
      (finally (doseq [sub subscriptions] (broker/unsubscribe! instance sub))))))
(dotimes [_ 2] (measure false) (measure true))
(doseq [scoped? [false true]]
  (prn (nth (sort-by :ms (repeatedly 5 #(measure scoped?))) 2)))
(shutdown-agents)
