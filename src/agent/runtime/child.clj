(ns agent.runtime.child
  "Child runtime shim for launched subagents."
  (:gen-class)
  (:require
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.control-client :as control-client]
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

(defn- heartbeat! [control state bootstrap-spec metrics]
  (let [payload {:sequence-no (next-seq! state :heartbeat-seq)
                 :status :running
                 :metrics metrics
                 :lease-id (:lease-id bootstrap-spec)}]
    (if (= :http (:type control))
      (control-client/heartbeat! (:client control) (:run-id bootstrap-spec) payload)
      (runtime/heartbeat! (:runtime-service control) (:run-id bootstrap-spec) payload))))

(defn- checkpoint! [control state bootstrap-spec checkpoint-type payload]
  (let [checkpoint {:sequence-no (next-seq! state :checkpoint-seq)
                    :checkpoint-type checkpoint-type
                    :state payload}]
    (if (= :http (:type control))
      (control-client/checkpoint! (:client control) (:run-id bootstrap-spec) checkpoint)
      (runtime/checkpoint! (:runtime-service control) (:run-id bootstrap-spec) checkpoint))))

(defn- complete-command! [control run-id command-id status error]
  (if (= :http (:type control))
    (control-client/complete-command! (:client control) run-id command-id status error)
    (runtime/complete-command! (:runtime-service control) run-id command-id status error)))

(defn- acknowledge-command! [control run-id command-id]
  (if (= :http (:type control))
    (control-client/acknowledge-command! (:client control) run-id command-id)
    (runtime/acknowledge-command! (:runtime-service control) run-id command-id)))

(defn- transition-run! [control run-id status & [opts]]
  (if (= :http (:type control))
    (control-client/transition-run! (:client control) run-id status opts)
    (runtime/transition-run! (:runtime-service control) run-id status opts)))

(defn- pending-commands [control run-id]
  (if (= :http (:type control))
    (control-client/pending-commands (:client control) run-id)
    (runtime/pending-commands (:runtime-service control) run-id)))

(defn- register-run! [control run-id registration]
  (if (= :http (:type control))
    (control-client/register-run! (:client control) run-id registration)
    (runtime/register-run! (:runtime-service control) run-id registration)))

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
  [control state bootstrap-spec command]
  (let [run-id (:run-id bootstrap-spec)
        command-id (:id command)
        command-type (keyword (:command-type command))
        payload (:payload command)]
    (log-out! "command" (name command-type) "begin" run-id command-id)
    (acknowledge-command! control run-id command-id)
    (try
      (case command-type
        :ping
        (do
          (heartbeat! control state bootstrap-spec {:ping true})
          (complete-command! control run-id command-id :completed nil))

        :checkpoint
        (do
          (checkpoint! control state bootstrap-spec
                       (keyword (or (:checkpoint-type payload) "state"))
                       (or (:state payload) payload))
          (complete-command! control run-id command-id :completed nil))

        :run-task
        (do
          (checkpoint! control state bootstrap-spec
                       :task
                       {:phase "started"
                        :payload payload})
          (log-out! "task" "started" run-id (pr-str payload))
          (when-let [sleep-ms (:sleep-ms payload)]
            (Thread/sleep (long sleep-ms)))
          (heartbeat! control state bootstrap-spec {:task true})
          (checkpoint! control state bootstrap-spec
                       :task
                       {:phase "completed"
                        :payload payload})
          (log-out! "task" "completed" run-id (pr-str payload))
          (complete-command! control run-id command-id :completed nil))

        :cancel
        (do
          (log-err! "command" "cancel" run-id command-id)
          (checkpoint! control state bootstrap-spec
                       :shutdown
                       {:reason "cancelled"})
          (complete-command! control run-id command-id :completed nil)
          (transition-run! control run-id :cancelled)
          (swap! state assoc :running? false))

        (complete-command! control run-id command-id :failed
                           (str "unsupported_command:" (name command-type))))
      (log-out! "command" (name command-type) "completed" run-id command-id)
      (catch Exception ex
        (log-err! "command" (name command-type) "failed" run-id (.getMessage ex))
        (complete-command! control run-id command-id :failed (.getMessage ex))
        (throw ex)))))

(defn- create-control
  [{:keys [sqlite-path control-url control-token]}]
  (if control-url
    {:type :http
     :client (control-client/create-client {:base-url control-url
                                            :token control-token})}
    (let [store (sqlite/create-store {:path sqlite-path
                                      :journal-mode (or (System/getenv "AGENT_SQLITE_JOURNAL_MODE")
                                                        "WAL")})]
      {:type :sqlite
       :store store
       :runtime-service (runtime/create-runtime-service
                         {:store store
                          :event-sink #(do
                                         (logging/log-system-event! %)
                                         (sqlite/log-event! store %))})})))

(defn run-child!
  [{:keys [sqlite-path run-id bootstrap-spec control-url control-token child-sqlite-path]}]
  (logging/start! {:enabled true
                   :file {:path (or (System/getenv "AGENT_LOG_FILE")
                                    "logs/clj-agent.log")}
                   :context {:service-name "clj-agent-child"
                             :run-id run-id}})
  (let [control (create-control {:sqlite-path sqlite-path
                                 :control-url control-url
                                 :control-token control-token})
        state (atom {:running? true
                     :heartbeat-seq 0
                     :checkpoint-seq (long (:checkpoint-seq bootstrap-spec 0))})
        heartbeat-interval-ms (long (:heartbeat-interval-ms bootstrap-spec 10000))
        command-poll-interval-ms (long (:command-poll-interval-ms bootstrap-spec 5000))]
    (when child-sqlite-path
      (sqlite/close-store! (sqlite/create-store {:path child-sqlite-path
                                                 :journal-mode "WAL"})))
    (register-run! control run-id
                   {:capabilities (:capabilities bootstrap-spec)
                    :network-identity (or (:network-identity bootstrap-spec)
                                          {:logical-id (str "agent://" (:agent-id bootstrap-spec)
                                                            "/" run-id)})
                    :runner-metadata {:pid (process-pid)
                                      :mode "child-runtime"
                                      :control-transport (name (:type control))
                                      :child-sqlite-path child-sqlite-path}})
    (log-out! "child-runtime" "boot" run-id (:agent-id bootstrap-spec))
    (checkpoint! control state bootstrap-spec :startup {:phase "boot"})
    (heartbeat! control state bootstrap-spec {:phase "boot"})
    (let [heartbeat-loop
          (future
            (try
              (while (:running? @state)
                (Thread/sleep heartbeat-interval-ms)
                (when (:running? @state)
                  (heartbeat! control state bootstrap-spec {:phase "idle"})))
              (catch InterruptedException _
                nil)))]
      (try
        (while (:running? @state)
          (doseq [command (pending-commands control run-id)]
            (when (:running? @state)
              (handle-command! control state bootstrap-spec command)))
          (when (:running? @state)
            (Thread/sleep command-poll-interval-ms)))
        (catch Exception ex
          (log-err! "child-runtime" "failed" run-id (.getMessage ex))
          (swap! state assoc :failed? true)
          (transition-run! control run-id :failed {:last-error (.getMessage ex)})
          (throw ex))
        (finally
          (let [complete? (and (:running? @state)
                               (not (:failed? @state)))]
          (swap! state assoc :running? false)
          (future-cancel heartbeat-loop)
          (when complete?
            (transition-run! control run-id :completed))
          (log-out! "child-runtime" "exit" run-id)))))))

(defn -main
  [& _args]
  (let [control-url (System/getenv "AGENT_CONTROL_URL")
        sqlite-path (or (System/getenv "AGENT_SQLITE_PATH")
                        (when-not control-url
                          (throw (ex-info "AGENT_SQLITE_PATH missing" {:type :missing-sqlite-path}))))
        run-id (or (System/getenv "AGENT_RUN_ID")
                   (throw (ex-info "AGENT_RUN_ID missing" {:type :missing-run-id})))
        bootstrap-spec (parse-bootstrap-spec (System/getenv "AGENT_BOOTSTRAP_SPEC"))]
    (run-child! {:sqlite-path sqlite-path
                 :run-id run-id
                 :bootstrap-spec bootstrap-spec
                 :control-url control-url
                 :control-token (System/getenv "AGENT_BOOTSTRAP_TOKEN")
                 :child-sqlite-path (System/getenv "AGENT_CHILD_SQLITE_PATH")})))
