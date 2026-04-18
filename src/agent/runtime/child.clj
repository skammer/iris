(ns agent.runtime.child
  "Child runtime shim for launched subagents."
  (:gen-class)
  (:require
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.core :as runtime]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.lang ProcessHandle Thread)))

(defn current-child-command
  []
  [(str (io/file (System/getProperty "java.home") "bin" "java"))
   "-cp"
   (System/getProperty "java.class.path")
   "clojure.main"
   "-m"
   "agent.runtime.child"])

(defn current-container-child-command
  []
  ["clojure"
   "-M"
   "-m"
   "agent.runtime.child"])

(defn- parse-bootstrap-spec [value]
  (when-not value
    (throw (ex-info "AGENT_BOOTSTRAP_SPEC missing" {:type :missing-bootstrap-spec})))
  (edn/read-string value))

(defn- process-pid []
  (.pid (ProcessHandle/current)))

(defn- next-seq! [state key]
  (let [value (inc (long (get @state key 0)))]
    (swap! state assoc key value)
    value))

(defn- heartbeat! [runtime-service state bootstrap-spec metrics]
  (runtime/heartbeat! runtime-service
                      (:run-id bootstrap-spec)
                      {:sequence-no (next-seq! state :heartbeat-seq)
                       :status :running
                       :metrics metrics
                       :lease-id (:lease-id bootstrap-spec)}))

(defn- checkpoint! [runtime-service state bootstrap-spec checkpoint-type payload]
  (runtime/checkpoint! runtime-service
                       (:run-id bootstrap-spec)
                       {:sequence-no (next-seq! state :checkpoint-seq)
                        :checkpoint-type checkpoint-type
                        :state payload}))

(defn- complete-command! [runtime-service run-id command-id status error]
  (runtime/complete-command! runtime-service run-id command-id status error))

(defn- log-out! [& parts]
  (logging/log! :agent.child/stdout
                {:message (str/join " " (map str parts))})
  (binding [*out* *out*]
    (apply println parts)
    (flush)))

(defn- log-err! [& parts]
  (logging/log! :agent.child/stderr
                {:message (str/join " " (map str parts))})
  (binding [*out* *err*]
    (apply println parts)
    (flush)))

(defn- handle-command!
  [runtime-service state bootstrap-spec command]
  (let [run-id (:run-id bootstrap-spec)
        command-id (:id command)
        command-type (keyword (:command-type command))
        payload (:payload command)]
    (log-out! "command" (name command-type) "begin" run-id command-id)
    (runtime/acknowledge-command! runtime-service run-id command-id)
    (try
      (case command-type
        :ping
        (do
          (heartbeat! runtime-service state bootstrap-spec {:ping true})
          (complete-command! runtime-service run-id command-id :completed nil))

        :checkpoint
        (do
          (checkpoint! runtime-service state bootstrap-spec
                       (keyword (or (:checkpoint-type payload) "state"))
                       (or (:state payload) payload))
          (complete-command! runtime-service run-id command-id :completed nil))

        :run-task
        (do
          (checkpoint! runtime-service state bootstrap-spec
                       :task
                       {:phase "started"
                        :payload payload})
          (log-out! "task" "started" run-id (pr-str payload))
          (when-let [sleep-ms (:sleep-ms payload)]
            (Thread/sleep (long sleep-ms)))
          (heartbeat! runtime-service state bootstrap-spec {:task true})
          (checkpoint! runtime-service state bootstrap-spec
                       :task
                       {:phase "completed"
                        :payload payload})
          (log-out! "task" "completed" run-id (pr-str payload))
          (complete-command! runtime-service run-id command-id :completed nil))

        :cancel
        (do
          (log-err! "command" "cancel" run-id command-id)
          (checkpoint! runtime-service state bootstrap-spec
                       :shutdown
                       {:reason "cancelled"})
          (complete-command! runtime-service run-id command-id :completed nil)
          (runtime/transition-run! runtime-service run-id :cancelled)
          (swap! state assoc :running? false))

        (complete-command! runtime-service run-id command-id :failed
                           (str "unsupported_command:" (name command-type))))
      (log-out! "command" (name command-type) "completed" run-id command-id)
      (catch Exception ex
        (log-err! "command" (name command-type) "failed" run-id (.getMessage ex))
        (complete-command! runtime-service run-id command-id :failed (.getMessage ex))
        (throw ex)))))

(defn run-child!
  [{:keys [sqlite-path run-id bootstrap-spec]}]
  (logging/start! {:enabled true
                   :file {:path (or (System/getenv "AGENT_LOG_FILE")
                                    "logs/clj-agent.log")}
                   :context {:service-name "clj-agent-child"
                             :run-id run-id}})
  (let [store (sqlite/create-store {:path sqlite-path})
        runtime-service (runtime/create-runtime-service
                         {:store store
                          :event-sink #(do
                                         (logging/log-system-event! %)
                                         (sqlite/log-event! store %))})
        state (atom {:running? true
                     :heartbeat-seq 0
                     :checkpoint-seq (long (:checkpoint-seq bootstrap-spec 0))})
        heartbeat-interval-ms (long (:heartbeat-interval-ms bootstrap-spec 10000))
        command-poll-interval-ms (long (:command-poll-interval-ms bootstrap-spec 5000))]
    (runtime/register-run! runtime-service run-id
                           {:capabilities (:capabilities bootstrap-spec)
                            :network-identity (or (:network-identity bootstrap-spec)
                                                  {:logical-id (str "agent://" (:agent-id bootstrap-spec)
                                                                    "/" run-id)})
                            :runner-metadata {:pid (process-pid)
                                              :mode "child-runtime"}})
    (log-out! "child-runtime" "boot" run-id (:agent-id bootstrap-spec))
    (checkpoint! runtime-service state bootstrap-spec :startup {:phase "boot"})
    (heartbeat! runtime-service state bootstrap-spec {:phase "boot"})
    (let [heartbeat-loop
          (future
            (try
              (while (:running? @state)
                (Thread/sleep heartbeat-interval-ms)
                (when (:running? @state)
                  (heartbeat! runtime-service state bootstrap-spec {:phase "idle"})))
              (catch InterruptedException _
                nil)))]
      (try
        (while (:running? @state)
          (doseq [command (runtime/pending-commands runtime-service run-id)]
            (when (:running? @state)
              (handle-command! runtime-service state bootstrap-spec command)))
          (when (:running? @state)
            (Thread/sleep command-poll-interval-ms)))
        (catch Exception ex
          (log-err! "child-runtime" "failed" run-id (.getMessage ex))
          (runtime/transition-run! runtime-service run-id :failed {:last-error (.getMessage ex)})
          (throw ex))
        (finally
          (swap! state assoc :running? false)
          (future-cancel heartbeat-loop)
          (let [status (:status (runtime/get-run runtime-service run-id))]
            (when-not (#{"cancelled" "completed" "failed"} status)
              (runtime/transition-run! runtime-service run-id :completed)))
          (log-out! "child-runtime" "exit" run-id))))))

(defn -main
  [& _args]
  (let [sqlite-path (or (System/getenv "AGENT_SQLITE_PATH")
                        (throw (ex-info "AGENT_SQLITE_PATH missing" {:type :missing-sqlite-path})))
        run-id (or (System/getenv "AGENT_RUN_ID")
                   (throw (ex-info "AGENT_RUN_ID missing" {:type :missing-run-id})))
        bootstrap-spec (parse-bootstrap-spec (System/getenv "AGENT_BOOTSTRAP_SPEC"))]
    (run-child! {:sqlite-path sqlite-path
                 :run-id run-id
                 :bootstrap-spec bootstrap-spec})))
