(ns agent.loop
  "Task-list-driven self-iteration prompt support."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

(def default-plan-file "LOOP_PLAN.md")

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
   :last-run-output nil})

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

(defn build-prompt [{:keys [prompt plan-file iteration max-iterations last-summary last-run-output]}]
  (str prompt
       "\n\n--- Loop Context (Iteration " iteration "/" (or max-iterations "∞") ") ---\n\n"
       "Current plan (" (or plan-file default-plan-file) "):\n"
       (or (read-plan plan-file) "")
       "\n\nPrevious iteration summary:\n"
       (or last-summary "starting fresh")
       "\n\nPrevious validation output:\n"
       (or last-run-output "(none)")
       "\n\n--- Instructions ---\n"
       (str/join "\n" (map #(str "- " %) instructions))))

(defn summarize [text]
  (let [text* (str/trim (or text ""))]
    (if (> (count text*) 500)
      (str (subs text* 0 500) "\n[truncated]")
      text*)))

(defn run-validation [cmd]
  (when-not (str/blank? (or cmd ""))
    (let [{:keys [out err exit]} (shell/sh "sh" "-c" cmd)]
      (str "exit " exit "\n" out (when-not (str/blank? err) (str "\n" err))))))
