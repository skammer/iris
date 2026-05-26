(ns agent.cli
  "Command-line parsing and dispatch."
  (:require
   [agent.loop :as loop]
   [agent.logging :as logging]
   [agent.skills :as skills]
   [agent.nrepl :as nrepl]
   [agent.system :as system]
   [clojure.string :as str]))

(def session-title-max-chars 80)

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
    "  clojure -M -m agent.core skills [prefix]"
    "  clojure -M -m agent.core loop --prompt \"task\" --plan LOOP_PLAN.md --max 10 --run \"clojure -M:test\""
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

          (if (and (contains? #{"serve" "loop" "skills"} arg)
                   (empty? (:prompt-parts parsed))
                   (nil? (:command parsed)))
            (recur (next remaining) (assoc parsed :command arg))
            (recur (next remaining) (update parsed :prompt-parts conj arg))))))))

(defn- prompt-title [prompt]
  (let [title (some-> prompt str/split-lines first str/trim)]
    (when-not (str/blank? title)
      (if (> (count title) session-title-max-chars)
        (str (subs title 0 (- session-title-max-chars 3)) "...")
        title))))

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
  (if (system/session-exists? system session-id)
    session-id
    (throw (ex-info (str "Session not found: " session-id)
                    {:type :invalid-cli-session
                     :session-id session-id}))))

(defn- new-session-id [system prompt]
  (:id (system/create-session! system (prompt-title prompt))))

(defn- session-id-for-prompt [system {:keys [continue? no-session? resume? session-id]} prompt]
  (let [sessions (delay (system/list-sessions system))]
    (cond
      no-session? nil
      session-id (specific-session-id system session-id)
      resume? (or (pick-session-id! @sessions)
                  (new-session-id system prompt))
      continue? (or (:id (first @sessions))
                    (new-session-id system prompt))
      :else (new-session-id system prompt))))

(defn- stream-prompt! [system prompt session-id]
  (let [streamed? (atom false)
        result (system/complete! system
                                 [{:role "user" :content prompt}]
                                 {:session-id session-id
                                  :on-delta (fn [delta]
                                              (reset! streamed? true)
                                              (print delta)
                                              (flush))})]
    (when-not @streamed?
      (print (or (:content result) "")))
    (println)
    result))

(defn- print-skills! [system prefix]
  (let [catalog (skills/filter-catalog (skills/skill-catalog (:skills-registry system))
                                       prefix)]
    (if (seq catalog)
      (doseq [{:keys [name description]} catalog]
        (println (str "/" name " - " description)))
      (println "No skills found."))))

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
      (let [system (system/start-api! (system/create-system config-path))
            nrepl-server (nrepl/start! system (:nrepl (:config system)))
            {:keys [host port]} (:api (:config system))]
        (logging/log! :agent.cli/serve {:host host :port port})
        (println (str "API listening on http://" host ":" port))
        (when nrepl-server
          (println (str "nREPL listening on " (:bind nrepl-server) ":" (:port nrepl-server)
                        " (" (:port-file nrepl-server) ")")))
        @(promise))

      (= "skills" command)
      (let [system (system/create-system config-path)]
        (print-skills! system prompt))

      (= "loop" command)
      (let [system (system/create-system config-path)]
        (run-loop! system parsed))

      (str/blank? prompt)
      (do
        (binding [*out* *err*]
          (println (usage)))
        (System/exit 1))

      :else
      (let [system (system/create-system config-path)
            session-id (session-id-for-prompt system parsed prompt)]
        (logging/log! :agent.cli/prompt {:prompt-length (count prompt)
                                         :session-id session-id
                                         :ephemeral? (boolean no-session?)})
        (stream-prompt! system prompt session-id)))))
