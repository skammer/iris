(ns agent.memory.proposals
  "Reviewable note changes with revision checks and rollback on failed application."
  (:require
   [agent.memory.vault :as vault]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [agent.memory.notes :as notes]
   [agent.memory.paths :as paths]
   [agent.memory.policy :as policy]))

(defn propose-vault-note-create!
  "Create a review-only Vault Note candidate. Nothing becomes recallable until approval."
  [memory-service note opts]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (doseq [field [:type :title :description :body]]
    (when (str/blank? (or (get note field) ""))
      (throw (ex-info "Memory create proposal is missing a required field"
                      {:type :validation-failed :field field}))))
  (let [result (vault/write-candidate-note!
                memory-service
                (merge note
                       {:scope (notes/note-scope memory-service note)
                        :status "candidate"
                        :origins (or (:origins opts)
                                     [(cond-> {:type (name (or (:source opts) :tool))}
                                        (:session-id opts) (assoc :session-id (:session-id opts))
                                        (:project-id opts) (assoc :project-id (:project-id opts))
                                        (:request-id opts) (assoc :request-id (:request-id opts)))])
                        :source-request-id (:request-id opts)
                        :evidence (:evidence opts)}))]
    (notes/reindex-vault! memory-service)
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.vault.create_proposed
                        :entity-type :vault_note
                        :entity-id (:id result)
                        :request-id (:request-id opts)
                        :payload result})
    result))

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
      (when (and scope (not (policy/allowed-vault-scopes scope)))
        (throw (ex-info "Unsupported vault note scope"
                        {:type :invalid-vault-note-scope
                         :scope scope
                         :allowed policy/allowed-vault-scopes})))
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

(defn create-memory-proposal!
  [memory-service proposal opts]
  (locking (:update-lock memory-service)
    (if-let [existing
             (some (fn [candidate]
                     (when (and (= (name (or (:operation proposal) :note-update))
                                    (:operation candidate))
                                (= (:proposed-revision proposal) (:proposed-revision candidate))
                                (= (:secondary-target-id proposal) (:secondary-target-id candidate)))
                       candidate))
                   (sqlite/list-memory-note-updates
                    (:store memory-service)
                    {:status "pending"
                     :target-id (:target-id proposal)
                     :operation (:operation proposal)
                     :limit 100}))]
      (assoc existing :duplicate true)
      (let [result (sqlite/create-memory-note-update!
                    (:store memory-service)
                    (merge {:id (str "memprop_" (java.util.UUID/randomUUID))
                            :source (or (:source opts) :tool)
                            :status :pending
                            :evidence (:evidence opts)}
                           proposal))]
        (sqlite/log-event! (:store memory-service)
                           {:event-type :memory.vault.operation_proposed
                            :entity-type :memory_proposal
                            :entity-id (:id result)
                            :request-id (:request-id opts)
                            :payload (dissoc result :proposed-content)})
        result))))

(defn- approved-note-state! [memory-service note-id expected-revision]
  (let [note (or (indexed-note-by-id memory-service note-id)
                 (throw (ex-info "Vault Note not found"
                                 {:type :not-found :note-id note-id})))
        _ (when-not (= "approved" (:iris-status note))
            (throw (ex-info "Only approved Vault Notes support operation proposals"
                            {:type :vault-note-not-approved
                             :note-id note-id
                             :status (:iris-status note)})))
        path (paths/ensure-vault-path! memory-service (:path note))
        content (slurp path)
        revision (vault/content-revision content)]
    (when-not (= expected-revision revision)
      (throw (ex-info "Vault Note revision changed"
                      {:type :stale-vault-note-revision
                       :note-id note-id
                       :expected-revision expected-revision
                       :actual-revision revision})))
    {:note note :path path :content content :revision revision}))

(defn propose-vault-note-move!
  [memory-service note-id expected-revision folder opts]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [{:keys [path content revision]} (approved-note-state! memory-service note-id expected-revision)
        root (paths/vault-root-for-path memory-service path)
        folder* (notes/safe-vault-folder folder)
        target-path (.getCanonicalPath (io/file root folder* (.getName (io/file path))))]
    (paths/ensure-vault-path! memory-service target-path)
    (when (.exists (io/file target-path))
      (throw (ex-info "Vault Note target already exists"
                      {:type :vault-note-target-exists
                       :note-id note-id
                       :target-path target-path})))
    (when (= path target-path)
      (throw (ex-info "Vault Note already resides in target folder"
                      {:type :validation-failed :note-id note-id :folder folder})))
    (create-memory-proposal!
     memory-service
     {:operation :note-move
      :target-id note-id
      :target-path path
      :base-revision revision
      :proposed-revision (vault/content-revision (str "move\n" revision "\n" target-path))
      :changes {:folder folder* :target-path target-path}
      :proposed-content content
      :diff (str "MOVE\n- " path "\n+ " target-path)}
     opts)))

(defn propose-vault-note-delete!
  [memory-service note-id expected-revision opts]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [{:keys [path content revision]} (approved-note-state! memory-service note-id expected-revision)]
    (create-memory-proposal!
     memory-service
     {:operation :note-delete
      :target-id note-id
      :target-path path
      :base-revision revision
      :proposed-revision (vault/content-revision (str "delete\n" revision))
      :changes {}
      :proposed-content content
      :diff (str "DELETE\n- " path "\n- entire approved note")}
     opts)))

(defn propose-vault-note-merge!
  [memory-service target-id source-id expected-target-revision expected-source-revision changes opts]
  (when (= target-id source-id)
    (throw (ex-info "Merge source and target must differ"
                    {:type :validation-failed :target-id target-id :source-id source-id})))
  (let [target (approved-note-state! memory-service target-id expected-target-revision)
        source (approved-note-state! memory-service source-id expected-source-revision)
        changes* (normalize-note-update-changes changes)
        before-values (vault/note-change-values (:content target))
        proposed-content (vault/proposed-note-content
                          (:content target)
                          changes*
                          (:evidence opts)
                          (or (:origins opts)
                              [{:type (name (or (:source opts) :tool))
                                :session-id (:session-id opts)
                                :request-id (:request-id opts)
                                :vault-path (:path source)}]))
        after-values (vault/note-change-values proposed-content)]
    (create-memory-proposal!
     memory-service
     {:operation :note-merge
      :target-id target-id
      :target-path (:path target)
      :secondary-target-id source-id
      :secondary-target-path (:path source)
      :base-revision (:revision target)
      :secondary-base-revision (:revision source)
      :proposed-revision (vault/content-revision proposed-content)
      :changes changes*
      :proposed-content proposed-content
      :diff (str (note-update-diff before-values after-values changes*)
                 "\n\nDELETE MERGED SOURCE\n- " (:path source))}
     opts)))

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
        path (paths/ensure-vault-path! memory-service (:path note))
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
                                 {:status "pending" :target-id note-id
                                  :operation :note-update :limit 100}))]
          (assoc existing :duplicate true)
          (let [after-values (vault/note-change-values proposed-content)]
            (create-memory-proposal!
             memory-service
             {:operation :note-update
              :target-id note-id
              :target-path path
              :base-revision base-revision
              :proposed-revision proposed-revision
              :changes changes*
              :proposed-content proposed-content
              :diff (note-update-diff before-values after-values changes*)}
             opts))))))))

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
      (when-not (= "note-update" (:operation update))
        (throw (ex-info "Proposal is not a note update"
                        {:type :invalid-memory-proposal-operation
                         :update-id update-id
                         :operation (:operation update)})))
      (let [path (paths/ensure-vault-path! memory-service (:target-path update))
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
            (let [index-result (notes/reindex-vault! memory-service)]
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
                (notes/reindex-vault! memory-service))
              (throw e))))))))

(defn- proposal-current-content! [memory-service proposal path-key revision-key]
  (let [path (paths/ensure-vault-path! memory-service (get proposal path-key))
        content (slurp path)
        revision (vault/content-revision content)]
    (when-not (= (get proposal revision-key) revision)
      (throw (ex-info "Memory proposal target changed after proposal creation"
                      {:type :stale-memory-proposal
                       :proposal-id (:id proposal)
                       :path path
                       :expected-revision (get proposal revision-key)
                       :actual-revision revision})))
    {:path path :content content :revision revision}))

(defn apply-memory-note-proposal!
  [memory-service proposal-id reason]
  (let [proposal (or (get-memory-note-update memory-service proposal-id)
                     (throw (ex-info "Memory proposal not found"
                                     {:type :not-found :proposal-id proposal-id})))]
    (when-not (= "pending" (:status proposal))
      (throw (ex-info "Memory proposal is not pending"
                      {:type :memory-proposal-not-pending
                       :proposal-id proposal-id
                       :status (:status proposal)})))
    (case (:operation proposal)
      "note-update" (apply-memory-note-update! memory-service proposal-id reason)

      "note-move"
      (locking (:update-lock memory-service)
        (let [{:keys [path]} (proposal-current-content! memory-service proposal
                                                        :target-path :base-revision)
              target-path (paths/ensure-vault-path! memory-service (get-in proposal [:changes :target-path]))]
          (try
            (vault/move-note! path target-path)
            (let [index-result (notes/reindex-vault! memory-service)]
              (when-not (:ok? index-result)
                (throw (ex-info "Vault reindex failed after move" {:report index-result})))
              (decide-memory-note-update! memory-service proposal-id :applied :yes reason))
            (catch Exception e
              (when (and (.isFile (io/file target-path)) (not (.exists (io/file path))))
                (vault/move-note! target-path path)
                (notes/reindex-vault! memory-service))
              (throw e)))))

      "note-delete"
      (locking (:update-lock memory-service)
        (let [{:keys [path content]} (proposal-current-content! memory-service proposal
                                                                :target-path :base-revision)]
          (try
            (java.nio.file.Files/delete (.toPath (io/file path)))
            (let [index-result (notes/reindex-vault! memory-service)]
              (when-not (:ok? index-result)
                (throw (ex-info "Vault reindex failed after delete" {:report index-result})))
              (decide-memory-note-update! memory-service proposal-id :applied :yes reason))
            (catch Exception e
              (when-not (.exists (io/file path))
                (spit path content)
                (notes/reindex-vault! memory-service))
              (throw e)))))

      "note-merge"
      (locking (:update-lock memory-service)
        (let [target (proposal-current-content! memory-service proposal
                                                :target-path :base-revision)
              source (proposal-current-content! memory-service proposal
                                                :secondary-target-path :secondary-base-revision)]
          (try
            (vault/replace-note-content! (:path target) (:revision target)
                                         (:proposed-content proposal))
            (java.nio.file.Files/delete (.toPath (io/file (:path source))))
            (let [index-result (notes/reindex-vault! memory-service)]
              (when-not (:ok? index-result)
                (throw (ex-info "Vault reindex failed after merge" {:report index-result})))
              (decide-memory-note-update! memory-service proposal-id :applied :yes reason))
            (catch Exception e
              (when (.exists (io/file (:path target)))
                (spit (:path target) (:content target)))
              (when-not (.exists (io/file (:path source)))
                (spit (:path source) (:content source)))
              (notes/reindex-vault! memory-service)
              (throw e)))))

      (throw (ex-info "Unsupported memory proposal operation"
                      {:type :invalid-memory-proposal-operation
                       :proposal-id proposal-id
                       :operation (:operation proposal)})))))
