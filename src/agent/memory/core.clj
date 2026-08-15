(ns agent.memory.core
  "Memory service facade. Reads vault
   files from bounded roots, recalls relevant context for chat, and extracts
   candidate vault notes from explicit memory consolidation runs."
  (:require
   [agent.llm.core :as llm]
   [agent.memory.scratchpad :as scratchpad]
   [agent.memory.vault :as vault]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def default-search-limit 10)
(def default-min-search-score 0.3)
(def default-embedding-candidate-limit 1000)
(def default-low-confidence-threshold 0.6)
(def default-stale-days 180)
(def default-session-extract-limit 80)
(def max-session-extract-limit 200)
(def session-extract-message-chars 1200)
(def session-extract-transcript-chars 20000)
(def default-existing-note-context-limit 8)
(def existing-note-context-chars 1200)
(def extraction-evidence-user-chars 4000)
(def extraction-evidence-assistant-chars 1000)
(def ^:private allowed-vault-statuses #{"candidate" "approved" "auto_session" "rejected" "superseded"})
(def ^:private allowed-vault-scopes #{"global" "session" "agent" "project"})
(def ^:private allowed-vault-move-roots #{"inbox" "preferences" "decisions" "projects"
                                           "runbooks" "sessions" "references" "archive"})
(def ^:private reviewable-vault-statuses #{"candidate" "approved" "auto_session"})
(def ^:private promotable-vault-statuses #{"candidate" "rejected"})
(def ^:private promotion-folders
  {"preference" "preferences"
   "decision" "decisions"
   "runbook" "runbooks"
   "projectnote" "projects"
   "reference" "references"})

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- canonical-roots [paths]
  (mapv canonical-path paths))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- ensure-vault-path! [memory-service path]
  (let [vault-roots (:vault-roots memory-service)
        fs-roots (:fs-roots memory-service)
        target (canonical-path path)]
    (when-not (seq vault-roots)
      (throw (ex-info "Vault memory is not configured" {:type :vault-memory-disabled})))
    (when-not (within-root? vault-roots target)
      (throw (ex-info "Path is outside configured memory vault roots"
                      {:type :path-not-allowed
                       :path target
                       :roots vault-roots})))
    (when (and (seq fs-roots) (not (within-root? fs-roots target)))
      (throw (ex-info "Path is outside filesystem policy roots"
                      {:type :path-not-allowed
                       :path target
                       :roots fs-roots})))
    target))

(defn- vault-root-for-path [memory-service path]
  (let [target (canonical-path path)]
    (some #(when (or (= target %)
                     (str/starts-with? target (str % java.io.File/separator)))
             %)
          (:vault-roots memory-service))))

(defn- token-set [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

(defn- jaccard [left right]
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

(defn- confidence-score [value]
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

(defn- positive-limit [value fallback]
  (if (and (integer? value) (pos? value))
    value
    fallback))

(defn- search-limit-config [search]
  (let [configured-default (positive-limit (get search :default-limit) default-search-limit)
        configured-max (positive-limit (get search :max-limit) configured-default)]
    {:default-limit (min configured-default configured-max)
     :max-limit configured-max}))

(defn- search-min-score-config [search]
  (let [score (:min-score search)]
    (if (number? score)
      (max 0.0 (double score))
      default-min-search-score)))

(defn- effective-search-limit [memory-service requested]
  (min (positive-limit requested (:search-default-limit memory-service))
       (:search-max-limit memory-service)))

(defn create-memory-service
  ([cfg store] (create-memory-service cfg store {}))
  ([{:keys [search vault fs-roots] :as cfg} store {:keys [embedding-provider embedding-model]}]
  (let [{:keys [default-limit max-limit]} (search-limit-config search)]
    {:config cfg
     :search-default-limit default-limit
     :search-max-limit max-limit
     :search-min-score (search-min-score-config search)
     :vault-roots (canonical-roots (get vault :paths []))
     :vault-writable? (true? (:writable? vault))
     :fs-roots (canonical-roots (or fs-roots []))
     :embedding-provider embedding-provider
     :embedding-model embedding-model
     :update-lock (Object.)
     :recall-metrics (atom {:count 0
                            :last-latency-ms nil
                            :max-latency-ms nil})
     :store store})))

(defn list-surfaces
  [memory-service]
  [{:name :search
    :type :sqlite
    :writable false
    :default-limit (:search-default-limit memory-service)
    :max-limit (:search-max-limit memory-service)
    :min-score (:search-min-score memory-service)}
   {:name :vault
    :type :file
    :writable (:vault-writable? memory-service)
    :enabled (boolean (seq (:vault-roots memory-service)))
    :paths (:vault-roots memory-service)}])

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

(defn reindex-vault!
  [memory-service]
  (vault/reindex! memory-service))

(defn- embeddings-enabled? [memory-service]
  (true? (get-in memory-service [:config :embeddings :enabled?])))

(defn- embedding-candidate-limit [memory-service requested]
  (let [configured (get-in memory-service [:config :embeddings :candidate-limit])]
    (positive-limit configured (max default-embedding-candidate-limit
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
  (if (and (embeddings-enabled? memory-service)
           (not (str/blank? (or query ""))))
    (try
      (let [candidates (sqlite/list-vault-chunk-embedding-candidates
                        (:store memory-service)
                        {:session-id (:session-id opts)
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

(defn read-vault-file
  [memory-service path]
  (let [path* (ensure-vault-path! memory-service path)
        file (io/file path*)]
    (when-not (.isFile file)
      (throw (ex-info "Vault file not found" {:type :not-found :path path*})))
    (let [content (slurp file)]
      {:path path*
       :content content
       :revision (vault/content-revision content)})))

(defn- normalize-vault-iris-change [{:keys [scope status]}]
  (let [scope* (some-> scope name)
        status* (some-> status name)]
    (when (and scope* (not (allowed-vault-scopes scope*)))
      (throw (ex-info "Unsupported vault note scope"
                      {:type :invalid-vault-note-scope
                       :scope scope*
                       :allowed allowed-vault-scopes})))
    (when (and status* (not (allowed-vault-statuses status*)))
      (throw (ex-info "Unsupported vault note status"
                      {:type :invalid-vault-note-status
                       :status status*
                       :allowed allowed-vault-statuses})))
    (cond-> {}
      scope* (assoc :scope scope*)
      status* (assoc :status status*))))

(defn update-vault-note-iris!
  [memory-service path changes]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [path* (ensure-vault-path! memory-service path)
        changes* (normalize-vault-iris-change changes)
        result (vault/update-note-iris! path* changes*)]
    (reindex-vault! memory-service)
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.vault.note_updated
                        :entity-type :memory
                        :entity-id path*
                        :payload result})
    result))

(defn- normalized-note-type [value]
  (-> (or value "Reference")
      str/lower-case
      (str/replace #"[^a-z0-9]" "")))

(defn- promotion-folder [scope type]
  (if (= "session" scope)
    "sessions"
    (get promotion-folders (normalized-note-type type) "references")))

(defn- current-note-metadata [path]
  (let [{:keys [frontmatter]} (vault/parse-note-content (slurp path) path)
        iris (:iris frontmatter)]
    {:type (or (:type frontmatter) "Reference")
     :scope (or (:scope iris) "global")
     :status (or (:status iris) "candidate")}))

(defn promote-vault-note!
  "Move a candidate/rejected note into its deterministic durable folder and
   mark it approved. The file, index, and audit event are rolled back together
   when promotion fails."
  [memory-service path changes]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [source-path (ensure-vault-path! memory-service path)
        root (or (vault-root-for-path memory-service source-path)
                 (throw (ex-info "Path is outside configured memory vault roots"
                                 {:type :path-not-allowed
                                  :path source-path
                                  :roots (:vault-roots memory-service)})))
        before (slurp source-path)
        {:keys [type scope status]} (current-note-metadata source-path)
        normalized-changes (normalize-vault-iris-change (assoc changes :status "approved"))
        scope* (or (:scope normalized-changes) scope)
        changes* (assoc normalized-changes :scope scope*)
        folder (promotion-folder scope* type)
        target-path (.getCanonicalPath
                     (io/file root folder (.getName (io/file source-path))))
        move? (not= source-path target-path)]
    (when-not (contains? promotable-vault-statuses status)
      (throw (ex-info "Vault Note cannot be promoted from its current status"
                      {:type :vault-note-not-promotable
                       :path source-path
                       :status status
                       :allowed promotable-vault-statuses})))
    (ensure-vault-path! memory-service target-path)
    (try
      (vault/update-note-iris! source-path changes*)
      (when move?
        (vault/move-note! source-path target-path))
      (reindex-vault! memory-service)
      (let [result {:from source-path
                    :path target-path
                    :folder folder
                    :moved move?
                    :updated true
                    :iris (select-keys changes* [:scope :status])}]
        (sqlite/log-event! (:store memory-service)
                           {:event-type :memory.vault.note_promoted
                            :entity-type :memory
                            :entity-id target-path
                            :payload result})
        result)
      (catch Exception e
        (try
          (when (and move?
                     (.isFile (io/file target-path))
                     (not (.exists (io/file source-path))))
            (vault/move-note! target-path source-path))
          (spit source-path before)
          (reindex-vault! memory-service)
          (catch Exception rollback-error
            (throw (ex-info "Vault Note promotion and rollback failed"
                            {:type :vault-note-promotion-rollback-failed
                             :path source-path
                             :target target-path
                             :promotion-error (.getMessage e)
                             :rollback-error (.getMessage rollback-error)}
                            rollback-error))))
        (throw e)))))

(defn approved-inbox-notes [memory-service]
  (->> (sqlite/list-vault-notes (:store memory-service)
                                {:status "approved" :limit 10000})
       (filter (fn [{:keys [path]}]
                 (= "inbox" (some-> path io/file .getParentFile .getName str/lower-case))))
       vec))

(defn- safe-vault-folder [folder]
  (let [folder* (str/replace (str/trim (or folder "")) #"\\" "/")
        parts (remove str/blank? (str/split folder* #"/"))
        top (first parts)]
    (when (or (str/blank? folder*)
              (str/starts-with? folder* "/")
              (some #(#{"." ".."} %) parts)
              (not (contains? allowed-vault-move-roots top)))
      (throw (ex-info "Unsupported vault note target folder"
                      {:type :invalid-vault-note-folder
                       :folder folder
                       :allowed allowed-vault-move-roots})))
    (str/join java.io.File/separator parts)))

(defn move-vault-note!
  [memory-service path folder]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [source-path (ensure-vault-path! memory-service path)
        root (or (vault-root-for-path memory-service source-path)
                 (throw (ex-info "Path is outside configured memory vault roots"
                                 {:type :path-not-allowed
                                  :path source-path
                                  :roots (:vault-roots memory-service)})))
        folder* (safe-vault-folder folder)
        target-path (.getCanonicalPath (io/file root folder* (.getName (io/file source-path))))]
    (ensure-vault-path! memory-service target-path)
    (let [result (vault/move-note! source-path target-path)]
      (reindex-vault! memory-service)
      (sqlite/log-event! (:store memory-service)
                         {:event-type :memory.vault.note_moved
                          :entity-type :memory
                          :entity-id (:path result)
                          :payload result})
      result)))

(def ^:private allowed-note-update-fields
  #{:type :title :description :body :tags :scope})

(defn- normalize-note-update-changes [changes]
  (let [changes* (select-keys (or changes {}) allowed-note-update-fields)]
    (when (empty? changes*)
      (throw (ex-info "Memory update must change at least one supported field"
                      {:type :validation-failed
                       :allowed-fields allowed-note-update-fields})))
    (doseq [field [:type :title :description :body]]
      (when (and (contains? changes* field)
                 (not (string? (get changes* field))))
        (throw (ex-info "Memory update text field must be a string"
                        {:type :validation-failed :field field}))))
    (when (and (contains? changes* :tags)
               (not (and (sequential? (:tags changes*))
                         (every? string? (:tags changes*)))))
      (throw (ex-info "Memory update tags must be strings"
                      {:type :validation-failed :field :tags})))
    (let [scope (some-> (:scope changes*) name)]
      (when (and scope (not (allowed-vault-scopes scope)))
        (throw (ex-info "Unsupported vault note scope"
                        {:type :invalid-vault-note-scope
                         :scope scope
                         :allowed allowed-vault-scopes})))
      (cond-> changes*
        (contains? changes* :tags) (update :tags #(vec (distinct %)))
        scope (assoc :scope scope)))))

(defn- indexed-note-by-id [memory-service note-id]
  (sqlite/get-vault-note-by-id (:store memory-service) note-id))

(defn- note-update-diff [before after changes]
  (str/join
   "\n\n"
   (for [field (filter #(contains? changes %) [:type :title :description :tags :scope :body])]
     (str "## " (name field)
          "\n- " (pr-str (get before field))
          "\n+ " (pr-str (get after field))))))

(defn get-memory-note-update [memory-service update-id]
  (sqlite/get-memory-note-update (:store memory-service) update-id))

(defn list-memory-note-updates
  ([memory-service] (list-memory-note-updates memory-service {}))
  ([memory-service opts]
   (sqlite/list-memory-note-updates (:store memory-service) opts)))

(defn propose-vault-note-update!
  [memory-service note-id expected-revision changes opts]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (locking (:update-lock memory-service)
   (let [note (or (indexed-note-by-id memory-service note-id)
                 (throw (ex-info "Vault Note not found"
                                 {:type :not-found :note-id note-id})))
        _ (when-not (= "approved" (:iris-status note))
            (throw (ex-info "Only approved Vault Notes use update proposals"
                            {:type :vault-note-update-not-approved
                             :note-id note-id
                             :status (:iris-status note)})))
        path (ensure-vault-path! memory-service (:path note))
        before-content (slurp path)
        base-revision (vault/content-revision before-content)
        _ (when-not (= expected-revision base-revision)
            (throw (ex-info "Vault Note revision changed"
                            {:type :stale-vault-note-revision
                             :note-id note-id
                             :expected-revision expected-revision
                             :actual-revision base-revision})))
        before-values (vault/note-change-values before-content)
        changes* (->> (normalize-note-update-changes changes)
                      (remove (fn [[field value]]
                                (= value (get before-values field))))
                      (into {}))]
    (if (empty? changes*)
      {:noop true
       :target-id note-id
      :base-revision base-revision}
      (let [proposed-content (vault/proposed-note-content
                              before-content
                              changes*
                              (:evidence opts)
                              (or (:origins opts)
                                  [{:type (name (or (:source opts) :tool))
                                    :session-id (:session-id opts)
                                    :request-id (:request-id opts)}]))
            proposed-revision (vault/content-revision proposed-content)]
        (if-let [existing (some #(when (= proposed-revision (:proposed-revision %)) %)
                                (sqlite/list-memory-note-updates
                                 (:store memory-service)
                                 {:status "pending" :target-id note-id :limit 100}))]
          (assoc existing :duplicate true)
          (let [after-values (vault/note-change-values proposed-content)
              update (sqlite/create-memory-note-update!
                        (:store memory-service)
                        {:id (str "memupd_" (java.util.UUID/randomUUID))
                         :target-id note-id
                         :target-path path
                         :base-revision base-revision
                         :proposed-revision proposed-revision
                         :changes changes*
                         :proposed-content proposed-content
                         :diff (note-update-diff before-values after-values changes*)
                         :evidence (:evidence opts)
                         :source (or (:source opts) :tool)
                         :status :pending})]
            (sqlite/log-event! (:store memory-service)
                               {:event-type :memory.vault.update_proposed
                                :entity-type :memory_note_update
                                :entity-id (:id update)
                                :request-id (:request-id opts)
                                :payload (dissoc update :proposed-content)})
            update)))))))

(defn decide-memory-note-update!
  [memory-service update-id status decision reason]
  (locking (:update-lock memory-service)
    (let [result (sqlite/update-memory-note-update-status!
                  (:store memory-service) update-id "pending" status decision reason)]
      (when (= "pending" (:status result))
        (throw (ex-info "Memory update proposal decision conflict"
                        {:type :memory-update-decision-conflict
                         :update-id update-id})))
      result)))

(defn apply-memory-note-update!
  [memory-service update-id reason]
  (locking (:update-lock memory-service)
    (let [update (or (get-memory-note-update memory-service update-id)
                     (throw (ex-info "Memory update proposal not found"
                                     {:type :not-found :update-id update-id})))]
      (when-not (= "pending" (:status update))
        (throw (ex-info "Memory update proposal is not pending"
                        {:type :memory-update-not-pending
                         :update-id update-id
                         :status (:status update)})))
      (let [path (ensure-vault-path! memory-service (:target-path update))
            before (slurp path)
            actual-revision (vault/content-revision before)]
        (if (not= (:base-revision update) actual-revision)
          (let [result (decide-memory-note-update!
                        memory-service update-id :superseded :stale
                        "Target note changed after proposal creation")]
            (sqlite/log-event! (:store memory-service)
                               {:event-type :memory.vault.update_superseded
                                :entity-type :memory_note_update
                                :entity-id update-id
                                :payload {:target-id (:target-id update)
                                          :base-revision (:base-revision update)
                                          :actual-revision actual-revision}})
            result)
          (try
            (vault/replace-note-content! path actual-revision (:proposed-content update))
            (let [index-result (reindex-vault! memory-service)]
              (when-not (:ok? index-result)
                (throw (ex-info "Vault reindex failed after memory update"
                                {:type :vault-reindex-failed
                                 :report index-result})))
              (let [result (decide-memory-note-update!
                            memory-service update-id :applied :yes reason)]
                (sqlite/log-event! (:store memory-service)
                                   {:event-type :memory.vault.update_applied
                                    :entity-type :memory_note_update
                                    :entity-id update-id
                                    :payload {:target-id (:target-id update)
                                              :path path
                                              :base-revision actual-revision
                                              :revision (:proposed-revision update)}})
                result))
            (catch Exception e
              (when (= (:proposed-revision update)
                       (vault/content-revision (slurp path)))
                (vault/replace-note-content! path (:proposed-revision update) before)
                (reindex-vault! memory-service))
              (throw e))))))))

(defn- effective-scratchpad-scope [scope opts]
  (scratchpad/normalize-scope
   (or scope
       (when-let [session-id (:session-id opts)]
         {:type :session :id session-id})
       {:type :global})))

(defn read-scratchpad
  ([memory-service] (read-scratchpad memory-service {}))
  ([memory-service opts]
   (scratchpad/read-scratchpad memory-service (effective-scratchpad-scope (:scope opts) opts))))

(defn search-scratchpad
  [memory-service query opts]
  (scratchpad/search-scratchpad memory-service
                                (effective-scratchpad-scope (:scope opts) opts)
                                query))

(defn replace-scratchpad!
  [memory-service {:keys [old-text new-text expected-revision scope] :as opts}]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [scope* (effective-scratchpad-scope scope opts)
        result (scratchpad/replace-scratchpad! memory-service
                                               scope*
                                               (or old-text "")
                                               (or new-text "")
                                               expected-revision)]
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.scratchpad.replaced
                        :entity-type :memory
                        :entity-id (:path result)
                        :payload (select-keys result [:scope :path :revision :previous-revision])})
    result))

(defn- extraction-schema []
  {:type "object"
   :additionalProperties false
   :properties {:notes {:type "array"
                        :items {:type "object"
                                :additionalProperties false
                                :properties {:operation {:type "string"
                                                         :enum ["create" "update"]
                                                         :description "Create a new memory note or propose an update to an approved note."}
                                             :target_id {:type ["string" "null"]
                                                         :description "Required for update; id of an existing approved note."}
                                             :expected_revision {:type ["string" "null"]
                                                                 :description "Required for update; revision supplied in existing_notes."}
                                             :type {:type "string"
                                                    :description "OKF note type, for example Preference, Decision, ProjectNote, Runbook, or Reference."}
                                             :title {:type "string"
                                                     :description "Short stable note title."}
                                             :description {:type "string"
                                                           :description "One sentence summary."}
                                             :body {:type "string"
                                                    :description "Concise Markdown body. Do not include secrets, credentials, transient chat details, or unsupported guesses."}
                                             :tags {:type "array"
                                                    :items {:type "string"}}
                                             :scope {:type "string"
                                                     :enum ["global" "session" "agent" "project"]
                                                     :description "Scope the candidate note would belong to after review."}
                                             :confidence {:type "number"
                                                          :description "Confidence from 0.0 to 1.0 that this is durable supported memory."}
                                             :evidence {:type "string"
                                                        :description "Short decisive evidence for this note. Cite relevant message or event ids; never copy the full transcript."}}
                                :required ["operation" "type" "title" "description" "body"]}}}
   :required ["notes"]})

(defn- parse-note-response [content]
  (let [value (cond
                (map? content) content
                (str/blank? (or content "")) {}
                :else (json/parse-string content true))
        notes (if (vector? value) value (:notes value))]
    (->> notes
         (filter map?)
         (filter #(and (#{"create" "update"} (:operation %))
                       (every? (fn [k] (string? (get % k)))
                               [:title :description :body])))
         (mapv (fn [note]
                 (-> note
                     (select-keys [:operation :target_id :expected_revision
                                   :type :title :description :body :tags :scope :confidence
                                   :evidence])
                     (set/rename-keys {:target_id :target-id
                                       :expected_revision :expected-revision})
                     (update :type #(or % "Reference"))
                     (update :tags #(vec (or % [])))))))))

(defn- extractor-format [extractor]
  (let [format (or (:format extractor) :json-schema)]
    (case format
      (:json-schema "json-schema") :json-schema
      (:json-object "json-object") :json-object
      (throw (ex-info "Unsupported memory note extractor format"
                      {:type :unsupported-note-extractor-format
                       :format format
                       :allowed [:json-schema :json-object]})))))

(defn- output-options [extractor]
  (case (extractor-format extractor)
    :json-schema
    {:structured-output {:name "memory_notes"
                         :strict? (not (false? (:strict? extractor)))
                         :schema (extraction-schema)}}

    :json-object
    {:response-format {:type "json_object"}}))

(defn extract-notes
  [provider {:keys [user-message assistant-message existing-notes model session-id extractor]}]
  (let [prompt-name (name (or (:prompt extractor) "note-extraction"))
        response (llm/invoke
                  provider
                  (merge
                   {:model model
                    :session-id session-id
                    :temperature 0.0
                    :messages [{:role "system"
                                :content (prompts/load-prompt prompt-name)}
                               {:role "user"
                                :content (json/generate-string
                                          {:user user-message
                                           :assistant assistant-message
                                           :existing_notes (vec (or existing-notes []))})}]}
                   (output-options extractor)))]
    (parse-note-response (:content response))))

(defn- note-origins [opts]
  (let [message-ids (vec (:source-message-ids opts))
        event-ids (vec (:source-event-ids opts))]
    [(cond-> {:type (or (:source-type opts) "extraction")}
       (:session-id opts) (assoc :session-id (:session-id opts))
       (:source-request-id opts) (assoc :request-id (:source-request-id opts))
       (seq message-ids) (assoc :message-id-start (first message-ids)
                                :message-id-end (last message-ids)
                                :message-count (count message-ids))
       (seq event-ids) (assoc :event-id-start (first event-ids)
                              :event-id-end (last event-ids)
                              :event-count (count event-ids)))]))

(defn- bounded-evidence [value limit]
  (util/truncate (or value "") limit #(str "\n\n[evidence truncated " % " chars]")))

(defn- note-evidence [exchange note]
  {:user (bounded-evidence (or (:evidence note) (:user-message exchange))
                           extraction-evidence-user-chars)
   :assistant (bounded-evidence (:assistant-message exchange)
                                extraction-evidence-assistant-chars)})

(defn- note-scope [memory-service note]
  (let [scope (or (:scope note)
                  (name (or (get-in memory-service [:config :notes :default-scope])
                            :session)))]
    (if (#{"global" "session" "agent" "project"} scope)
      scope
      "session")))

(defn- normalized-note-title [note]
  (-> (:title note)
      (or "")
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn- note-search-text [note]
  (str (:title note) "\n" (:description note) "\n" (:body note)))

(defn- reviewable-existing-note? [note]
  (contains? reviewable-vault-statuses (:iris-status note)))

(defn- duplicate-note? [existing note]
  (let [title (normalized-note-title note)
        text (note-search-text note)]
    (some (fn [candidate]
            (when (reviewable-existing-note? candidate)
              (let [candidate-title (normalized-note-title candidate)
                    candidate-text (str (:title candidate) "\n" (:description candidate))]
                (or (and (not (str/blank? title))
                         (= title candidate-title))
                    (>= (jaccard text candidate-text) 0.88)))))
          existing)))

(defn- filter-extracted-notes [memory-service notes opts]
  (let [min-confidence (:min-confidence opts)
        existing (when (:dedupe? opts)
                   (sqlite/list-vault-notes (:store memory-service) {:limit 10000}))]
    (second
     (reduce
      (fn [[seen kept] note]
        (let [title (normalized-note-title note)
              operation (:operation note)
              seen-key (if (= "update" operation)
                         [operation (:target-id note)]
                         [operation title])]
          (if (or (contains? seen seen-key)
                  (and min-confidence
                       (< (confidence-score (:confidence note))
                          (double min-confidence)))
                  (and (= "create" operation)
                       existing
                       (duplicate-note? existing note)))
            [seen kept]
            [(conj seen seen-key)
             (conj kept note)])))
      [#{} []]
      notes))))

(defn- existing-note-contexts [memory-service transcript]
  (->> (sqlite/list-vault-notes (:store memory-service)
                                {:status "approved" :limit 200})
       (map (fn [note]
              (let [content (:content (read-vault-file memory-service (:path note)))
                    values (vault/note-change-values content)
                    score (jaccard transcript
                                   (str (:title note) "\n"
                                        (:description note) "\n"
                                        (:body values)))]
                {:score score
                 :id (:id note)
                 :revision (or (:revision note) (vault/content-revision content))
                 :type (:type values)
                 :title (:title values)
                 :description (:description values)
                 :body (util/truncate (:body values)
                                      existing-note-context-chars
                                      #(str " [truncated " % " chars]"))
                 :tags (:tags values)
                 :scope (:scope values)})))
       (sort-by (juxt :score :title) #(compare %2 %1))
       (take default-existing-note-context-limit)
       (mapv #(dissoc % :score))))

(defn- save-extracted-memory!
  [memory-service exchange opts note]
  (let [origins (note-origins opts)
        evidence (note-evidence exchange note)]
    (case (:operation note)
      "create"
      (vault/write-candidate-note!
       memory-service
       (merge note
              {:scope (note-scope memory-service note)
               :origins origins
               :source-request-id (:source-request-id opts)
               :evidence evidence}))

      "update"
      (when (and (not (str/blank? (:target-id note)))
                 (not (str/blank? (:expected-revision note))))
        (let [result (propose-vault-note-update!
                      memory-service
                      (:target-id note)
                      (:expected-revision note)
                      (-> note
                          (select-keys [:type :title :description :body :tags :scope])
                          (update :scope #(or % (note-scope memory-service note))))
                      {:source (or (:source-type opts) :extraction)
                       :request-id (:source-request-id opts)
                       :origins origins
                       :evidence evidence})]
          (when-not (or (:noop result) (:duplicate result))
            result)))

      nil)))

(defn extract-and-save-notes!
  [memory-service provider exchange opts]
  (let [extractor (merge (get-in memory-service [:config :notes :extractor])
                         (:extractor opts))]
    (if (false? (:enabled extractor))
      []
      (try
        (let [model (or (:model extractor) (:model opts))
              existing-notes (existing-note-contexts memory-service (:user-message exchange))
              notes (filter-extracted-notes
                     memory-service
                     (extract-notes provider (assoc exchange
                                                    :model model
                                                    :session-id (:session-id opts)
                                                    :existing-notes existing-notes
                                                    :extractor extractor))
                     opts)
              saved (vec (keep #(save-extracted-memory! memory-service exchange opts %) notes))
              created (filter :path saved)]
          (when (seq created)
            (reindex-vault! memory-service)
            nil)
          (when (seq saved)
            (sqlite/log-event! (:store memory-service)
                               {:event-type :memory.notes.extracted
                                :entity-type :session
                                :entity-id (:session-id opts)
                                :request-id (:source-request-id opts)
                                :payload {:note-count (count saved)
                                          :created-count (count created)
                                          :update-count (count (remove :path saved))
                                          :paths (mapv :path created)
                                          :update-ids (mapv :id (remove :path saved))}}))
          saved)
        (catch Exception e
          (sqlite/log-event! (:store memory-service)
                             {:event-type :memory.notes.extraction_failed
                              :entity-type :session
                              :entity-id (:session-id opts)
                              :request-id (:source-request-id opts)
                              :payload {:message (.getMessage e)}})
          (when (:throw? opts)
            (throw e))
          [])))))

(defn- extraction-limit [value]
  (min max-session-extract-limit
       (positive-limit value default-session-extract-limit)))

(defn- message-transcript-line [{:keys [id role content]}]
  (str "[" id "] " role ": "
       (util/truncate content
                      session-extract-message-chars
                      #(str " [truncated " % " chars]"))))

(defn- session-transcript [memory-service session-id limit]
  (let [messages (sqlite/list-messages (:store memory-service) session-id)
        selected (take-last (extraction-limit limit) messages)
        transcript (-> (str/join "\n\n" (map message-transcript-line selected))
                       (util/truncate session-extract-transcript-chars
                                      #(str "\n\n[transcript truncated " % " chars]")))]
    {:session-id session-id
     :total-message-count (count messages)
     :included-message-count (count selected)
     :messages (vec selected)
     :transcript transcript}))

(defn extract-session-and-save-notes!
  "Manual memory consolidation entry point. Scans a bounded session transcript
   and writes durable findings as candidate vault notes."
  [memory-service provider {:keys [session-id limit request-id model]}]
  (when (str/blank? (or session-id ""))
    (throw (ex-info "session-id is required" {:type :validation-failed})))
  (when-not provider
    (throw (ex-info "Memory note extractor provider is not configured"
                    {:type :memory-extractor-provider-missing})))
  (let [{:keys [messages transcript] :as source} (session-transcript memory-service session-id limit)]
    (if (empty? messages)
      (assoc (dissoc source :messages :transcript)
             :note-count 0
             :created-count 0
             :update-count 0
             :paths []
             :update-ids [])
      (let [saved (extract-and-save-notes!
                   memory-service
                   provider
                   {:user-message transcript
                    :assistant-message "Manual end-of-dialogue memory consolidation."}
                   {:session-id session-id
                    :source-session-id session-id
                    :source-message-ids (mapv #(str (:id %)) messages)
                    :source-request-id request-id
                    :model model})
            created (filter :path saved)
            updates (remove :path saved)]
        (assoc (dissoc source :messages :transcript)
               :note-count (count saved)
               :created-count (count created)
               :update-count (count updates)
               :paths (mapv :path created)
               :update-ids (mapv :id updates))))))

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
                                 default-low-confidence-threshold)
     :stale-days (if (and (integer? (:stale-days cfg))
                          (pos? (:stale-days cfg)))
                   (:stale-days cfg)
                   default-stale-days)}))

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
  (contains? reviewable-vault-statuses (:iris-status note)))

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
  (let [enabled? (embeddings-enabled? memory-service)
        desired-notes (filter #(= "approved" (:iris-status %)) notes)
        desired-chunks (filter #(contains? (set (map :path desired-notes)) (:path %)) chunks)
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
