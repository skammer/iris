(ns agent.chat.streaming-test
  (:require
   [agent.chat.streaming :as streaming]
   [clojure.test :refer [deftest is]])
  (:import
   (java.util.concurrent Executors)))

(defn- eventually
  [pred]
  (loop [remaining 20]
    (if (pred)
      true
      (when (pos? remaining)
        (Thread/sleep 25)
        (recur (dec remaining))))))

(deftest stream-delta-flusher-uses-scheduler-test
  (let [events (atom [])
        scheduler (Executors/newSingleThreadScheduledExecutor)
        flusher (streaming/stream-delta-flusher #(swap! events conj %) scheduler)
        event {:event-type :message-update
               :payload {:delta "a"}}]
    (try
      ((:emit! flusher) event)
      ((:emit! flusher) (assoc-in event [:payload :delta] "b"))
      (is (eventually #(= 1 (count @events))))
      (is (= "ab" (get-in @events [0 :payload :delta])))
      (finally
        (.shutdownNow scheduler)))))
