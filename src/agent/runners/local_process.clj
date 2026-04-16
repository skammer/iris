(ns agent.runners.local-process
  "Local subprocess runner."
  (:require
   [agent.runners.core :as runners]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(defn- now [] (str (Instant/now)))

(defn- normalize-command [runner-options]
  (let [command (:command runner-options)]
    (when-not (and (vector? command) (seq command) (every? string? command))
      (throw (ex-info "runner-options.command must be a non-empty vector of strings"
                      {:type :validation-failed
                       :runner-options runner-options})))
    command))

(defn- process-pid [^Process process]
  (.pid (.toHandle process)))

(defn- process-status [entry]
  (when entry
    (let [^Process process (:process entry)
          alive? (.isAlive process)]
      {:run-id (:run-id entry)
       :pid (:pid entry)
       :command (:command entry)
       :working-dir (:working-dir entry)
       :started-at (:started-at entry)
       :alive alive?
       :exit-code (when-not alive? (.exitValue process))})))

(defrecord LocalProcessRunner [processes on-exit]
  runners/IRunner
  (launch [_ run-spec]
    (let [runner-options (:runner-options run-spec)
          command (normalize-command runner-options)
          working-dir (or (:working-dir runner-options) ".")
          env-extra (or (:env runner-options) {})
          process-builder (ProcessBuilder. command)
          process (doto process-builder
                    (.directory (io/file working-dir))
                    (.redirectErrorStream true))
          env (.environment process-builder)]
      (.put env "AGENT_RUN_ID" (:run-id run-spec))
      (.put env "AGENT_AGENT_ID" (:agent-id run-spec))
      (.put env "AGENT_BOOTSTRAP_TOKEN" (or (:bootstrap-token run-spec) ""))
      (.put env "AGENT_BOOTSTRAP_SPEC" (pr-str (:bootstrap-spec run-spec)))
      (doseq [[k v] env-extra]
        (.put env (name k) (str v)))
      (let [started-at (now)
            process* (.start process-builder)
            entry {:run-id (:run-id run-spec)
                   :process process*
                   :pid (process-pid process*)
                   :command command
                   :working-dir (.getAbsolutePath (io/file working-dir))
                   :started-at started-at}]
        (swap! processes assoc (:run-id run-spec) entry)
        (future
          (.waitFor process*)
          (when on-exit
            (on-exit (:run-id run-spec)
                     {:exit-code (.exitValue process*)
                      :finished-at (now)})))
        (process-status entry))))
  (signal [_ run-id command]
    (if-let [entry (get @processes run-id)]
      (let [^Process process (:process entry)
            command-type (cond
                           (keyword? command) command
                           (map? command) (keyword (:command-type command))
                           (string? command) (keyword (str/lower-case command))
                           :else nil)]
        (case command-type
          (:cancel :terminate) (do (.destroy process)
                                   {:run-id run-id :signaled true :command-type (name command-type)})
          :kill (do (.destroyForcibly process)
                    {:run-id run-id :signaled true :command-type "kill"})
          {:run-id run-id :signaled false :command-type (some-> command-type name) :error "unsupported_command"}))
      {:run-id run-id :signaled false :error "unknown_run"}))
  (status [_ run-id]
    (or (process-status (get @processes run-id))
        {:run-id run-id
         :known false}))
  (stop [_ run-id]
    (if-let [entry (get @processes run-id)]
      (let [^Process process (:process entry)]
        (.destroy process)
        {:run-id run-id
         :stopped true})
      {:run-id run-id
       :stopped false
       :error "unknown_run"})))

(defn create-local-process-runner
  ([] (create-local-process-runner {}))
  ([{:keys [on-exit]}]
   (->LocalProcessRunner (atom {}) on-exit)))
