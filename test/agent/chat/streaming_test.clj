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

(deftest stream-delta-flusher-falls-back-when-scheduler-stopped-test
  (let [events (atom [])
        scheduler (Executors/newSingleThreadScheduledExecutor)
        flusher (streaming/stream-delta-flusher #(swap! events conj %) scheduler)
        event {:event-type :message-update
               :payload {:delta "a"}}]
    (.shutdownNow scheduler)
    ((:emit! flusher) event)
    ((:emit! flusher) (assoc-in event [:payload :delta] "b"))
    (is (eventually #(= 1 (count @events))))
    (is (= "ab" (get-in @events [0 :payload :delta])))))

(deftest stream-delta-flusher-coalesces-thinking-separately-test
  (let [events (atom [])
        scheduler (Executors/newSingleThreadScheduledExecutor)
        flusher (streaming/stream-delta-flusher #(swap! events conj %) scheduler)
        thinking {:event-type :message-update
                  :payload {:thinking-delta "a"}}
        content {:event-type :message-update
                 :payload {:delta "c"}}]
    (try
      ((:emit! flusher) thinking)
      ((:emit! flusher) (assoc-in thinking [:payload :thinking-delta] "b"))
      ((:emit! flusher) content)
      ((:flush! flusher))
      (is (= ["ab" nil]
             (mapv #(get-in % [:payload :thinking-delta]) @events)))
      (is (= [nil "c"]
             (mapv #(get-in % [:payload :delta]) @events)))
      (finally
        (.shutdownNow scheduler)))))
