(ns agent.memory.search
  "Lexical and vector recall, scoring and bounded result selection."
  (:require
   [agent.llm.core :as llm]
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [agent.memory.policy :as policy]))

(defn- token-set [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

(defn ^:no-doc jaccard [left right]
  (let [a (token-set left)
        b (token-set right)
        union-count (count (set/union a b))]
    (if (zero? union-count)
      0.0
      (/ (double (count (set/intersection a b))) union-count))))

(defn- contains-query-score [query text]
  (let [query* (str/lower-case (str/trim (or query "")))
        text* (str/lower-case (or text ""))]
    (cond
      (str/blank? query*) 0.0
      (str/includes? text* query*) 1.0
      :else 0.0)))

(defn ^:no-doc confidence-score [value]
  (if (number? value)
    (max 0.0 (min 1.0 (double value)))
    0.5))

(defn- item-text [surface item]
  (case surface
    :message (:content item)
    :event (json/generate-string (:payload item))
    ""))

(defn- score-memory-item [query surface item]
  (let [text (item-text surface item)
        lexical (jaccard query text)
        exact (contains-query-score query text)
        confidence (confidence-score (:confidence item))
        surface-weight (case surface
                         :message 1.0
                         :event 0.8
                         1.0)
        score (* surface-weight
                 (+ (* 0.65 lexical)
                    (* 0.25 exact)
                    (* 0.10 confidence)))]
    {:surface surface
     :score score
     :score-breakdown {:lexical lexical
                       :exact exact
                       :confidence confidence
                       :surface-weight surface-weight}
     :item item}))

(defn- dedupe-ranked-results [ranked]
  (second
   (reduce (fn [[seen results] {:keys [surface item] :as scored}]
             (let [value (-> (item-text surface item)
                             str/lower-case
                             (str/replace #"\s+" " ")
                             str/trim)
                   key (if (str/blank? value)
                         (pr-str item)
                         value)]
               (if (contains? seen key)
                 [seen results]
                 [(conj seen key) (conj results scored)])))
           [#{} []]
           ranked)))

(defn- rank-memory-results
  [query results opts]
  (let [limit (or (:limit opts) 20)
        min-score (or (:min-score opts) 0.0)
        dedupe? (not= false (:dedupe? opts))]
    (->> (concat
          (map #(score-memory-item query :message %) (:messages results))
          (map #(score-memory-item query :event %) (:events results)))
         (sort-by :score >)
         (filter #(and (pos? (:score %)) (>= (:score %) min-score)))
         (#(if dedupe? (dedupe-ranked-results %) %))
         (take limit)
         vec)))

(defn ^:no-doc search-limit-config [search]
  (let [configured-default (policy/positive-limit (get search :default-limit) policy/default-search-limit)
        configured-max (policy/positive-limit (get search :max-limit) configured-default)]
    {:default-limit (min configured-default configured-max)
     :max-limit configured-max}))

(defn ^:no-doc search-min-score-config [search]
  (let [score (:min-score search)]
    (if (number? score)
      (max 0.0 (double score))
      policy/default-min-search-score)))

(defn- effective-search-limit [memory-service requested]
  (min (policy/positive-limit requested (:search-default-limit memory-service))
       (:search-max-limit memory-service)))

(defn search-memory
  ([memory-service query] (search-memory memory-service query {}))
  ([memory-service query opts]
   (let [limit (effective-search-limit memory-service (:limit opts))
         min-score (or (:min-score opts) (:search-min-score memory-service))
         messages (sqlite/search-messages (:store memory-service)
                                          query
                                          {:limit limit
                                           :session-id (:session-id opts)})
         events (sqlite/search-events (:store memory-service)
                                      query
                                      {:limit limit
                                       :entity-type (:entity-type opts)
                                       :entity-id (:entity-id opts)})
         results {:query query
                  :messages messages
                  :events events}]
     (assoc results :ranked (rank-memory-results query results {:limit limit
                                                                :min-score min-score
                                                                :dedupe? (:dedupe? opts)})))))

(defn- embedding-candidate-limit [memory-service requested]
  (let [configured (get-in memory-service [:config :embeddings :candidate-limit])]
    (policy/positive-limit configured (max policy/default-embedding-candidate-limit
                                    (effective-search-limit memory-service requested)))))

(defn- dot-product [left right]
  (reduce + 0.0 (map * left right)))

(defn- magnitude [values]
  (Math/sqrt (reduce + 0.0 (map #(* % %) values))))

(defn- cosine-score [left right]
  (let [left* (mapv double left)
        right* (mapv double right)
        denominator (* (magnitude left*) (magnitude right*))]
    (if (or (zero? denominator)
            (not= (count left*) (count right*)))
      0.0
      (/ (+ 1.0 (/ (dot-product left* right*) denominator)) 2.0))))

(defn- query-embedding [memory-service query]
  (when-let [provider (:embedding-provider memory-service)]
    (let [opts (cond-> {}
                 (:embedding-model memory-service)
                 (assoc :model (:embedding-model memory-service)))
          embedding (llm/embed provider query opts)]
      (when (and (sequential? embedding)
                 (every? number? embedding))
        (vec embedding)))))

(defn- vector-vault-results [memory-service query opts]
  (if (and (policy/embeddings-enabled? memory-service)
           (not (str/blank? (or query ""))))
    (try
      (let [candidates (sqlite/list-vault-chunk-embedding-candidates
                        (:store memory-service)
                        {:session-id (:session-id opts)
                         :project-id (:project-id opts)
                         :limit (embedding-candidate-limit memory-service (:limit opts))})]
        (if-let [embedding (and (seq candidates)
                                (query-embedding memory-service query))]
          (->> candidates
               (map #(-> %
                         (assoc :vector-score (cosine-score embedding (:embedding %)))
                         (dissoc :embedding)))
               (filter #(pos? (:vector-score %)))
               vec)
          []))
      (catch Exception _
        (logging/log! :agent.memory/vector-search-degraded {:reason :embedding-search-failed})
        []))
    []))

(defn- indexed-scores [items score-key]
  (let [total (max 1 (count items))]
    (map-indexed (fn [idx item]
                   (assoc item score-key
                          (if (= 1 total)
                            1.0
                            (- 1.0 (/ idx (double (dec total)))))))
                 items)))

(defn- parse-instant [value]
  (try
    (some-> value java.time.Instant/parse)
    (catch Exception _
      nil)))

(defn- recency-score [updated-at]
  (if-let [instant (parse-instant updated-at)]
    (let [days (/ (double (.toMillis (java.time.Duration/between instant (java.time.Instant/now))))
                  86400000.0)]
      (/ 1.0 (+ 1.0 (max 0.0 (/ days 30.0)))))
    0.5))

(defn- scope-score [item opts]
  (case (:iris-scope item)
    "session" (if (:session-id opts) 1.0 0.0)
    "global" 0.9
    "project" 0.85
    0.6))

(defn- hybrid-vault-score [item opts]
  (let [fts (double (or (:fts-score item) 0.0))
        vector (double (or (:vector-score item) 0.0))
        scope (scope-score item opts)
        recency (recency-score (:updated-at item))
        confidence (confidence-score (:iris-confidence item))
        surface-weight 1.0
        score (* surface-weight
                 (+ (* 0.45 fts)
                    (* 0.35 vector)
                    (* 0.08 scope)
                    (* 0.05 recency)
                    (* 0.05 confidence)
                    (* 0.02 surface-weight)))]
    [score {:fts fts
            :vector vector
            :scope scope
            :recency recency
            :confidence confidence
            :surface-weight surface-weight}]))

(defn- merge-vault-results [fts-results vector-results opts limit]
  (let [fts* (indexed-scores fts-results :fts-score)
        by-id (reduce (fn [acc item]
                        (merge-with merge acc {(:chunk-id item) item}))
                      {}
                      (concat fts* vector-results))]
    (->> (vals by-id)
         (map (fn [item]
                (let [[score breakdown] (hybrid-vault-score item opts)
                      reason (cond
                               (and (pos? (:fts breakdown))
                                    (pos? (:vector breakdown))) :hybrid-match
                               (pos? (:vector breakdown)) :semantic-match
                               :else :fts-match)]
                  (assoc item
                         :score score
                         :score-breakdown breakdown
                         :reason reason))))
         (sort-by :score >)
         (take limit)
         vec)))

(defn search-vault
  ([memory-service query] (search-vault memory-service query {}))
  ([memory-service query opts]
   (let [limit (effective-search-limit memory-service (:limit opts))
         opts* (assoc opts :limit limit)
         fts-results (sqlite/search-vault-chunks (:store memory-service)
                                                 query
                                                 opts*)
         vector-results (vector-vault-results memory-service query opts*)]
     (merge-vault-results fts-results vector-results opts* limit))))
