(ns agent.telegram.streaming-test
  (:require
   [agent.telegram.streaming :as streaming]
   [clojure.test :refer [deftest is]]))

(def ^:private make-scheduler #'streaming/make-flush-scheduler)

(defn- with-fast-scheduler* [flush-ms keepalive-ms f]
  (with-redefs-fn {#'streaming/stream-flush-ms flush-ms
                   #'streaming/draft-keepalive-ms keepalive-ms
                   #'streaming/scheduler-tick-ms 20}
    f))

(deftest first-request-flushes-immediately
  (with-fast-scheduler* 200 100000
    (fn []
      (let [flushes (atom 0)
            {:keys [request! stop!]} (make-scheduler (Object.) (constantly true)
                                                     #(swap! flushes inc))]
        (try
          (request!)
          (is (= 1 @flushes) "window starts elapsed, first content paints now")
          (finally (stop!)))))))

(deftest trailing-flush-fires-without-further-requests
  (with-fast-scheduler* 200 100000
    (fn []
      (let [flushes (atom 0)
            {:keys [request! stop!]} (make-scheduler (Object.) (constantly true)
                                                     #(swap! flushes inc))]
        (try
          (request!) ;; immediate
          (request!) ;; inside window: deferred to the worker
          (is (= 1 @flushes))
          (Thread/sleep 400)
          (is (= 2 @flushes)
              "pending content is delivered when the window closes, not on the next delta")
          (finally (stop!)))))))

(deftest steady-cadence-under-rapid-requests
  (with-fast-scheduler* 150 100000
    (fn []
      (let [flushes (atom 0)
            {:keys [request! stop!]} (make-scheduler (Object.) (constantly true)
                                                     #(swap! flushes inc))]
        (try
          (dotimes [_ 50]
            (request!)
            (Thread/sleep 10))
          (Thread/sleep 250)
          (is (<= 2 @flushes 7)
              "50 rapid requests over ~500ms collapse to the throttled cadence")
          (finally (stop!)))))))

(deftest keepalive-reflushes-unchanged-content
  (with-fast-scheduler* 50 300
    (fn []
      (let [flushes (atom 0)
            {:keys [request! stop!]} (make-scheduler (Object.) (constantly true)
                                                     #(swap! flushes inc))]
        (try
          (request!)
          (is (= 1 @flushes))
          (Thread/sleep 600)
          (is (>= @flushes 2)
              "silent gaps re-send the draft before the preview TTL")
          (finally (stop!)))))))

(deftest keepalive-skipped-without-content
  (with-fast-scheduler* 50 200
    (fn []
      (let [flushes (atom 0)
            {:keys [request! stop!]} (make-scheduler (Object.) (constantly false)
                                                     #(swap! flushes inc))]
        (try
          (request!)
          (Thread/sleep 500)
          (is (= 1 @flushes))
          (finally (stop!)))))))

(deftest stop-halts-worker
  (with-fast-scheduler* 50 150
    (fn []
      (let [flushes (atom 0)
            {:keys [request! stop!]} (make-scheduler (Object.) (constantly true)
                                                     #(swap! flushes inc))]
        (request!)
        (stop!)
        (Thread/sleep 400)
        (is (= 1 @flushes) "no trailing or keepalive flushes after stop")))))

(deftest reset-restores-immediate-flush
  (with-fast-scheduler* 100000 1000000
    (fn []
      (let [flushes (atom 0)
            {:keys [request! reset! stop!]} (make-scheduler (Object.) (constantly true)
                                                            #(swap! flushes inc))]
        (try
          (request!)
          (is (= 1 @flushes))
          (request!)
          (is (= 1 @flushes) "second request is inside the huge window")
          (reset!)
          (request!)
          (is (= 2 @flushes) "reset reopens the window for the next turn segment")
          (finally (stop!)))))))
