(ns agent.memory.quality
  "Recall metrics and vault quality reports for operator review."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [agent.memory.policy :as policy]))

(defn record-recall-latency!
  [memory-service latency-ms]
  (when-let [metrics (:recall-metrics memory-service)]
    (swap! metrics
           (fn [{:keys [count max-latency-ms] :as current}]
             (assoc current
                    :count (inc (long (or count 0)))
                    :last-latency-ms latency-ms
                    :max-latency-ms (max (long (or max-latency-ms 0))
                                         (long latency-ms)))))))

(defn recall-latency-metrics
  [memory-service]
  (select-keys @(or (:recall-metrics memory-service)
                    (atom {}))
               [:count :last-latency-ms :max-latency-ms]))

(defn- quality-config [memory-service]
  (let [cfg (get-in memory-service [:config :quality])]
    {:low-confidence-threshold (if (number? (:low-confidence-threshold cfg))
                                 (double (:low-confidence-threshold cfg))
                                 policy/default-low-confidence-threshold)
     :stale-days (if (and (integer? (:stale-days cfg))
                          (pos? (:stale-days cfg)))
                   (:stale-days cfg)
                   policy/default-stale-days)}))

(defn- count-by [f coll]
  (->> coll
       (map f)
       (map #(cond
               (nil? %) "unknown"
               (keyword? %) (name %)
               :else (str %)))
       frequencies
       (into (sorted-map))))

(defn- reviewable-note? [note]
  (contains? policy/reviewable-vault-statuses (:iris-status note)))

(defn- review-note-summary [note]
  (select-keys note [:path :id :type :title :iris-status :iris-scope
                     :iris-confidence :updated-at :body-hash]))

(defn- normalize-title [value]
  (-> (or value "")
      str/lower-case
      (str/replace #"[^a-z0-9а-яё]+" " ")
      str/trim))

(defn- conflict-key [note]
  [(:type note) (normalize-title (:title note)) (:iris-scope note)])

(defn- conflict-groups [notes]
  (->> notes
       (filter reviewable-note?)
       (remove #(str/blank? (normalize-title (:title %))))
       (group-by conflict-key)
       (keep (fn [[[type title scope] matches]]
               (when (and (< 1 (count matches))
                          (< 1 (count (set (map :body-hash matches)))))
                 {:type type
                  :title title
                  :scope scope
                  :notes (mapv review-note-summary matches)})))
       vec))

(defn- origin-type [origin]
  (or (:type origin) (:origin-type origin) "unknown"))

(defn- origin-vault-path [origin]
  (or (:vault_path origin)
      (:vault-path origin)))

(defn- broken-origin-notes [notes]
  (->> notes
       (keep (fn [note]
               (let [broken (->> (:origins note)
                                 (keep origin-vault-path)
                                 (remove #(.isFile (io/file %)))
                                 vec)]
                 (when (seq broken)
                   (assoc (review-note-summary note) :broken-origin-paths broken)))))
       vec))

(defn- parse-instant-safe [value]
  (try
    (some-> value str java.time.Instant/parse)
    (catch Exception _
      nil)))

(defn- frontmatter-value [note & ks]
  (some (fn [k]
          (or (get-in note [:frontmatter :iris k])
              (get-in note [:frontmatter k])))
        ks))

(defn- stale-note-reason [now stale-days note]
  (let [review-after (parse-instant-safe (frontmatter-value note :review_after :review-after))
        stale-after (parse-instant-safe (frontmatter-value note :stale_after :stale-after
                                                           :expires_at :expires-at))
        timestamp (parse-instant-safe (or (:timestamp note) (:updated-at note)))]
    (cond
      (and review-after (.isBefore review-after now)) :review-after
      (and stale-after (.isBefore stale-after now)) :stale-after
      (and timestamp
           (.isBefore timestamp (.minus now (java.time.Duration/ofDays stale-days)))) :age
      :else nil)))

(defn- stale-notes [notes stale-days]
  (let [now (java.time.Instant/now)]
    (->> notes
         (filter reviewable-note?)
         (keep (fn [note]
                 (when-let [reason (stale-note-reason now stale-days note)]
                   (assoc (review-note-summary note) :stale-reason reason))))
         vec)))

(defn- low-confidence-notes [notes threshold]
  (->> notes
       (filter reviewable-note?)
       (filter #(and (number? (:iris-confidence %))
                     (< (double (:iris-confidence %)) threshold)))
       (mapv review-note-summary)))

(defn- orphan-notes [notes]
  (->> notes
       (remove #(.isFile (io/file (:path %))))
       (mapv review-note-summary)))

(defn- orphan-chunks [notes chunks]
  (let [note-paths (set (map :path notes))
        missing-note-paths (set (map :path (orphan-notes notes)))]
    (->> chunks
         (filter #(or (not (contains? note-paths (:path %)))
                      (contains? missing-note-paths (:path %))))
         (mapv #(select-keys % [:chunk_id :path :heading :content_hash])))))

(defn- notes-without-chunks [notes chunks]
  (let [chunk-paths (set (map :path chunks))]
    (->> notes
         (filter reviewable-note?)
         (remove #(contains? chunk-paths (:path %)))
         (mapv review-note-summary))))

(defn- embedding-coverage [memory-service notes chunks]
  (let [enabled? (policy/embeddings-enabled? memory-service)
        desired-notes (filter #(= "approved" (:iris-status %)) notes)
        desired-paths (into #{} (map :path) desired-notes)
        desired-chunks (filter #(contains? desired-paths (:path %)) chunks)
        note-embeddings (sqlite/list-memory-embeddings (:store memory-service)
                                                       {:surface "vault_note"
                                                        :limit 10000})
        chunk-embeddings (sqlite/list-vault-chunk-embeddings (:store memory-service)
                                                             {:limit 10000})
        ratio (fn [actual desired]
                (if (zero? desired) 1.0 (/ (double actual) desired)))]
    {:enabled? enabled?
     :vault-notes {:desired (count desired-notes)
                   :embedded (count note-embeddings)
                   :coverage (ratio (count note-embeddings) (count desired-notes))}
     :vault-chunks {:desired (count desired-chunks)
                    :embedded (count chunk-embeddings)
                    :coverage (ratio (count chunk-embeddings) (count desired-chunks))}}))

(defn quality-report
  [memory-service]
  (let [{:keys [low-confidence-threshold stale-days]} (quality-config memory-service)
        notes (sqlite/list-vault-notes (:store memory-service) {:limit 10000})
        chunks (sqlite/list-vault-chunks (:store memory-service) {:limit 10000})
        pending-updates (sqlite/list-memory-note-updates
                         (:store memory-service) {:status "pending" :limit 1000})
        candidates (filter #(= "candidate" (:iris-status %)) notes)
        approved-inbox (filter (fn [{:keys [path iris-status]}]
                                 (and (= "approved" iris-status)
                                      (= "inbox" (some-> path io/file .getParentFile .getName str/lower-case))))
                               notes)
        origins (mapcat :origins notes)
        low-confidence (low-confidence-notes notes low-confidence-threshold)
        stale (stale-notes notes stale-days)
        conflicts (conflict-groups notes)
        broken-origins (broken-origin-notes notes)
        orphan-notes* (orphan-notes notes)
        orphan-chunks* (orphan-chunks notes chunks)
        empty-chunk-notes (notes-without-chunks notes chunks)]
    {:note-count-by-type (count-by :type notes)
     :note-count-by-status (count-by :iris-status notes)
     :candidate-backlog (count candidates)
     :candidate-notes (mapv review-note-summary (take 50 candidates))
     :pending-update-count (count pending-updates)
     :pending-updates (mapv #(dissoc % :proposed-content) (take 50 pending-updates))
     :approved-inbox-notes (mapv review-note-summary (take 50 approved-inbox))
     :low-confidence-threshold low-confidence-threshold
     :low-confidence-notes (vec (take 50 low-confidence))
     :origin-count-by-type (count-by origin-type origins)
     :conflicts (vec (take 50 conflicts))
     :stale-days stale-days
     :stale-notes (vec (take 50 stale))
     :broken-origin-notes (vec (take 50 broken-origins))
     :orphan-notes (vec (take 50 orphan-notes*))
     :orphan-chunks (vec (take 50 orphan-chunks*))
     :notes-without-chunks (vec (take 50 empty-chunk-notes))
     :embedding-coverage (embedding-coverage memory-service notes chunks)
     :recall-latency (recall-latency-metrics memory-service)
     :review-queue-count (+ (count candidates)
                            (count pending-updates)
                            (count approved-inbox)
                            (count low-confidence)
                            (count stale)
                            (count conflicts)
                            (count broken-origins)
                            (count orphan-notes*)
                            (count orphan-chunks*)
                            (count empty-chunk-notes))}))

(defn health-check
  [memory-service]
  (let [quality (quality-report memory-service)]
    {:healthy true
     :search {:healthy true
              :default-limit (:search-default-limit memory-service)
              :max-limit (:search-max-limit memory-service)}
     :vault {:healthy true
             :paths (:vault-roots memory-service)
             :writable (:vault-writable? memory-service)
             :note-count (sqlite/count-vault-notes (:store memory-service))
             :chunk-count (sqlite/count-vault-chunks (:store memory-service))}
     :quality quality}))
