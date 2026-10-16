(ns agent.memory.notes
  "Vault note lifecycle, promotion, backup and index maintenance."
  (:require
   [agent.memory.vault :as vault]
   [agent.persistence.sqlite :as sqlite]
   [agent.util :as util]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [agent.memory.paths :as paths]
   [agent.memory.policy :as policy]
   [agent.memory.search :as search]))

(defn reindex-vault!
  [memory-service]
  (vault/reindex! memory-service))

(defn read-vault-file
  [memory-service path]
  (let [path* (paths/ensure-vault-path! memory-service path)
        file (io/file path*)]
    (when-not (.isFile file)
      (throw (ex-info "Vault file not found" {:type :not-found :path path*})))
    (let [content (slurp file)]
      {:path path*
       :content content
       :revision (vault/content-revision content)})))

(defn- origin-value [origin key]
  (let [underscore-key (str/replace (name key) "-" "_")]
    (or (get origin key)
        (get origin (keyword underscore-key))
        (get origin (name key))
        (get origin underscore-key))))

(defn- note-scope-owner [note]
  (let [scope (:iris-scope note)
        origins (:origins note)]
    [scope
     (case scope
       "project" (some #(origin-value % :project-id) origins)
       "session" (some #(origin-value % :session-id) origins)
       "agent" (some #(origin-value % :agent-id) origins)
       nil)]))

(defn- comparable-note-scope? [left right]
  (let [[left-scope left-owner] (note-scope-owner left)
        [right-scope right-owner] (note-scope-owner right)]
    (and (= left-scope right-scope)
         (if (= "global" left-scope)
           true
           (and (some? left-owner) (= left-owner right-owner))))))

(defn- similar-note-score [left right]
  (let [title (search/jaccard (:title left) (:title right))
        description (search/jaccard (:description left) (:description right))
        body (search/jaccard (:body left) (:body right))
        combined (search/jaccard (str (:title left) "\n" (:description left) "\n" (:body left))
                          (str (:title right) "\n" (:description right) "\n" (:body right)))
        score (+ (* 0.35 title) (* 0.15 description) (* 0.30 body) (* 0.20 combined))]
    {:score score
     :score-breakdown {:title title :description description :body body :combined combined}}))

(defn similar-vault-notes
  "Return lexical candidates for semantic merge review. Scope owners never cross."
  ([memory-service] (similar-vault-notes memory-service {}))
  ([memory-service {:keys [threshold limit]
                    :or {threshold policy/default-similar-note-threshold
                         limit policy/default-similar-note-limit}}]
   (let [notes (->> (sqlite/list-vault-notes (:store memory-service)
                                             {:status "approved" :limit 200})
                    (mapv (fn [note]
                            (let [content (:content (read-vault-file memory-service (:path note)))
                                  values (vault/note-change-values content)]
                              (merge note
                                     (select-keys values [:type :title :description :body :tags :scope]))))))
         pending-pairs (->> (sqlite/list-memory-note-updates (:store memory-service)
                                                            {:status "pending" :operation :note-merge :limit 200})
                            (map (fn [{:keys [target-id secondary-target-id]}]
                                   #{target-id secondary-target-id}))
                            set)]
     (->> (for [left-index (range (count notes))
                right-index (range (inc left-index) (count notes))
                :let [left (nth notes left-index)
                      right (nth notes right-index)
                      pair #{(:id left) (:id right)}]
                :when (and (comparable-note-scope? left right)
                           (not (contains? pending-pairs pair)))
                :let [{:keys [score score-breakdown]} (similar-note-score left right)]
                :when (>= score (double threshold))]
            {:score score
             :score-breakdown score-breakdown
             :scope (:iris-scope left)
             :scope-owner (second (note-scope-owner left))
             :left (select-keys left [:id :revision :type :title :description :body :tags])
             :right (select-keys right [:id :revision :type :title :description :body :tags])})
          (sort-by :score >)
          (take (max 1 (min 100 (long limit))))
          vec))))

(defn- normalize-vault-iris-change [{:keys [scope status]}]
  (let [scope* (some-> scope name)
        status* (some-> status name)]
    (when (and scope* (not (policy/allowed-vault-scopes scope*)))
      (throw (ex-info "Unsupported vault note scope"
                      {:type :invalid-vault-note-scope
                       :scope scope*
                       :allowed policy/allowed-vault-scopes})))
    (when (and status* (not (policy/allowed-vault-statuses status*)))
      (throw (ex-info "Unsupported vault note status"
                      {:type :invalid-vault-note-status
                       :status status*
                       :allowed policy/allowed-vault-statuses})))
    (cond-> {}
      scope* (assoc :scope scope*)
      status* (assoc :status status*))))

(defn update-vault-note-iris!
  [memory-service path changes]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [path* (paths/ensure-vault-path! memory-service path)
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
    (get policy/promotion-folders (normalized-note-type type) "references")))

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
  (let [source-path (paths/ensure-vault-path! memory-service path)
        root (or (paths/vault-root-for-path memory-service source-path)
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
    (when-not (contains? policy/promotable-vault-statuses status)
      (throw (ex-info "Vault Note cannot be promoted from its current status"
                      {:type :vault-note-not-promotable
                       :path source-path
                       :status status
                       :allowed policy/promotable-vault-statuses})))
    (paths/ensure-vault-path! memory-service target-path)
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

(defn ^:no-doc safe-vault-folder [folder]
  (let [folder* (str/replace (str/trim (or folder "")) #"\\" "/")
        parts (remove str/blank? (str/split folder* #"/"))
        top (first parts)]
    (when (or (str/blank? folder*)
              (str/starts-with? folder* "/")
              (some #(#{"." ".."} %) parts)
              (not (contains? policy/allowed-vault-move-roots top)))
      (throw (ex-info "Unsupported vault note target folder"
                      {:type :invalid-vault-note-folder
                       :folder folder
                       :allowed policy/allowed-vault-move-roots})))
    (str/join java.io.File/separator parts)))

(defn move-vault-note!
  [memory-service path folder]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [source-path (paths/ensure-vault-path! memory-service path)
        root (or (paths/vault-root-for-path memory-service source-path)
                 (throw (ex-info "Path is outside configured memory vault roots"
                                 {:type :path-not-allowed
                                  :path source-path
                                  :roots (:vault-roots memory-service)})))
        folder* (safe-vault-folder folder)
        target-path (.getCanonicalPath (io/file root folder* (.getName (io/file source-path))))]
    (paths/ensure-vault-path! memory-service target-path)
    (let [result (vault/move-note! source-path target-path)]
      (reindex-vault! memory-service)
      (sqlite/log-event! (:store memory-service)
                         {:event-type :memory.vault.note_moved
                          :entity-type :memory
                          :entity-id (:path result)
                          :payload result})
      result)))

(defn- inbox-path? [path]
  (= "inbox" (some-> path io/file .getParentFile .getName str/lower-case)))

(defn- note-index-entry [root path]
  (let [relative (str (.relativize (.toPath (io/file root)) (.toPath (io/file path))))
        title (or (:title (vault/note-change-values (slurp path)))
                  (.getName (io/file path)))]
    (str "- [" title "](" (str/replace relative "\\" "/") ")")))

(defn generate-vault-indexes!
  "Regenerate root and first-level folder indexes from physical note paths."
  [memory-service]
  (doseq [root (:vault-roots memory-service)]
    (let [files (->> (file-seq (io/file root))
                     (filter #(.isFile %))
                     (filter #(str/ends-with? (str/lower-case (.getName %)) ".md"))
                     (remove #(= "index.md" (str/lower-case (.getName %))))
                     (sort-by #(.getCanonicalPath %))
                     vec)
          groups (group-by #(or (some-> % .getParentFile .getName) ".") files)]
      (spit (io/file root "index.md")
            (str "# Memory index\n\n"
                 (str/join "\n" (map #(note-index-entry root (.getCanonicalPath %)) files))
                 "\n"))
      (doseq [[folder folder-files] groups
              :when (policy/allowed-vault-move-roots folder)]
        (spit (io/file root folder "index.md")
              (str "# " (str/capitalize folder) "\n\n"
                   (str/join "\n" (map #(note-index-entry (io/file root folder)
                                                           (.getCanonicalPath %))
                                         folder-files))
                   "\n")))))
  (reindex-vault! memory-service))

(defn- copy-tree! [source target]
  (doseq [file (file-seq (io/file source))]
    (let [relative (.relativize (.toPath (io/file source)) (.toPath file))
          destination (.resolve (.toPath (io/file target)) relative)]
      (if (.isDirectory file)
        (java.nio.file.Files/createDirectories
         destination (make-array java.nio.file.attribute.FileAttribute 0))
        (java.nio.file.Files/copy
         (.toPath file) destination
         (into-array java.nio.file.CopyOption
                     [java.nio.file.StandardCopyOption/COPY_ATTRIBUTES]))))))

(defn backup-vault! [memory-service]
  (let [stamp (str/replace (util/now-str) #"[:.]" "-")]
    (mapv (fn [root]
            (let [root-file (io/file root)
                  backup-root (io/file (.getParentFile root-file)
                                       "memory-backups"
                                       stamp
                                       (.getName root-file))]
              (copy-tree! root-file backup-root)
              (.getCanonicalPath backup-root)))
          (:vault-roots memory-service))))

(defn cleanup-vault!
  "Apply safe mechanical cleanup after an external backup: remove rejected
   drafts, compact approved evidence/origins, move approved inbox notes, and
   regenerate indexes. Exact-title duplicates are reported, never merged."
  ([memory-service] (cleanup-vault! memory-service {}))
  ([memory-service {:keys [apply?] :or {apply? false}}]
   (when-not (:vault-writable? memory-service)
     (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
   (let [notes (sqlite/list-vault-notes (:store memory-service) {:limit 10000})
         rejected (filter #(= "rejected" (:iris-status %)) notes)
         approved (filter #(= "approved" (:iris-status %)) notes)
         inbox (filter #(inbox-path? (:path %)) approved)
         duplicates (->> approved
                         (group-by #(-> (or (:title %) "")
                                        str/lower-case
                                        (str/replace #"\s+" " ")
                                        str/trim))
                         (keep (fn [[title matches]]
                                 (when (and (not (str/blank? title)) (< 1 (count matches)))
                                   {:title title
                                    :ids (mapv :id matches)
                                    :paths (mapv :path matches)})))
                         vec)
         report {:rejected-count (count rejected)
                 :approved-count (count approved)
                 :approved-inbox-count (count inbox)
                 :duplicate-groups duplicates}]
     (if-not apply?
       (assoc report :applied false)
       (let [backup-paths (backup-vault! memory-service)]
         (doseq [note rejected]
           (java.nio.file.Files/deleteIfExists (.toPath (io/file (paths/ensure-vault-path! memory-service (:path note))))))
         (doseq [note approved
                 :let [path (paths/ensure-vault-path! memory-service (:path note))
                       before (slurp path)
                       compacted (vault/proposed-note-content before {} nil [])]
                 :when (not= before compacted)]
           (vault/replace-note-content! path (vault/content-revision before) compacted))
         (doseq [note inbox
                 :let [source (:path note)]
                 :when (.isFile (io/file source))]
           (let [folder (promotion-folder (:iris-scope note) (:type note))
                 root (paths/vault-root-for-path memory-service source)
                 target (.getCanonicalPath (io/file root folder (.getName (io/file source))))]
             (when (not= source target)
               (vault/move-note! source target))))
         (let [index-report (generate-vault-indexes! memory-service)
               result (assoc report :applied true :backup-paths backup-paths :index index-report)]
           (sqlite/log-event! (:store memory-service)
                              {:event-type :memory.vault.cleaned
                               :entity-type :system
                               :entity-id "memory"
                               :payload (dissoc result :index)})
           result))))))

(defn ^:no-doc note-scope [memory-service note]
  (let [scope (or (:scope note)
                  (name (or (get-in memory-service [:config :notes :default-scope])
                            :session)))]
    (if (#{"global" "session" "agent" "project"} scope)
      scope
      "session")))
