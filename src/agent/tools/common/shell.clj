(ns agent.tools.common.shell
  "Local shell execution tool with bounded working roots."
  (:require
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- resolve-working-dir! [roots path]
  (let [candidate (canonical-path path)]
    (when-not (within-root? roots candidate)
      (throw (tools/tool-error :path-not-allowed
                               "Working directory is outside allowed roots"
                               {:path candidate
                                :roots roots})))
    candidate))

(defn- validate-input [input]
  (when (contains? input :command)
    (throw (tools/validation-error "command string is not supported; use argv"
                                   {:input input})))
  (when-not (and (vector? (:argv input)) (seq (:argv input)) (every? string? (:argv input)))
    (throw (tools/validation-error "argv must be a non-empty vector of strings" {:input input})))
  (assoc input :argv (vec (:argv input))))

(defn- wait-for [^Process process timeout-ms]
  (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn- ensure-allowed-command! [config argv]
  (let [allowed (set (:allowed-commands config))
        binary (first argv)]
    (when (and (:deny-by-default? config)
               (not (contains? allowed binary)))
      (throw (tools/tool-error :command-not-allowed
                               "Command is not in shell allowlist"
                               {:command binary
                                :allowed-commands (vec (:allowed-commands config))})))))

(defn- slurp-limited [stream max-bytes]
  (with-open [in stream]
    (let [buffer (byte-array 4096)
          out (java.io.ByteArrayOutputStream.)]
      (loop [remaining max-bytes]
        (when (pos? remaining)
          (let [read-count (.read in buffer 0 (min (alength buffer) remaining))]
            (when (pos? read-count)
              (.write out buffer 0 read-count)
              (recur (- remaining read-count))))))
      (.toString out "UTF-8"))))

(defn create-shell-tool
  [opts]
  (let [config (merge {:roots ["."]
                       :working-dir "."
                       :timeout-ms 30000
                       :deny-by-default? true
                       :allowed-commands ["printf" "pwd" "ls" "echo" "cat" "rg" "git"]
                       :max-output-bytes 65536}
                      opts)
        roots (mapv canonical-path (:roots config))]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :shell
       "Local shell execution tool"
       :category :system
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:shell-exec}
       :input-schema [:map {:closed true}
                      [:argv {:optional true} [:vector {:min 1} :string]]
                      [:command {:optional true} :string]
                      [:working-dir {:optional true} :string]
                      [:timeout-ms {:optional true} [:int {:min 1}]]]
       :sensitive true
       :source :builtin)
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                    :details {:roots roots
                              :working-dir (canonical-path (:working-dir config))
                              :deny-by-default? (:deny-by-default? config)
                              :allowed-commands (:allowed-commands config)}})
      :execute-fn
      (fn [input _context]
        (let [working-dir (resolve-working-dir! roots (or (:working-dir input) (:working-dir config)))
              timeout-ms (long (or (:timeout-ms input) (:timeout-ms config)))
              argv (:argv input)
              _ (ensure-allowed-command! config argv)
              process (.start (doto (ProcessBuilder. argv)
                                (.directory (io/file working-dir))))
              stdout (future (slurp-limited (.getInputStream process) (:max-output-bytes config)))
              stderr (future (slurp-limited (.getErrorStream process) (:max-output-bytes config)))
              finished? (wait-for process timeout-ms)]
          (when-not finished?
            (.destroyForcibly process)
            (throw (tools/tool-error :timeout
                                     "Shell command timed out"
                                     {:argv argv
                                      :timeout-ms timeout-ms})))
          {:argv argv
           :working-dir working-dir
           :exit (.exitValue process)
           :stdout @stdout
           :stderr @stderr}))})))
