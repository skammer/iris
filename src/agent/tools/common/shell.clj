(ns agent.tools.common.shell
  "Local shell execution tool with bounded working roots."
  (:require
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.util.regex Pattern)))

(def default-rules
  [{:argv ["pwd"] :decision :allow}
   {:argv ["printf" "**"] :decision :allow}
   {:argv ["echo" "**"] :decision :allow}
   {:argv ["which" "**"] :decision :allow}
   {:argv ["type" "**"] :decision :allow}
   {:argv ["ls" "**"] :decision :allow}
   {:argv ["cat" "**"] :decision :allow}
   {:argv ["head" "**"] :decision :allow}
   {:argv ["tail" "**"] :decision :allow}
   {:argv ["wc" "**"] :decision :allow}
   {:argv ["df" "**"] :decision :allow}
   {:argv ["uname" "**"] :decision :allow}
   {:argv ["sort" "**"] :decision :allow}
   {:argv ["uniq" "**"] :decision :allow}
   {:argv ["cut" "**"] :decision :allow}
   {:argv ["diff" "**"] :decision :allow}
   {:argv ["rg" "**"] :decision :allow}
   {:argv ["grep" "**"] :decision :allow}
   {:argv ["find" "**"] :decision :allow}
   {:argv ["git" "status" "**"] :decision :allow}
   {:argv ["git" "log" "**"] :decision :allow}
   {:argv ["git" "diff" "**"] :decision :allow}
   {:argv ["git" "show" "**"] :decision :allow}
   {:argv ["git" "branch" "**"] :decision :allow}
   {:argv ["cargo" "fmt" "**"] :decision :allow}
   {:argv ["rm" "-rf" "/*"] :decision :deny}
   {:argv ["sudo" "rm" "-rf" "/*"] :decision :deny}
   {:argv ["dd" "**"] :decision :deny}
   {:argv ["mkfs" "**"] :decision :deny}
   {:argv ["fdisk" "**"] :decision :deny}
   {:argv ["mkswap" "**"] :decision :deny}])

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

(defn- split-command [command]
  (vec (remove str/blank? (str/split (str/trim (or command "")) #"\s+"))))

(defn- binary-basename [value]
  (.getName (io/file (or value ""))))

(defn- policy-argv [argv]
  (if (seq argv)
    (update argv 0 binary-basename)
    argv))

(def ^:private shell-wrapper-binaries #{"sh" "bash" "zsh" "dash"})
(def ^:private always-denied-binaries #{"dd" "mkfs" "fdisk" "mkswap"})

(defn- rm-recursive-force? [args]
  (let [options (filter #(str/starts-with? % "-") args)
        recursive? (some #(or (str/includes? % "r")
                              (str/includes? % "R")) options)
        force? (some #(str/includes? % "f") options)]
    (boolean (and recursive? force?))))

(defn- authoritative-deny [argv]
  (let [[binary & args] (policy-argv argv)]
    (cond
      (nil? binary) nil

      (= "sudo" binary)
      (authoritative-deny (vec args))

      (and (contains? shell-wrapper-binaries binary)
           (= "-c" (first args))
           (second args))
      (authoritative-deny (split-command (second args)))

      (and (= "rm" binary)
           (rm-recursive-force? args))
      {:decision :deny
       :reason "Command denied by authoritative shell safety rule"
       :details {:binary binary
                 :argv argv}}

      (contains? always-denied-binaries binary)
      {:decision :deny
       :reason "Command denied by authoritative shell safety rule"
       :details {:binary binary
                 :argv argv}}

      :else nil)))

(defn- normalize-decision [decision]
  (cond
    (keyword? decision) decision
    (string? decision) (keyword (str/lower-case decision))
    :else decision))

(defn- legacy-policy-config? [config]
  (and (map? config)
       (or (contains? config :allowed-commands)
           (contains? config :blocked-commands)
           (contains? config :deny-by-default?))))

(defn- normalize-rule-pattern [rule]
  (let [pattern (or (:argv rule) (:pattern rule) (:command rule))]
    (cond
      (vector? pattern) (mapv str pattern)
      (string? pattern) (split-command pattern)
      :else [])))

(defn- normalize-rule [rule]
  (when (map? rule)
    {:argv (normalize-rule-pattern rule)
     :decision (normalize-decision (or (:decision rule) (:action rule)))}))

(defn- glob-token-matches? [pattern value]
  (if (= "*" pattern)
    true
    (let [pieces (str/split pattern #"\*" -1)
          regex (str "^" (str/join ".*" (map #(Pattern/quote %) pieces)) "$")]
      (boolean (re-matches (re-pattern regex) value)))))

(defn- argv-pattern-matches? [pattern argv]
  (letfn [(matches? [ps as]
            (cond
              (empty? ps) (empty? as)
              (= "**" (first ps)) (or (matches? (rest ps) as)
                                      (and (seq as) (matches? ps (rest as))))
              (empty? as) false
              (glob-token-matches? (first ps) (first as)) (matches? (rest ps) (rest as))
              :else false))]
    (matches? pattern argv)))

(defn- legacy-policy-decision [config argv]
  (let [allowed (set (:allowed-commands config))
        blocked (set (:blocked-commands config))
        binary (binary-basename (first argv))]
    (cond
      (contains? blocked binary) {:decision :deny
                                  :reason "Command is in shell blocklist"
                                  :details {:command binary
                                            :blocked-commands (vec (:blocked-commands config))}}
      (and (:deny-by-default? config)
           (not (contains? allowed binary))) {:decision :deny
                                              :reason "Command is not in shell allowlist"
                                              :details {:command binary
                                                        :allowed-commands (vec (:allowed-commands config))}}
      :else {:decision :allow})))

(defn- rule-policy-decision [config argv]
  (let [rules (keep normalize-rule (:rules config))
        argv* (policy-argv argv)
        matches (filter #(argv-pattern-matches? (:argv %) argv*) rules)
        rule (last matches)]
    {:decision (or (:decision rule)
                   (normalize-decision (or (:default-decision config)
                                           (:default-action config)))
                   :ask)
     :rule rule}))

(defn- shell-policy-decision [config argv]
  (if-let [deny (authoritative-deny argv)]
    deny
    (if (legacy-policy-config? config)
      (legacy-policy-decision config argv)
      (rule-policy-decision config argv))))

(defn- validate-input [input]
  (let [argv (or (:argv input)
                 (when (contains? input :command)
                   (split-command (:command input))))]
    (when-not (and (vector? argv) (seq argv) (every? string? argv))
      (throw (tools/validation-error "argv must be a non-empty vector of strings" {:input input})))
    (-> input
        (dissoc :command)
        (assoc :argv (vec argv)))))

(defn- wait-for [^Process process timeout-ms]
  (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

(defn- ensure-not-denied! [config argv]
  (let [{:keys [decision reason details rule]} (shell-policy-decision config argv)]
    (when (= :deny decision)
      (throw (tools/tool-error :command-not-allowed
                               (or reason "Command denied by shell rule")
                               (merge {:argv argv
                                       :rule rule}
                                      details))))))

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
  (let [legacy-opts? (legacy-policy-config? opts)
        config (merge {:roots ["."]
                       :working-dir "."
                       :timeout-ms 30000
                       :max-timeout-ms 30000
                       :max-output-bytes 65536}
                      (if legacy-opts?
                        {:deny-by-default? true
                         :allowed-commands ["printf" "pwd" "ls" "echo" "cat" "rg" "git" "df"]
                         :blocked-commands []}
                        {:default-decision :ask
                         :rules default-rules})
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
       :operation :act
       :approval-sensitive? true
       :sensitive (fn [input]
                    (= :ask (:decision (shell-policy-decision config (:argv input)))))
       :source :builtin)
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                    :details {:roots roots
                              :working-dir (canonical-path (:working-dir config))
                              :default-decision (:default-decision config)
                              :rules (:rules config)
                              :max-timeout-ms (:max-timeout-ms config)}})
      :execute-fn
      (fn [input _context]
        (let [working-dir (resolve-working-dir! roots (or (:working-dir input) (:working-dir config)))
              requested-timeout-ms (long (or (:timeout-ms input) (:timeout-ms config)))
              max-timeout-ms (long (:max-timeout-ms config))
              timeout-ms (min requested-timeout-ms max-timeout-ms)
              argv (:argv input)
              _ (ensure-not-denied! config argv)
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
