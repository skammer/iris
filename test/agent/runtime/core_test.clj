(ns agent.runtime.core-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.core :as runtime]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-runtime-" ".db")))

(deftest runtime-service-persists-run-lifecycle-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        service (runtime/create-runtime-service {:store store
                                                 :event-sink #(swap! events conj %)})
        run (runtime/request-run! service
                                  (runtime/create-run-request
                                   {:agent-id "agent-alpha"
                                    :name "alpha"
                                    :substrate :local-process
                                    :capabilities [:chat :tools]
                                    :requested-by "tester"}))
        run-id (:id run)
        lease-id (get-in run [:lease :id])
        registered (runtime/register-run! service run-id
                                          {:capabilities [:chat :tools :checkpointing]
                                           :network-identity {:logical-id "agent://alpha"}
                                           :runner-metadata {:pid 42}})
        _ (runtime/heartbeat! service run-id
                              {:sequence-no 1
                               :status :running
                               :metrics {:cpu 0.1}
                               :lease-id lease-id})
        _ (runtime/checkpoint! service run-id
                               {:sequence-no 1
                                :checkpoint-type :state
                                :state {:step "planning"}})
        command (runtime/enqueue-command! service run-id
                                          {:command-type :pause
                                           :payload {:reason "operator"}})
        _ (runtime/acknowledge-command! service run-id (:id command))
        _ (runtime/complete-command! service run-id (:id command) :completed nil)
        _ (runtime/transition-run! service run-id :completed)
        hydrated (runtime/get-run service run-id)
        latest-lease (sqlite/latest-agent-run-lease store run-id)]
    (is (= "agent-alpha" (:agent-id run)))
    (is (= "requested" (:status run)))
    (is (= run-id (get-in run [:bootstrap-spec :run-id])))
    (is (= "running" (:status registered)))
    (is (= "running" (:status (:heartbeat hydrated))))
    (is (= 1 (:sequence-no (:checkpoint hydrated))))
    (is (empty? (:pending-commands hydrated)))
    (is (= "released" (:status latest-lease)))
    (is (= "completed" (:status (sqlite/get-agent-run store run-id))))
    (is (= ["agent.run.requested"
            "agent.run.registered"
            "agent.run.heartbeat"
            "agent.run.checkpointed"
            "agent.run.command.enqueued"
            "agent.run.command.acknowledged"
            "agent.run.command.completed"
            "agent.run.completed"]
           (mapv (comp name :event-type) @events)))
    (io/delete-file path true)))

(deftest runtime-health-counts-runs-and-pending-commands-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        service (runtime/create-runtime-service {:store store})
        run (runtime/request-run! service
                                  (runtime/create-run-request
                                   {:agent-id "agent-beta"
                                    :substrate :bubblewrap}))
        _ (runtime/enqueue-command! service (:id run)
                                    {:command-type :resume
                                     :payload {}})
        health (runtime/runtime-health service)]
    (is (= 1 (:run-count health)))
    (is (= 1 (:pending-command-count health)))
    (io/delete-file path true)))
