(ns agent.tools.common.shell
  "Local shell execution tool with bounded working roots."
  (:require
   [agent.tools.core :as tools]
   [agent.tools.shell-policy :as policy]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.util.regex Pattern)))

(def default-rules policy/default-rules)

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

(defn- command-argv [command]
  ["/bin/bash" "-lc" command])

(defn- binary-basename [value]
  (.getName (io/file (or value ""))))

(defn- policy-argv [argv]
  (if (seq argv)
    (update argv 0 binary-basename)
    argv))

(def ^:private shell-wrapper-binaries #{"sh" "bash" "zsh" "dash"})
(def ^:private always-denied-binaries #{"dd" "mkfs" "fdisk" "mkswap"})

(defn- shell-script [args]
  (when (and (str/starts-with? (or (first args) "") "-")
             (str/includes? (first args) "c"))
    (second args)))

(defn- simple-shell-script? [script]
  (not (re-find #"[|&;<>()`$\n\r]" (or script ""))))

(defn- policy-command-argv [argv]
  (let [[binary & args] (policy-argv argv)
        script (when (contains? shell-wrapper-binaries binary)
                 (shell-script args))]
    (if (and script (simple-shell-script? script))
      (split-command script)
      argv)))

(defn- rm-recursive-force? [args]
  (let [options (filter #(str/starts-with? % "-") args)
        recursive? (some #(or (str/includes? % "r")
                              (str/includes? % "R")) options)
        force? (some #(str/includes? % "f") options)]
    (boolean (and recursive? force?))))

(defn- script-tokens [script]
  (->> (str/split (or script "") #"[^A-Za-z0-9_./=+-]+")
       (remove str/blank?)
       vec))

(defn- authoritative-script-deny [script argv]
  (let [tokens (script-tokens script)
        binaries (mapv binary-basename tokens)
        dangerous-binary (some #(when (contains? always-denied-binaries %) %) binaries)
        recursive-rm? (some (fn [[idx binary]]
                              (when (= "rm" binary)
                                (rm-recursive-force? (subvec tokens (inc idx)))))
                            (map-indexed vector binaries))]
    (when (or dangerous-binary recursive-rm?)
      {:decision :deny
       :reason "Command denied by authoritative shell safety rule"
       :details {:binary (or dangerous-binary "rm")
                 :argv argv}})))

(defn- authoritative-deny [argv]
  (let [[binary & args] (policy-argv argv)]
    (cond
      (nil? binary) nil

      (= "sudo" binary)
      (authoritative-deny (vec args))

      (and (contains? shell-wrapper-binaries binary)
           (shell-script args))
      (or (authoritative-script-deny (shell-script args) argv)
          (authoritative-deny (split-command (shell-script args))))

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
    (let [policy-argv* (policy-command-argv argv)]
      (if (legacy-policy-config? config)
        (legacy-policy-decision config policy-argv*)
        (rule-policy-decision config policy-argv*)))))

(defn- validate-input [input]
  (let [argv? (contains? input :argv)
        command? (contains? input :command)
        command (:command input)
        _ (when (= argv? command?)
            (throw (tools/validation-error
                    "provide exactly one of argv or command"
                    {:input input})))
        _ (when (and command? (str/blank? command))
            (throw (tools/validation-error "command must be non-blank" {:input input})))
        argv (if argv? (:argv input) (command-argv command))]
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
      (loop [remaining max-bytes truncated? false]
        (let [n (.read in buffer)]
          (if (neg? n)
            {:text (.toString out "UTF-8") :truncated? truncated?}
            (let [kept (min remaining n)]
              (when (pos? kept) (.write out buffer 0 kept))
              (recur (- remaining kept) (or truncated? (> n kept))))))))))

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
       "Run a local process. Pass exactly one top-level field: argv for an executable plus literal arguments, or command for shell syntax such as pipes, redirects, variables, and &&. Never wrap input inside an arguments field."
       :category :system
       :timeout-ms (:timeout-ms config)
       :required-permissions #{:shell-exec}
       :input-schema [:map {:closed true}
                      [:argv {:optional true
                              :description "Exact executable and arguments, e.g. [\"rg\" \"TODO\" \"src\"]. No shell parsing."}
                       [:vector {:min 1} :string]]
                      [:command {:optional true
                                 :description "Shell command string for pipes, redirects, variables, globs, or &&; executed with /bin/bash -lc."}
                       :string]
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
              deadline (+ (System/nanoTime) (* 1000000 timeout-ms))
              timeout! #(throw (tools/tool-error :timeout "Shell command timed out"
                                                 {:argv argv :timeout-ms timeout-ms}))
              output! (fn [task]
                        (let [remaining (max 0 (quot (- deadline (System/nanoTime)) 1000000))
                              result (deref task remaining ::timeout)]
                          (if (= ::timeout result) (timeout!) result)))]
          (try
            (when-not (wait-for process timeout-ms) (timeout!))
            (let [out (output! stdout) err (output! stderr)]
              {:argv argv :working-dir working-dir :exit (.exitValue process)
               :stdout (:text out) :stderr (:text err)
               :stdout-truncated? (:truncated? out) :stderr-truncated? (:truncated? err)})
            (finally
              (when (.isAlive process) (.destroyForcibly process))
              (.close (.getOutputStream process))
              (future-cancel stdout)
              (future-cancel stderr)
              (.close (.getInputStream process))
              (.close (.getErrorStream process))))))})))
