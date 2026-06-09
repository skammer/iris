(ns agent.loop
  "Task-list-driven self-iteration prompt support."
  (:require
   [agent.util :as util]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-plan-file "LOOP_PLAN.md")
(def default-max-iterations 10)
(def default-summary-max-chars 1200)
(def default-validation-max-chars 12000)

(defonce ^:private loop-states (atom {}))
(def ^:private state-lock (Object.))

(def instructions
  ["Choose ONE task from the plan. Do not implement multiple things."
   "Before writing code, search the codebase first."
   "After implementing, run tests for changed code."
   "Keep the plan up to date: mark completed items, add new findings."
   "If you discover unrelated bugs, document them in the plan."])

(defn new-state
  [{:keys [prompt plan-file max-iterations run-cmd]}]
  {:prompt prompt
   :plan-file (or plan-file default-plan-file)
   :iteration 0
   :max-iterations max-iterations
   :last-summary nil
   :run-cmd run-cmd
   :last-run-output nil
   :active? true
   :started-at (util/now-str)})

(defn options
  [cfg overrides]
  (let [loop-cfg (:loop cfg)]
    {:plan-file (or (:plan-file overrides)
                    (:plan-file loop-cfg)
                    default-plan-file)
     :max-iterations (or (:max-iterations overrides)
                         (:max-iterations loop-cfg)
                         default-max-iterations)
     :run-cmd (or (:run-cmd overrides)
                  (:run-cmd loop-cfg))
     :summary-max-chars (or (:summary-max-chars overrides)
                            (:summary-max-chars loop-cfg)
                            default-summary-max-chars)
     :validation-max-chars (or (:validation-max-chars overrides)
                               (:validation-max-chars loop-cfg)
                               default-validation-max-chars)}))

(defn validate-max-iterations! [value]
  (when (and value (not (pos? (long value))))
    (throw (ex-info "loop max iterations must be positive"
                    {:type :invalid-loop-max
                     :max-iterations value})))
  value)

(defn read-plan [plan-file]
  (let [file (io/file (or plan-file default-plan-file))]
    (when (.isFile file)
      (slurp file))))

(defn plan-complete? [plan-text]
  (let [lines (str/split-lines (or plan-text ""))
        checkboxes (filter #(re-find #"(?i)- \[[ x]\]" %) lines)]
    (and (seq checkboxes)
         (every? #(re-find #"(?i)- \[x\]" %) checkboxes))))

(defn should-stop? [{:keys [iteration max-iterations] :as state}]
  (boolean
   (or (and max-iterations (>= (long iteration) (long max-iterations)))
       (plan-complete? (read-plan (:plan-file state))))))

(defn iteration-label [{:keys [iteration max-iterations]}]
  (str "LOOP " iteration "/" (or max-iterations "∞")))

(defn- truncate [text max-chars]
  (util/truncate text
                 (or max-chars default-summary-max-chars)
                 #(str "\n[truncated " % " chars]")))

(defn summarize [text]
  (truncate (str/trim (or text "")) 500))

(def ^:private file-path-re
  #"(?:src|test|resources|docs|scripts|tmp)/[A-Za-z0-9._/\-]*[A-Za-z0-9_-]")

(def ^:private validation-line-re
  #"(?i)(^Testing .+|^Ran \d+ tests?.+|^\d+ failures?, \d+ errors?.*|^FAIL.*|^ERROR.*|^exit \d+.*)")

(defn- distinct-take [n xs]
  (->> xs
       (remove str/blank?)
       distinct
       (take n)
       vec))

(defn- extract-paths [text]
  (distinct-take 20 (re-seq file-path-re (or text ""))))

(defn- extract-validation-lines [text]
  (distinct-take 12
                 (keep (fn [line]
                         (when (re-find validation-line-re line)
                           (str/trim line)))
                       (str/split-lines (or text "")))))

(defn- unchecked-plan-items [plan-text]
  (->> (str/split-lines (or plan-text ""))
       (keep (fn [line]
               (when-let [[_ item] (re-matches #"(?i)\s*-\s+\[\s\]\s+(.+)" line)]
                 (str/trim item))))
       (take 10)
       vec))

(defn progress-summary
  [{:keys [response validation-output plan-file summary-max-chars]}]
  (let [plan-text (read-plan plan-file)
        response* (str/trim (or response ""))]
    {:assistant-summary (truncate response* summary-max-chars)
     :changed-files (extract-paths response*)
     :validation (extract-validation-lines validation-output)
     :next-plan-item (first (unchecked-plan-items plan-text))
     :open-plan-items (unchecked-plan-items plan-text)}))

(defn render-progress
  [summary]
  (if (map? summary)
    (str/join
     "\n"
     (cond-> []
       (:assistant-summary summary)
       (conj (str "assistant_summary: " (:assistant-summary summary)))

       (seq (:changed-files summary))
       (conj (str "changed_files: " (str/join ", " (:changed-files summary))))

       (seq (:validation summary))
       (conj (str "validation: " (str/join " | " (:validation summary))))

       (:next-plan-item summary)
       (conj (str "next_plan_item: " (:next-plan-item summary)))))
    (or summary "starting fresh")))

(defn build-prompt [{:keys [prompt plan-file iteration max-iterations last-summary last-run-output]}]
  (str prompt
       "\n\n--- Loop Context (Iteration " iteration "/" (or max-iterations "∞") ") ---\n\n"
       "Current plan (" (or plan-file default-plan-file) "):\n"
       (or (read-plan plan-file) "")
       "\n\nPrevious iteration summary:\n"
       (render-progress (or last-summary "starting fresh"))
       "\n\nPrevious validation output:\n"
       (or last-run-output "(none)")
       "\n\n--- Instructions ---\n"
       (str/join "\n" (map #(str "- " %) instructions))))

(defn run-validation
  ([cmd] (run-validation cmd {}))
  ([cmd {:keys [validation-max-chars]}]
   (when-not (str/blank? (or cmd ""))
     (truncate (str "validation skipped: /loop validation commands no longer execute shell. "
                    "Run checks through approved shell/tool paths.")
               (or validation-max-chars default-validation-max-chars)))))

(defn control-command [text]
  (let [text* (str/trim (or text ""))]
    (when-let [[_ rest] (re-matches #"(?is)^/loop(?:\s+(.*))?$" text*)]
      (let [rest* (str/trim (or rest ""))]
        (cond
          (str/blank? rest*) {:action :status}
          :else (let [[head tail] (str/split rest* #"\s+" 2)
                      tail* (str/trim (or tail ""))]
                  (case (str/lower-case head)
                    "status" {:action :status}
                    "stop" {:action :stop}
                    "run" {:action :run :value tail*}
                    "plan" {:action :plan :value tail*}
                    {:action :start :prompt rest*})))))))

(defn active-state [session-id]
  (when-let [state (and session-id (get @loop-states session-id))]
    (when (:active? state) state)))

(defn active? [session-id]
  (boolean (active-state session-id)))

(defn status-text
  [session-id]
  (if-let [{:keys [iteration max-iterations plan-file run-cmd last-run-output]} (active-state session-id)]
    (str "Loop active: " (iteration-label {:iteration iteration :max-iterations max-iterations})
         ". Plan: " plan-file
         ". Run: " (or run-cmd "none")
         ". Last validation: "
         (if (str/blank? (or last-run-output ""))
           "none"
           (first (str/split-lines last-run-output))))
    "No active loop. Usage: /loop <prompt> | /loop status | /loop stop | /loop plan <path>."))

(defn start!
  [session-id cfg prompt]
  (let [opts (options cfg {})
        max-iterations (validate-max-iterations! (:max-iterations opts))]
    (locking state-lock
      (if (active-state session-id)
        {:content (status-text session-id)
         :started? false}
        (let [state (new-state {:prompt prompt
                                :plan-file (:plan-file opts)
                                :max-iterations max-iterations
                                :run-cmd (:run-cmd opts)})]
          (swap! loop-states assoc session-id state)
          {:content (str "Loop started: " (iteration-label state)
                         ". Plan: " (:plan-file state) ".")
           :started? true
           :state state})))))

(defn stop!
  [session-id]
  (locking state-lock
    (let [state (active-state session-id)]
      (swap! loop-states update session-id assoc :active? false :stopped-at (util/now-str))
      {:content (if state "Loop stopped." "No active loop.")
       :stopped? (boolean state)})))

(defn update-run!
  [session-id cmd]
  (locking state-lock
    (if (and (active-state session-id) (not (str/blank? cmd)))
      {:content "Loop validation commands are disabled. Use approved shell/tool execution outside /loop."}
      {:content "Loop validation commands are disabled."})))

(defn update-plan!
  [session-id path]
  (locking state-lock
    (if (and (active-state session-id) (not (str/blank? path)))
      (do
        (swap! loop-states assoc-in [session-id :plan-file] path)
        {:content (str "Loop plan: " path ".")})
      {:content "Usage: /loop plan <path>."})))

(defn handle-control!
  [session-id cfg text]
  (when-let [{:keys [action prompt value]} (control-command text)]
    (case action
      :start (start! session-id cfg prompt)
      :status {:content (status-text session-id)}
      :stop (stop! session-id)
      :run (update-run! session-id value)
      :plan (update-plan! session-id value))))

(defn prepare-iteration!
  [session-id]
  (locking state-lock
    (when-let [state (active-state session-id)]
      (if (should-stop? state)
        (do
          (swap! loop-states assoc-in [session-id :active?] false)
          nil)
        (let [state* (update state :iteration inc)]
          (swap! loop-states assoc session-id state*)
          state*)))))

(defn record-result!
  [session-id response validation-output cfg]
  (let [opts (options cfg {})
        state (active-state session-id)
        summary (progress-summary {:response response
                                   :validation-output validation-output
                                   :plan-file (:plan-file state)
                                   :summary-max-chars (:summary-max-chars opts)})]
    (locking state-lock
      (when-let [state* (active-state session-id)]
        (let [updated (assoc state*
                             :last-summary summary
                             :last-run-output validation-output
                             :updated-at (util/now-str))
              stopped? (should-stop? updated)
              updated* (cond-> updated stopped? (assoc :active? false
                                                       :stopped-at (util/now-str)))]
          (swap! loop-states assoc session-id updated*)
          {:state updated*
           :stopped? stopped?
           :content (when stopped?
                      (str "Loop complete: " (iteration-label updated*) "."))})))))
