(ns agent.runtime.child-test
  (:require
   [agent.core :as core]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.core :as runtime]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-child-" ".db")))

(defn wait-until
  [f timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [value (f)]
        (cond
          value value
          (< deadline (System/currentTimeMillis)) nil
          :else (do
                  (Thread/sleep 100)
                  (recur)))))))

(deftest child-runtime-local-process-flow-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        event-bus (core/create-event-bus)
        event-sink (core/create-event-sink store event-bus)
        runtime-service (core/create-runtime-service store event-sink)
        system {:config {:storage {:sqlite {:path path}}}
                :store store
                :event-bus event-bus
                :event-sink event-sink
                :runtime-service runtime-service
                :runner-registry (core/create-runner-registry runtime-service)}
        run (core/request-run! system {:agent-id "child-agent"
                                       :name "child-runtime"
                                       :substrate :local-process
                                       :requested-by "tester"})
        run-id (:id run)]
    (try
      (core/launch-run! system run-id)
      (is (wait-until #(when (= "running" (:status (core/get-run system run-id)))
                         (core/get-run system run-id))
                      15000))
      (let [registered-run (core/get-run system run-id)
            _ (core/enqueue-run-command! system run-id {:command-type :ping
                                                        :payload {}})
            _ (core/enqueue-run-command! system run-id {:command-type :run-task
                                                        :payload {:task "demo"
                                                                  :sleep-ms 25}})
            _ (is (wait-until #(when (empty? (core/list-run-commands system run-id {:status "pending"}))
                                 true)
                              15000))
            commands (core/list-run-commands system run-id)
            checkpoints (core/list-run-checkpoints system run-id {:limit 20})
            heartbeats (core/list-run-heartbeats system run-id {:limit 20})
            _ (core/enqueue-run-command! system run-id {:command-type :cancel
                                                        :payload {:reason "test"}})
            cancelled-run (wait-until #(when (= "cancelled" (:status (core/get-run system run-id)))
                                         (core/get-run system run-id))
                                      15000)]
        (is (= "running" (:status registered-run)))
        (is (= "child-runtime" (get-in registered-run [:runner-metadata :mode])))
        (is (number? (get-in registered-run [:runner-metadata :pid])))
        (is (some #(= "completed" (:status %)) commands))
        (is (some #(= "task" (:checkpoint-type %)) checkpoints))
        (is (>= (count heartbeats) 2))
        (is cancelled-run)
        (is (empty? (:pending-commands cancelled-run))))
      (finally
        (when (get-in (core/runner-status system run-id) [:alive])
          (core/signal-run! system run-id {:command-type :kill}))
        (io/delete-file path true)))))
