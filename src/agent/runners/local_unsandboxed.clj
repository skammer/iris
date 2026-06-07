(ns agent.runners.local-unsandboxed
  "Local unsandboxed subprocess runner."
  (:require
   [agent.runners.core :as runners]
   [agent.runners.policy :as policy]
   [agent.util :as util]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io BufferedReader IOException InputStreamReader)))

(def ^:private now util/now-str)

(defn- process-pid [^Process process]
  (.pid (.toHandle process)))

(defn- process-status [entry]
  (when entry
    (let [^Process process (:process entry)
          alive? (and process (.isAlive process))]
      {:run-id (:run-id entry)
       :known true
       :pid (:pid entry)
       :command (:command entry)
       :working-dir (:working-dir entry)
       :started-at (:started-at entry)
       :finished-at (:finished-at entry)
       :alive alive?
       :state (if alive? :running :exited)
       :exit-code (cond
                    (:exit-code entry) (:exit-code entry)
                    (and process (not alive?)) (.exitValue process))})))

(defn- safe-callback [callback & args]
  (when callback
    (try
      (apply callback args)
      (catch Exception _ nil))))

(defn- consume-lines
  [run-id stream-name input-stream on-output]
  (future
    (try
      (with-open [reader (BufferedReader. (InputStreamReader. input-stream))]
        (loop []
          (when-let [line (.readLine reader)]
            (safe-callback on-output
                           run-id
                           {:stream stream-name
                            :line line
                            :captured-at (now)})
            (recur))))
      (catch IOException _ nil))))

(defrecord LocalUnsandboxedRunner [processes on-exit on-output]
  runners/IRunner
  (launch [_ run-spec]
    (let [run-spec (policy/validate-launch-spec run-spec)
          runner-options (:runner-options run-spec)
          command (:command runner-options)
          working-dir (or (:working-dir runner-options) ".")
          env-extra (or (:env runner-options) {})
          process-builder (ProcessBuilder. command)
          _ (.directory process-builder (io/file working-dir))
          env (.environment process-builder)]
      (.put env "AGENT_RUN_ID" (:run-id run-spec))
      (.put env "AGENT_AGENT_ID" (:agent-id run-spec))
      (.put env "AGENT_BOOTSTRAP_TOKEN" (or (:bootstrap-token run-spec) ""))
      (.put env "AGENT_BOOTSTRAP_SPEC" (pr-str (:bootstrap-spec run-spec)))
      (doseq [[k v] env-extra]
        (.put env (name k) (str v)))
      (let [started-at (now)
            process* (.start process-builder)
            _ (consume-lines (:run-id run-spec) :stdout (.getInputStream process*) on-output)
            _ (consume-lines (:run-id run-spec) :stderr (.getErrorStream process*) on-output)
            entry {:run-id (:run-id run-spec)
                   :process process*
                   :pid (process-pid process*)
                   :command command
                   :working-dir (.getAbsolutePath (io/file working-dir))
                   :started-at started-at}]
        (swap! processes assoc (:run-id run-spec) entry)
        (future
          (try
            (let [exit-code (.waitFor process*)
                  finished-at (now)]
              (swap! processes update (:run-id run-spec)
                     merge
                     {:alive false
                      :exit-code exit-code
                      :finished-at finished-at})
              (safe-callback on-exit
                             (:run-id run-spec)
                             {:exit-code exit-code
                              :finished-at finished-at}))
            (finally
              nil)))
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
        (when (and process (.isAlive process))
          (.destroy process))
        {:run-id run-id
         :stopped true})
      {:run-id run-id
       :stopped false
       :error "unknown_run"})))

(defn create-local-unsandboxed-runner
  ([] (create-local-unsandboxed-runner {}))
  ([{:keys [on-exit on-output]}]
   (->LocalUnsandboxedRunner (atom {}) on-exit on-output)))
