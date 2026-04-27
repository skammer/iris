(ns agent.runtime.child-test
  (:require
   [agent.system :as system]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.core :as runtime]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-child-" ".db")))

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

(deftest child-runtime-local-unsandboxed-flow-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        event-bus (system/create-event-bus)
        event-sink (system/create-event-sink store event-bus)
        runtime-service (system/create-runtime-service store event-sink)
        system {:config {:storage {:sqlite {:path path}}}
                :store store
                :event-bus event-bus
                :event-sink event-sink
                :runtime-service runtime-service
                :runner-registry (system/create-runner-registry runtime-service)}
        run (system/request-run! system {:agent-id "child-agent"
                                       :name "child-runtime"
                                       :substrate :local-unsandboxed
                                       :requested-by "tester"})
        run-id (:id run)]
    (try
      (system/launch-run! system run-id)
      (is (wait-until #(when (= "running" (:status (system/get-run system run-id)))
                         (system/get-run system run-id))
                      15000))
        (let [registered-run (system/get-run system run-id)
            _ (system/enqueue-run-command! system run-id {:command-type :ping
                                                        :payload {}})
            _ (system/enqueue-run-command! system run-id {:command-type :run-task
                                                        :payload {:task "demo"
                                                                  :sleep-ms 25}})
            _ (is (wait-until #(when (and (some (fn [command]
                                                  (= "completed" (:status command)))
                                                (system/list-run-commands system run-id))
                                        (some (fn [checkpoint]
                                                (= "task" (:checkpoint-type checkpoint)))
                                              (system/list-run-checkpoints system run-id {:limit 20})))
                                 true)
                              15000))
            commands (system/list-run-commands system run-id)
            checkpoints (system/list-run-checkpoints system run-id {:limit 20})
            heartbeats (system/list-run-heartbeats system run-id {:limit 20})
            output-events (sqlite/list-events store {:entity-type :agent_run
                                                     :entity-id run-id
                                                     :limit 50})
            _ (system/enqueue-run-command! system run-id {:command-type :cancel
                                                        :payload {:reason "test"}})
            cancelled-run (wait-until #(when (= "cancelled" (:status (system/get-run system run-id)))
                                         (system/get-run system run-id))
                                      15000)]
        (is (= "running" (:status registered-run)))
        (is (= "child-runtime" (get-in registered-run [:runner-metadata :mode])))
        (is (number? (get-in registered-run [:runner-metadata :pid])))
        (is (some #(= "completed" (:status %)) commands))
        (is (some #(= "task" (:checkpoint-type %)) checkpoints))
        (is (>= (count heartbeats) 2))
        (is (some #(= "agent.run.output" (:event-type %)) output-events))
        (is cancelled-run)
        (is (empty? (:pending-commands cancelled-run))))
      (finally
        (when (get-in (system/runner-status system run-id) [:alive])
          (system/signal-run! system run-id {:command-type :kill}))
        (io/delete-file path true)))))
