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

(deftest stream-delta-flusher-finishes-after-in-flight-delta-test
  (let [events (atom [])
        tail-entered (promise)
        release-tail (promise)
        terminal-started (promise)
        scheduler (Executors/newSingleThreadScheduledExecutor)
        prefix "Проверенные и стабильные вариан"
        suffix "ты - Qwen3, GigaEmbeddings, BGE-M3, e5."
        flusher (streaming/stream-delta-flusher
                 (fn [event]
                   (when (= suffix (get-in event [:payload :delta]))
                     (deliver tail-entered true)
                     (deref release-tail 5000 nil))
                   (swap! events conj event))
                 scheduler)
        terminal (atom nil)]
    (try
      ((:emit! flusher) {:event-type :message-update :payload {:delta prefix}})
      ((:flush! flusher))
      ((:emit! flusher) {:event-type :message-update :payload {:delta suffix}})
      (is (deref tail-entered 2000 false))
      (reset! terminal
              (future
                (deliver terminal-started true)
                ((:emit! flusher) {:event-type :message-end
                                  :payload {:final? true :content (str prefix suffix)}})
                ((:emit! flusher) {:event-type :agent-end
                                  :payload {:stop-reason :completed}})
                :finished))
      (is (deref terminal-started 2000 false))
      (is (= :waiting (deref @terminal 100 :waiting))
          "completion must wait for a delta already being published")
      (deliver release-tail true)
      (is (= :finished (deref @terminal 2000 :timeout)))
      (is (eventually #(= 4 (count @events))))
      (is (= [:message-update :message-update :message-end :agent-end]
             (mapv :event-type @events)))
      (is (= (str prefix suffix)
             (apply str (keep #(get-in % [:payload :delta])
                              (take-while #(not= :message-end (:event-type %)) @events)))))
      (finally
        (deliver release-tail true)
        (when-let [task @terminal] (deref task 2000 nil))
        (.shutdownNow scheduler)))))
