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
  (when-not (and (string? (:command input)) (not (str/blank? (:command input))))
    (throw (tools/validation-error "command must be a non-blank string" {:input input})))
  input)

(defn- wait-for [^Process process timeout-ms]
  (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn create-shell-tool
  [opts]
  (let [config (merge {:roots ["."]
                       :working-dir "."
                       :timeout-ms 30000}
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
       :input-schema {:required [:command]
                      :optional [:working-dir :timeout-ms]}
       :source :builtin)
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                    :details {:roots roots
                              :working-dir (canonical-path (:working-dir config))}})
      :execute-fn
      (fn [input _context]
        (let [working-dir (resolve-working-dir! roots (or (:working-dir input) (:working-dir config)))
              timeout-ms (long (or (:timeout-ms input) (:timeout-ms config)))
              shell (if (.startsWith (System/getProperty "os.name") "Windows") "cmd" "sh")
              shell-args (if (= shell "cmd")
                           ["/c" (:command input)]
                           ["-lc" (:command input)])
              process (.start (doto (ProcessBuilder. (into [shell] shell-args))
                                (.directory (io/file working-dir))))
              finished? (wait-for process timeout-ms)
              stdout (future (slurp (.getInputStream process)))
              stderr (future (slurp (.getErrorStream process)))]
          (when-not finished?
            (.destroyForcibly process)
            (throw (tools/tool-error :timeout
                                     "Shell command timed out"
                                     {:command (:command input)
                                      :timeout-ms timeout-ms})))
          {:command (:command input)
           :working-dir working-dir
           :exit (.exitValue process)
           :stdout @stdout
           :stderr @stderr}))})))
