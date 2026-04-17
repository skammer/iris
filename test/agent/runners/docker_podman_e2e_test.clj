(ns agent.runners.docker-podman-e2e-test
  (:require
   [agent.core :as core]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (let [dir (io/file "tmp")]
    (.mkdirs dir)
    (.getAbsolutePath
     (java.io.File/createTempFile "clj-agent-docker-e2e-" ".db" dir))))

(defn wait-until
  [f timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [value (f)]
        (cond
          value value
          (< deadline (System/currentTimeMillis)) nil
          :else (do
                  (Thread/sleep 250)
                  (recur)))))))

(defn docker-available? []
  (try
    (zero? (:exit (sh/sh "docker" "info" "--format" "{{.ServerVersion}}")))
    (catch Exception _ false)))

(defn podman-available? []
  (try
    (zero? (:exit (sh/sh "podman" "info" "--format" "{{.Version.Version}}")))
    (catch Exception _ false)))

(defn run-engine-e2e-test [engine]
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        event-bus (core/create-event-bus)
        event-sink (core/create-event-sink store event-bus)
        runtime-service (core/create-runtime-service store event-sink)
        system {:config {:storage {:sqlite {:path path}}
                         :runners {(keyword engine) {:image "clojure:temurin-21-alpine"
                                                     :container-working-dir "/workspace"
                                                     :container-data-dir "/agent-data"
                                                     :container-home-dir "/root"
                                                     :host-working-dir "."
                                                     :share-network? false}}}
                :store store
                :event-bus event-bus
                :event-sink event-sink
                :runtime-service runtime-service
                :runner-registry (core/create-runner-registry runtime-service)}
        run (core/request-run! system {:agent-id (str engine "-child-agent")
                                       :name (str engine "-child-runtime")
                                       :substrate (keyword engine)
                                       :requested-by "tester"})
        run-id (:id run)]
    (try
      (core/launch-run! system run-id)
      (is (wait-until #(when (= "running" (:status (core/get-run system run-id)))
                         (core/get-run system run-id))
                      60000))
        (let [_ (core/enqueue-run-command! system run-id {:command-type :ping
                                                          :payload {}})
              _ (core/enqueue-run-command! system run-id {:command-type :run-task
                                                          :payload {:task engine
                                                                    :sleep-ms 25}})
              _ (is (wait-until #(when (and (some (fn [command]
                                                    (= "completed" (:status command)))
                                                  (core/list-run-commands system run-id))
                                          (some (fn [checkpoint]
                                                  (= "task" (:checkpoint-type checkpoint)))
                                                (core/list-run-checkpoints system run-id {:limit 20})))
                                   true)
                                60000))
            commands (core/list-run-commands system run-id)
            checkpoints (core/list-run-checkpoints system run-id {:limit 20})
            output-events (sqlite/list-events store {:entity-type :agent_run
                                                     :entity-id run-id
                                                     :limit 100})
            _ (core/enqueue-run-command! system run-id {:command-type :cancel
                                                        :payload {:reason "test"}})
            cancelled-run (wait-until #(when (= "cancelled" (:status (core/get-run system run-id)))
                                         (core/get-run system run-id))
                                      30000)]
        (is (some #(= "completed" (:status %)) commands))
        (is (some #(= "task" (:checkpoint-type %)) checkpoints))
        (is (some #(and (= "agent.run.output" (:event-type %))
                        (= "stdout" (get-in % [:payload :stream])))
                  output-events))
        (is cancelled-run))
      (finally
        (when (get-in (core/runner-status system run-id) [:alive])
          (core/signal-run! system run-id {:command-type :kill}))
        (io/delete-file path true)))))

(deftest docker-child-runtime-e2e-test
  (if-not (docker-available?)
    (is true)
    (run-engine-e2e-test "docker")))

(deftest podman-child-runtime-e2e-test
  (if-not (podman-available?)
    (is true)
    (run-engine-e2e-test "podman")))
