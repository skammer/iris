(ns agent.cli
  "Command-line parsing and dispatch."
  (:require
   [agent.chat :as chat]
   [agent.cli.render :as cli-render]
   [agent.config :as cfg]
   [agent.cron.cli :as cron-cli]
   [agent.loop :as loop]
   [agent.logging :as logging]
   [agent.sessions.service :as sessions]
   [agent.skills :as skills]
   [agent.nrepl :as nrepl]
   [agent.system :as system]
   [agent.wasm.bundles :as wasm-bundles]
   [clojure.string :as str]))

(def ^:dynamic *add-shutdown-hook!*
  (fn [^Thread hook]
    (.addShutdownHook (Runtime/getRuntime) hook)))

(def ^:dynamic *serve-block!*
  (fn []
    @(promise)))

(defn usage []
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m agent.core \"prompt text\""
    "  clojure -M -m agent.core -p \"prompt text\""
    "  clojure -M -m agent.core -c \"continue latest session\""
    "  clojure -M -m agent.core -r \"pick session\""
    "  clojure -M -m agent.core --session session-id \"continue session\""
    "  clojure -M -m agent.core --no-session \"ephemeral prompt\""
    "  clojure -M -m agent.core config init"
    "  clojure -M -m agent.core config migrate path/to/config.edn"
    "  clojure -M -m agent.core config set dotted.path value"
    "  clojure -M -m agent.core bundle install path/to/package.skill"
    "  clojure -M -m agent.core bundle list"
    "  clojure -M -m agent.core bundle enable bundle.id [version]"
    "  clojure -M -m agent.core bundle disable bundle.id"
    "  clojure -M -m agent.core skills [prefix]"
    "  clojure -M -m agent.core cron list|create|get|update|pause|resume|run|delete|runs|status"
    "  clojure -M -m agent.core loop --prompt \"task\" --plan LOOP_PLAN.md --max 10"
    "  clojure -M -m agent.core serve"
    "  clojure -M -m agent.core --config path/to/config.edn \"prompt text\""]))

(defn- require-option-value [option args]
  (or (second args)
      (throw (ex-info (str option " requires a value")
                      {:type :invalid-cli-args
                       :option option}))))

(defn parse-args [args]
  (loop [remaining (seq args)
         parsed {:prompt-parts []}]
    (if-not remaining
      (-> parsed
          (assoc :prompt (str/join " " (:prompt-parts parsed)))
          (dissoc :prompt-parts))
      (let [arg (first remaining)]
        (case arg
          "--"
          (recur nil (update parsed :prompt-parts into (rest remaining)))

          "--config"
          (recur (nnext remaining)
                 (assoc parsed :config-path (require-option-value arg remaining)))

          "--prompt"
          (recur (nnext remaining)
                 (assoc parsed :loop-prompt (require-option-value arg remaining)))

          "--plan"
          (recur (nnext remaining)
                 (assoc parsed :loop-plan (require-option-value arg remaining)))

          "--max"
          (recur (nnext remaining)
                 (assoc parsed :loop-max (Long/parseLong (require-option-value arg remaining))))

          "--run"
          (recur (nnext remaining)
                 (assoc parsed :loop-run (require-option-value arg remaining)))

          "-p"
          (recur (next remaining) (assoc parsed :print? true))

          "--print"
          (recur (next remaining) (assoc parsed :print? true))

          "-c"
          (recur (next remaining) (assoc parsed :continue? true))

          "--continue"
          (recur (next remaining) (assoc parsed :continue? true))

          "-r"
          (recur (next remaining) (assoc parsed :resume? true))

          "--resume"
          (recur (next remaining) (assoc parsed :resume? true))

          "--session"
          (recur (nnext remaining)
                 (assoc parsed :session-id (require-option-value arg remaining)))

          "--no-session"
          (recur (next remaining) (assoc parsed :no-session? true))

          (if (and (contains? #{"serve" "loop" "skills" "config" "bundle" "cron"} arg)
                   (empty? (:prompt-parts parsed))
                   (nil? (:command parsed)))
            (if (= "cron" arg)
              (recur nil (assoc parsed :command arg :command-args (vec (rest remaining))))
              (recur (next remaining) (assoc parsed :command arg)))
            (recur (next remaining) (update parsed :prompt-parts conj arg))))))))

(defn- find-session [sessions value]
  (let [exact (filter #(= value (:id %)) sessions)
        prefix (filter #(str/starts-with? (:id %) value) sessions)]
    (cond
      (= 1 (count exact)) (first exact)
      (= 1 (count prefix)) (first prefix)
      (> (count prefix) 1) (throw (ex-info (str "Session prefix is ambiguous: " value)
                                             {:type :invalid-cli-session
                                              :session-id value}))
      :else nil)))

(defn- session-label [{:keys [id title created-at]}]
  (str id
       "  "
       (or (not-empty title) "(untitled)")
       (when created-at
         (str "  " created-at))))

(defn- pick-session-id! [sessions]
  (when (seq sessions)
    (binding [*out* *err*]
      (println "Recent sessions:")
      (doseq [[idx session] (map-indexed vector sessions)]
        (println (str "  " (inc idx) ". " (session-label session))))
      (print "Select session number or id [blank=new]: ")
      (flush))
    (let [choice (some-> (read-line) str/trim)]
      (when-not (str/blank? choice)
        (if-let [[_ idx-text] (re-matches #"(\d+)" choice)]
          (let [idx (dec (parse-long idx-text))]
            (or (:id (nth sessions idx nil))
                (throw (ex-info (str "Session selection out of range: " choice)
                                {:type :invalid-cli-session
                                 :selection choice}))))
          (or (:id (find-session sessions choice))
              (throw (ex-info (str "Session not found: " choice)
                              {:type :invalid-cli-session
                               :session-id choice}))))))))

(defn- specific-session-id [system session-id]
  (if (sessions/session-exists? system session-id)
    session-id
    (throw (ex-info (str "Session not found: " session-id)
                    {:type :invalid-cli-session
                     :session-id session-id}))))

(defn- new-session-id [system _prompt]
  (:id (sessions/create-session! system nil)))

(defn- session-id-for-prompt [system {:keys [continue? no-session? resume? session-id]} prompt]
  (let [sessions (delay (sessions/list-sessions system))]
    (cond
      no-session? nil
      session-id (specific-session-id system session-id)
      resume? (or (pick-session-id! @sessions)
                  (new-session-id system prompt))
      continue? (or (:id (first @sessions))
                    (new-session-id system prompt))
      :else (new-session-id system prompt))))

(defn- stream-prompt! [system prompt session-id]
  (let [render-opts {:tty? (cli-render/tty?)}
        {:keys [on-delta finish]} (cli-render/make-stream-renderer render-opts)
        streamed? (atom false)
        result (chat/run! system
                          {:messages [{:role "user" :content prompt}]
                           :session-id session-id
                           :on-delta (fn [delta]
                                       (reset! streamed? true)
                                       (on-delta delta))})]
    (if @streamed?
      (finish)
      (cli-render/render-string! (or (:content result) "") render-opts))
    (println)
    result))

(defn- print-skills! [system prefix]
  (let [catalog (skills/filter-catalog (skills/skill-catalog (:skills-registry system))
                                       prefix)]
    (if (seq catalog)
      (doseq [{:keys [name description]} catalog]
        (println (str "/" name " - " description)))
      (println "No skills found."))))

(defn- config-args [prompt]
  (if (str/blank? prompt)
    []
    (str/split (str/trim prompt) #"\s+")))

(defn- print-edn! [value]
  (binding [*print-namespace-maps* false]
    (prn value)))

(defn- run-config-command! [prompt config-path]
  (let [[subcommand path & extra] (config-args prompt)]
    (case subcommand
      "init"
      (do
        (when (or path (seq extra))
          (throw (ex-info "config init takes no arguments"
                          {:type :invalid-cli-args})))
        (println (.getPath (cfg/init-config!))))

      "migrate"
      (do
        (when (or (str/blank? path) (seq extra))
          (throw (ex-info "config migrate requires exactly one path"
                          {:type :invalid-cli-args})))
        (print-edn! (cfg/migrate-config-file path)))

      "set"
      (do
        (when (or (str/blank? path) (not (seq extra)))
          (throw (ex-info "config set requires path and value"
                          {:type :invalid-cli-args})))
        (print-edn! (cfg/set-config-value!
                     path
                     (str/join " " extra)
                     {:explicit-path config-path})))

      (throw (ex-info "config command must be init, migrate, or set"
                      {:type :invalid-cli-args
                       :subcommand subcommand})))))

(defn- bundle-key [id version]
  (if (str/blank? version)
    id
    (str id "@" version)))

(defn- current-enabled-bundles [config-path]
  (vec (get-in (cfg/load-config config-path) [:tools :wasm-bundles :enabled] [])))

(defn- set-enabled-bundles! [config-path enabled]
  (cfg/set-config-value!
   "tools.wasm-bundles.enabled"
   (pr-str (vec enabled))
   {:explicit-path config-path}))

(defn- print-bundles! [bundles]
  (if (seq bundles)
    (doseq [{:keys [id name version root]} bundles]
      (println (str id " " version " /" name " " root)))
    (println "No bundles found.")))

(defn- run-bundle-command! [prompt config-path]
  (let [[subcommand a b & extra] (config-args prompt)
        config (cfg/load-config config-path)
        bundle-cfg (get-in config [:tools :wasm-bundles])]
    (case subcommand
      "install"
      (do
        (when (or (str/blank? a) b (seq extra))
          (throw (ex-info "bundle install requires exactly one package path"
                          {:type :invalid-cli-args})))
        (print-edn! (wasm-bundles/install-bundle! bundle-cfg a)))

      "list"
      (do
        (when (or a b (seq extra))
          (throw (ex-info "bundle list takes no arguments"
                          {:type :invalid-cli-args})))
        (print-bundles! (wasm-bundles/discover-bundles bundle-cfg)))

      "installed"
      (do
        (when (or a b (seq extra))
          (throw (ex-info "bundle installed takes no arguments"
                          {:type :invalid-cli-args})))
        (print-bundles! (wasm-bundles/installed-bundles bundle-cfg)))

      "enable"
      (do
        (when (or (str/blank? a) (seq extra))
          (throw (ex-info "bundle enable requires id and optional version"
                          {:type :invalid-cli-args})))
        (let [enabled (current-enabled-bundles config-path)
              key (bundle-key a b)]
          (print-edn! (set-enabled-bundles! config-path (distinct (conj enabled key))))))

      "disable"
      (do
        (when (or (str/blank? a) b (seq extra))
          (throw (ex-info "bundle disable requires exactly one id"
                          {:type :invalid-cli-args})))
        (let [enabled (remove #(or (= a (str %))
                                   (str/starts-with? (str %) (str a "@")))
                              (current-enabled-bundles config-path))]
          (print-edn! (set-enabled-bundles! config-path enabled))))

      (throw (ex-info "bundle command must be install, list, installed, enable, or disable"
                      {:type :invalid-cli-args
                       :subcommand subcommand})))))

(defn- create-system!
  [config-path]
  (cfg/init-config!)
  (system/create-system config-path))

(defn- with-system!
  [config-path f]
  (let [system (create-system! config-path)]
    (try
      (f system)
      (finally
        (system/close-system! system)))))

(defn- serve-shutdown!
  [system nrepl-server]
  (let [closed? (atom false)]
    (fn []
      (when (compare-and-set! closed? false true)
        (try
          (some-> @nrepl-server nrepl/stop!)
          (catch Exception e
            (logging/log-error! :agent.cli.lifecycle/nrepl-stop-failed e {})))
        (system/close-system! system)))))

(defn- register-shutdown-hook!
  [shutdown!]
  (*add-shutdown-hook!* (Thread. (reify Runnable
                                   (run [_]
                                     (shutdown!)))
                                 "iris-shutdown-hook")))

(defn- run-serve!
  [config-path]
  (let [system (system/start-api! (create-system! config-path))
        nrepl-server (atom nil)
        shutdown! (serve-shutdown! system nrepl-server)
        {:keys [host port]} (:api (:config system))]
    (try
      (reset! nrepl-server (nrepl/start! system (:nrepl (:config system))))
      (register-shutdown-hook! shutdown!)
      (logging/log! :agent.cli/serve {:host host :port port})
      (println (str "API listening on http://" host ":" port))
      (when-let [server @nrepl-server]
        (println (str "nREPL listening on " (:bind server) ":" (:port server)
                      " (" (:port-file server) ")")))
      (*serve-block!*)
      (finally
        (shutdown!)))))

(defn- loop-prompt [parsed]
  (or (some-> (:loop-prompt parsed) str/trim not-empty)
      (some-> (:prompt parsed) str/trim not-empty)))

(defn- run-loop! [system parsed]
  (let [prompt (or (loop-prompt parsed)
                   (throw (ex-info "loop requires --prompt or prompt text"
                                   {:type :invalid-cli-args})))
        loop-opts (loop/options (:config system)
                                {:plan-file (:loop-plan parsed)
                                 :max-iterations (:loop-max parsed)
                                 :run-cmd (:loop-run parsed)})
        max-iterations (loop/validate-max-iterations! (:max-iterations loop-opts))
        session-id (session-id-for-prompt system parsed (str "Loop: " prompt))
        initial-state (loop/new-state {:prompt prompt
                                       :plan-file (:plan-file loop-opts)
                                       :max-iterations max-iterations
                                       :run-cmd (:run-cmd loop-opts)})]
    (loop [state initial-state]
      (if (loop/should-stop? state)
        state
        (let [state* (update state :iteration inc)]
          (binding [*out* *err*]
            (println (str "=== " (loop/iteration-label state*) " ===")))
          (let [result (stream-prompt! system (loop/build-prompt state*) session-id)
                validation (loop/run-validation (:run-cmd state*) loop-opts)
                summary (loop/progress-summary {:response (:content result)
                                                :validation-output validation
                                                :plan-file (:plan-file state*)
                                                :summary-max-chars (:summary-max-chars loop-opts)})]
            (when validation
              (binding [*out* *err*]
                (println "--- validation ---")
                (println validation)))
            (recur (assoc state*
                          :last-summary summary
                          :last-run-output validation))))))))

(defn main [args]
  (let [{:keys [config-path command prompt no-session?] :as parsed} (parse-args args)]
    (cond
      (= "serve" command)
      (run-serve! config-path)

      (= "config" command)
      (run-config-command! prompt config-path)

      (= "bundle" command)
      (run-bundle-command! prompt config-path)

      (= "skills" command)
      (with-system! config-path #(print-skills! % prompt))

      (= "cron" command)
      (with-system! config-path #(cron-cli/run! % (:command-args parsed)))

      (= "loop" command)
      (with-system! config-path #(run-loop! % parsed))

      (str/blank? prompt)
      (do
        (binding [*out* *err*]
          (println (usage)))
        (System/exit 1))

      :else
      (with-system!
        config-path
        (fn [system]
          (let [session-id (session-id-for-prompt system parsed prompt)]
            (logging/log! :agent.cli/prompt {:prompt-length (count prompt)
                                             :session-id session-id
                                             :ephemeral? (boolean no-session?)})
            (stream-prompt! system prompt session-id)))))))
