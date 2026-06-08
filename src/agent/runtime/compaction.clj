(ns agent.runtime.compaction
  "Session entry compaction planning and deterministic summary storage."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.tokens :as tokens]
   [clojure.string :as str]))

(def ^:private default-thresholds
  {:max-context-tokens 8192
   :reserve-output-tokens 1024
   :keep-recent-tokens 2048
   :max-summary-input-tokens 8192})

(defn- entry-tokens [entry]
  (tokens/estimate (:payload entry)))

(defn- total-tokens [entries]
  (reduce + 0 (map entry-tokens entries)))

(defn- message-role [entry]
  (or (get-in entry [:payload :role])
      (get-in entry [:payload :message :role])))

(defn- tool-result-entry? [entry]
  (and (= :message (:type entry))
       (= "tool" (message-role entry))))

(defn- recent-start-index [entries keep-recent-tokens]
  (loop [idx (dec (count entries))
         tokens 0]
    (if (neg? idx)
      0
      (let [tokens* (+ tokens (entry-tokens (nth entries idx)))]
        (if (> tokens* keep-recent-tokens)
          (inc idx)
          (recur (dec idx) tokens*))))))

(defn- safe-cut-index [entries thresholds]
  (let [idx (recent-start-index entries (:keep-recent-tokens thresholds))]
    (loop [i idx]
      (cond
        (>= i (count entries)) (max 0 (dec (count entries)))
        (tool-result-entry? (nth entries i)) (recur (inc i))
        :else i))))

(defn- take-summary-input [entries max-tokens]
  (loop [remaining entries
         tokens 0
         acc []]
    (if-let [entry (first remaining)]
      (let [tokens* (+ tokens (entry-tokens entry))]
        (if (> tokens* max-tokens)
          acc
          (recur (rest remaining) tokens* (conj acc entry))))
      acc)))

(defn- previous-summary [entries]
  (some (fn [entry]
          (when (= :compaction (:type entry))
            (get-in entry [:payload :summary])))
        (reverse entries)))

(defn- prepare-compaction
  ([entries] (prepare-compaction entries {}))
  ([entries thresholds]
   (let [thresholds* (merge default-thresholds thresholds)
         tokens-before (total-tokens entries)
         limit (- (:max-context-tokens thresholds*)
                  (:reserve-output-tokens thresholds*))]
     (when (> tokens-before limit)
       (let [cut-idx (safe-cut-index entries thresholds*)
             cut (subvec (vec entries) 0 cut-idx)
             kept (subvec (vec entries) cut-idx)
             first-kept (:id (first kept))
             oversized? (and (= 1 (count kept))
                             (> (entry-tokens (first kept))
                                (:keep-recent-tokens thresholds*)))]
         {:cut-index cut-idx
          :summary-input (take-summary-input cut (:max-summary-input-tokens thresholds*))
          :kept kept
          :first-kept-entry-id first-kept
          :tokens-before tokens-before
          :previous-summary (previous-summary cut)
          :oversized-single-turn? oversized?
          :thresholds thresholds*})))))

(defn- entry-summary-line [entry]
  (let [payload (:payload entry)
        text (or (:content payload)
                 (:summary payload)
                 (:label payload)
                 (pr-str payload))]
    (str (:id entry) " " (name (:type entry)) ": "
         (subs (str/replace (str text) #"\s+" " ")
               0
               (min 180 (count (str/replace (str text) #"\s+" " ")))))))

(defn- deterministic-summary [plan]
  (let [lines (map entry-summary-line (:summary-input plan))]
    (str "Compacted " (count (:summary-input plan)) " entries; "
         "tokens before " (:tokens-before plan) "."
         (when-let [prev (:previous-summary plan)]
           (str " Previous: " (subs prev 0 (min 180 (count prev)))))
         (when (seq lines)
           (str "\n" (str/join "\n" (take 12 lines)))))))

(defn- file-history [entries]
  (reduce (fn [acc entry]
            (let [details (or (get-in entry [:payload :details])
                              (get-in entry [:payload :metadata])
                              (:payload entry))]
              (-> acc
                  (update :files-read into (or (:files-read details) (:read-files details) []))
                  (update :files-touched into (or (:files-touched details) (:touched-files details) [])))))
          {:files-read [] :files-touched []}
          entries))

(defn- store-compaction! [store session-id plan]
  (let [summary (or (:summary plan) (deterministic-summary plan))
        details (merge {:thresholds (:thresholds plan)
                        :oversized-single-turn? (:oversized-single-turn? plan)
                        :file-history (file-history (:summary-input plan))}
                       (:details plan))]
    (sqlite/append-entry! store session-id
                          {:type :compaction
                           :payload {:summary summary
                                     :first-kept-entry-id (:first-kept-entry-id plan)
                                     :tokens-before (:tokens-before plan)
                                     :details details}})))

(defn compact-session!
  ([store session-id] (compact-session! store session-id {}))
  ([store session-id thresholds]
   (if-let [plan (prepare-compaction (sqlite/branch-path store session-id) thresholds)]
     (assoc (store-compaction! store session-id plan)
            :compacted? true
            :plan (dissoc plan :summary-input :kept))
     {:compacted? false})))

(defn- common-ancestor-id [old-path new-path]
  (loop [old old-path
         new new-path
         common nil]
    (if (and (seq old) (seq new) (= (:id (first old)) (:id (first new))))
      (recur (rest old) (rest new) (:id (first old)))
      common)))

(defn- branch-summary [old-path new-path]
  (let [ancestor (common-ancestor-id old-path new-path)
        after-ancestor (fn [path]
                         (->> path
                              (drop-while #(not= ancestor (:id %)))
                              rest
                              vec))]
    {:from-id ancestor
     :summary (str "Branch switch from " (count (after-ancestor old-path))
                   " old entries to " (count (after-ancestor new-path))
                   " new entries.")
     :details {:old-file-history (file-history (after-ancestor old-path))
               :new-file-history (file-history (after-ancestor new-path))}}))

(defn store-branch-summary! [store session-id old-leaf-id new-leaf-id]
  (let [summary (branch-summary (sqlite/branch-path store session-id old-leaf-id)
                                (sqlite/branch-path store session-id new-leaf-id))]
    (sqlite/append-entry! store session-id
                          {:type :branch_summary
                           :parent-id (:from-id summary)
                           :payload summary})))

(defn auto-compact! [store session-id cfg]
  (let [thresholds (:compaction cfg)]
    (compact-session! store session-id thresholds)))
