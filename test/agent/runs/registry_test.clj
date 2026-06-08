(ns agent.runs.registry-test
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local-broker]
   [agent.persistence.sqlite :as sqlite]
   [agent.runs.registry :as runtime]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-runtime-" ".db")))

(defn with-service! [f]
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (f path store)
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(defn ex-data* [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest runtime-service-persists-external-run-lifecycle-test
  (with-service!
   (fn [_path store]
     (let [events (atom [])
           service (runtime/create-runtime-service {:store store
                                                    :event-sink #(swap! events conj %)})
           run (runtime/request-run! service
                                     (runtime/create-run-request
                                      {:agent-id "agent-alpha"
                                       :name "alpha"
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
       (is (= "external" (:substrate run)))
       (is (nil? (:bootstrap-token run)))
       (is (nil? (:bootstrap-spec run)))
       (is (= "requested" (:status run)))
       (is (= "running" (:status registered)))
       (is (= "running" (:status (:heartbeat hydrated))))
       (is (= 1 (:sequence-no (:checkpoint hydrated))))
       (is (empty? (:pending-commands hydrated)))
       (is (= "released" (:status latest-lease)))
       (is (= "completed" (:status hydrated)))
       (is (= ["agent.run.requested"
               "agent.run.registered"
               "agent.run.heartbeat"
               "agent.run.checkpointed"
               "agent.run.command.enqueued"
               "agent.run.command.acknowledged"
               "agent.run.command.completed"
               "agent.run.completed"]
              (mapv (comp name :event-type) @events)))))))

(deftest runtime-health-counts-runs-and-pending-commands-test
  (with-service!
   (fn [_path store]
     (let [service (runtime/create-runtime-service {:store store})
           run (runtime/request-run! service
                                     (runtime/create-run-request
                                      {:agent-id "agent-beta"}))
           _ (runtime/enqueue-command! service (:id run)
                                       {:command-type :resume
                                        :payload {}})
           health (runtime/runtime-health service)]
       (is (= 1 (:run-count health)))
       (is (= 1 (:pending-command-count health)))))))

(deftest idempotent-records-and-activities-test
  (with-service!
   (fn [_path store]
     (let [events (atom [])
           service (runtime/create-runtime-service {:store store
                                                    :event-sink #(swap! events conj %)})
           request (runtime/create-run-request
                    {:idempotency-key "run-key-1"
                     :agent-id "agent-idem"})
           run-a (runtime/request-run! service request)
           run-b (runtime/request-run! service request)
           run-id (:id run-a)
           heartbeat-a (runtime/heartbeat! service run-id {:sequence-no 1
                                                           :status :running
                                                           :metrics {:phase "a"}})
           heartbeat-b (runtime/heartbeat! service run-id {:sequence-no 1
                                                           :status :running
                                                           :metrics {:phase "b"}})
           checkpoint-a (runtime/checkpoint! service run-id {:sequence-no 1
                                                             :checkpoint-type :state
                                                             :state {:phase "a"}})
           checkpoint-b (runtime/checkpoint! service run-id {:sequence-no 1
                                                             :checkpoint-type :state
                                                             :state {:phase "b"}})
           command-a (runtime/enqueue-command! service run-id {:command-type :run-task
                                                               :payload {:x 1}
                                                               :request-id "cmd-key-1"})
           command-b (runtime/enqueue-command! service run-id {:command-type :run-task
                                                               :payload {:x 2}
                                                               :request-id "cmd-key-1"})
           completed-a (runtime/complete-command! service run-id (:id command-a) :completed nil {:ok true})
           completed-b (runtime/complete-command! service run-id (:id command-a) :failed "retry-error" {:ok false})
           activity-runs (atom 0)
           activity-a (runtime/execute-activity! service
                                                 {:run-id run-id
                                                  :command-id (:id command-a)
                                                  :activity-name :test.effect
                                                  :input {:x 1}}
                                                 #(do
                                                    (swap! activity-runs inc)
                                                    {:value 42}))
           activity-b (runtime/execute-activity! service
                                                 {:run-id run-id
                                                  :command-id (:id command-a)
                                                  :activity-name :test.effect
                                                  :input {:x 1}}
                                                 #(do
                                                    (swap! activity-runs inc)
                                                    {:value 100}))]
       (is (= (:id run-a) (:id run-b)))
       (is (= "run-key-1" (:idempotency-key run-b)))
       (is (= heartbeat-a heartbeat-b))
       (is (= checkpoint-a checkpoint-b))
       (is (= (:id command-a) (:id command-b)))
       (is (= "completed" (:status completed-b)))
       (is (= (:completed-at completed-a) (:completed-at completed-b)))
       (is (= {:value 42} (:result activity-a)))
       (is (= {:value 42} (:result activity-b)))
       (is (true? (:cached? activity-b)))
       (is (= 1 @activity-runs))
       (is (= ["agent.run.requested"
               "agent.run.heartbeat"
               "agent.run.checkpointed"
               "agent.run.command.enqueued"
               "agent.run.command.completed"]
              (mapv (comp name :event-type) @events)))))))

(deftest lifecycle-transition-rules-test
  (with-service!
   (fn [_path store]
     (let [service (runtime/create-runtime-service {:store store})
           run (runtime/request-run! service
                                     (runtime/create-run-request
                                      {:agent-id "agent-life"}))
           run-id (:id run)
           _ (runtime/register-run! service run-id
                                    {:runner-metadata {:pid 1}})
           failed (runtime/transition-run! service run-id :failed
                                           {:last-error "boom"})
           illegal (ex-data* #(runtime/transition-run! service run-id :running))]
       (is (= "failed" (:status failed)))
       (is (= {:pid 1} (:runner-metadata failed)))
       (is (= "boom" (:last-error failed)))
       (is (= :illegal-run-transition (:type illegal)))))))

(deftest command-completion-validation-and-scope-test
  (with-service!
   (fn [_path store]
     (let [service (runtime/create-runtime-service {:store store})
           run-a (runtime/request-run! service
                                       (runtime/create-run-request
                                        {:agent-id "agent-a"}))
           run-b (runtime/request-run! service
                                       (runtime/create-run-request
                                        {:agent-id "agent-b"}))
           command-a (runtime/enqueue-command! service (:id run-a)
                                               {:command-type :pause})
           command-b (runtime/enqueue-command! service (:id run-b)
                                               {:command-type :pause})
           non-terminal (ex-data* #(runtime/complete-command! service
                                                              (:id run-a)
                                                              (:id command-a)
                                                              :acknowledged
                                                              nil))
           foreign (ex-data* #(runtime/acknowledge-command! service
                                                            (:id run-a)
                                                            (:id command-b)))
           completed (runtime/complete-command! service
                                                (:id run-a)
                                                (:id command-a)
                                                :completed
                                                nil)]
       (is (= :invalid-command-transition (:type non-terminal)))
       (is (= :command-not-found (:type foreign)))
       (is (= "completed" (:status completed)))))))

(deftest retry-run-carries-checkpoint-in-run-options-test
  (with-service!
   (fn [_path store]
     (let [service (runtime/create-runtime-service {:store store})
           run (runtime/request-run! service
                                     (runtime/create-run-request
                                      {:agent-id "agent-retry"
                                       :run-options {:recovery {:retry-on-stale? true
                                                               :max-attempts 2}}}))
           _ (runtime/checkpoint! service (:id run)
                                  {:sequence-no 7
                                   :checkpoint-type :state
                                   :state {:step "resume-here"}})
           replacement (runtime/retry-run! service (:id run))
           recovery (get-in replacement [:run-options :recovery])]
       (is (= 1 (:attempt recovery)))
       (is (= 7 (:checkpoint-seq recovery)))
       (is (= {:step "resume-here"} (:checkpoint-state recovery)))
       (is (= (:id run) (:previous-run-id recovery)))))))

(deftest wait-for-run-uses-broker-events-test
  (with-service!
   (fn [_path store]
     (let [broker-instance (local-broker/create-broker)
           service (runtime/create-runtime-service
                    {:store store
                     :broker broker-instance
                     :event-sink (fn [event]
                                   (doseq [message (broker/event->messages event)]
                                     (broker/publish! broker-instance message)))})
           run (runtime/request-run! service
                                     (runtime/create-run-request
                                      {:agent-id "agent-gamma"}))
           waiter (future (runtime/wait-for-run! service (:id run) {:timeout-ms 5000}))]
       (Thread/sleep 50)
       (runtime/transition-run! service (:id run) :completed)
       (is (= "completed" (:status (deref waiter 1000 nil))))))))
