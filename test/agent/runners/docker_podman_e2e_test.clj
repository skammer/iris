(ns agent.runners.docker-podman-e2e-test
  (:require
   [agent.system :as system]
   [agent.persistence.sqlite :as sqlite]
   [agent.runs.service :as runs]
   [agent.system.events :as events]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.test :refer [deftest is]])
  (:import
   (java.net ServerSocket)))

(defn temp-db-path []
  (let [dir (io/file "tmp")]
    (.mkdirs dir)
    (.getAbsolutePath
     (java.io.File/createTempFile "iris-docker-e2e-" ".db" dir))))

(defn wait-until
  ([f timeout-ms] (wait-until f timeout-ms 1000))
  ([f timeout-ms interval-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [value (f)]
        (cond
          value value
          (< deadline (System/currentTimeMillis)) nil
          :else (do
                  (Thread/sleep interval-ms)
                  (recur))))))))

(defn docker-available? []
  (try
    (zero? (:exit (sh/sh "docker" "info" "--format" "{{.ServerVersion}}")))
    (catch Exception _ false)))

(defn podman-available? []
  (try
    (zero? (:exit (sh/sh "podman" "info" "--format" "{{.Version.Version}}")))
    (catch Exception _ false)))

(defn free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn run-engine-e2e-test [engine]
  (let [path (temp-db-path)
        port (free-port)
        store (sqlite/create-store {:path path
                                    :journal-mode "DELETE"})
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        runtime-service (runs/create-runtime-service store event-sink)
        base-system {:config {:api {:host "0.0.0.0"
                                    :port port}
                              :storage {:sqlite {:path path
                                                 :journal-mode "DELETE"}}
                              :runners {(keyword engine) {:image "clojure:temurin-21-alpine"
                                                          :container-working-dir "/workspace"
                                                          :container-data-dir "/tmp/iris"
                                                          :container-home-dir "/root"
                                                          :host-working-dir "."
                                                          :share-network? true}}}
                     :store store
                     :event-bus event-bus
                     :event-sink event-sink
                     :runtime-service runtime-service
                     :runner-registry (runs/create-runner-registry runtime-service)}
        system (system/start-api! base-system)
        run (runs/request-run! system {:agent-id (str engine "-child-agent")
                                       :name (str engine "-child-runtime")
                                       :substrate (keyword engine)
                                       :requested-by "tester"})
        run-id (:id run)]
    (try
      (runs/launch-run! system run-id)
      ;; Docker Desktop bind-mounted SQLite can throw transient VFS write errors
      ;; if the parent polls while the child is still opening the database.
      (Thread/sleep 6000)
      (is (wait-until #(when (= "running" (:status (runs/get-run system run-id)))
                         (runs/get-run system run-id))
                      60000))
        (let [_ (runs/enqueue-run-command! system run-id {:command-type :ping
                                                          :payload {}})
              _ (runs/enqueue-run-command! system run-id {:command-type :run-task
                                                          :payload {:task engine
                                                                    :sleep-ms 25}})
              _ (is (wait-until #(when (and (some (fn [command]
                                                    (= "completed" (:status command)))
                                                  (runs/list-run-commands system run-id))
                                          (some (fn [checkpoint]
                                                  (= "task" (:checkpoint-type checkpoint)))
                                                (runs/list-run-checkpoints system run-id {:limit 20})))
                                   true)
                                60000))
            commands (runs/list-run-commands system run-id)
            checkpoints (runs/list-run-checkpoints system run-id {:limit 20})
            output-events (sqlite/list-events store {:entity-type :agent_run
                                                     :entity-id run-id
                                                     :limit 100})
            _ (runs/enqueue-run-command! system run-id {:command-type :cancel
                                                        :payload {:reason "test"}})
            cancelled-run (wait-until #(when (= "cancelled" (:status (runs/get-run system run-id)))
                                         (runs/get-run system run-id))
                                      30000)]
        (is (some #(= "completed" (:status %)) commands))
        (is (some #(= "task" (:checkpoint-type %)) checkpoints))
        (is (some #(and (= "agent.run.output" (:event-type %))
                        (= "stdout" (get-in % [:payload :stream])))
                  output-events))
        (is cancelled-run))
      (finally
        (when (get-in (runs/runner-status system run-id) [:alive])
          (runs/signal-run! system run-id {:command-type :kill}))
        (system/stop-api! system)
        (io/delete-file path true)))))

(deftest docker-child-runtime-e2e-test
  (if-not (docker-available?)
    (is true)
    (run-engine-e2e-test "docker")))

(deftest podman-child-runtime-e2e-test
  (if-not (podman-available?)
    (is true)
    (run-engine-e2e-test "podman")))
