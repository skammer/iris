(ns agent.runtime.child-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runs.service :as runs]
   [agent.system.events :as events]
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
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        runtime-service (runs/create-runtime-service store event-sink)
        system {:config {:storage {:sqlite {:path path}}}
                :store store
                :event-bus event-bus
                :event-sink event-sink
                :runtime-service runtime-service
                :runner-registry (runs/create-runner-registry runtime-service)}
        run (runs/request-run! system {:agent-id "child-agent"
                                       :name "child-runtime"
                                       :substrate :local-unsandboxed
                                       :requested-by "tester"})
        run-id (:id run)]
    (try
      (runs/launch-run! system run-id)
      (is (wait-until #(when (= "running" (:status (runs/get-run system run-id)))
                         (runs/get-run system run-id))
                      15000))
        (let [registered-run (runs/get-run system run-id)
            _ (runs/enqueue-run-command! system run-id {:command-type :ping
                                                        :payload {}})
            _ (runs/enqueue-run-command! system run-id {:command-type :run-task
                                                        :payload {:task "demo"
                                                                  :sleep-ms 25}})
            _ (is (wait-until #(when (and (some (fn [command]
                                                  (= "completed" (:status command)))
                                                (runs/list-run-commands system run-id))
                                        (some (fn [checkpoint]
                                                (= "task" (:checkpoint-type checkpoint)))
                                              (runs/list-run-checkpoints system run-id {:limit 20})))
                                 true)
                              15000))
            commands (runs/list-run-commands system run-id)
            checkpoints (runs/list-run-checkpoints system run-id {:limit 20})
            heartbeats (runs/list-run-heartbeats system run-id {:limit 20})
            output-events (sqlite/list-events store {:entity-type :agent_run
                                                     :entity-id run-id
                                                     :limit 50})
            _ (runs/enqueue-run-command! system run-id {:command-type :cancel
                                                        :payload {:reason "test"}})
            cancelled-run (wait-until #(when (= "cancelled" (:status (runs/get-run system run-id)))
                                         (runs/get-run system run-id))
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
        (when (get-in (runs/runner-status system run-id) [:alive])
          (runs/signal-run! system run-id {:command-type :kill}))
        (io/delete-file path true)))))
