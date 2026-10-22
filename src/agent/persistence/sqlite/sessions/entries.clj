(ns agent.persistence.sqlite.sessions.entries
  "Session entry trees, leaf selection and compacted LLM history."
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [agent.persistence.sqlite.sessions.queries :as queries]))

(def entry-types
  #{:message :model_change :thinking_level_change :compaction :branch_summary
    :custom :custom_message :label :session_info})

(defn- normalize-entry-type [value]
  (cond
    (keyword? value) (keyword (str/replace (name value) "-" "_"))
    (string? value) (keyword (str/replace value "-" "_"))
    :else value))

(defn- valid-entry-type! [type]
  (let [type* (normalize-entry-type type)]
    (when-not (contains? entry-types type*)
      (throw (ex-info (str "Unsupported session entry type: " type)
                      {:type :validation-failed
                       :entry-type type})))
    type*))

(defn ^:no-doc row->entry
  [{:keys [id session_id parent_id type payload_json created_at]}]
  {:id id
   :session-id session_id
   :parent-id parent_id
   :type (keyword type)
   :payload (common/parse-json-string payload_json)
   :created-at created_at})

(defn- content-block-preview [block]
  (let [type (keyword (or (:type block) :custom))]
    (case type
      :text (:text block)
      :thinking (:text block)
      :image (or (:alt block) "[image]")
      :audio (or (:transcript block) (:alt block) "[audio]")
      :video (or (:alt block) "[video]")
      :file (or (:alt block) (:filename block) "[file]")
      :tool-result (some-> (:content block) str)
      nil)))

(defn ^:no-doc content-preview [content]
  (cond
    (nil? content) ""
    (string? content) content
    (sequential? content) (str/join "\n" (keep content-block-preview content))
    :else (str content)))

(defn ^:no-doc result-call-id [role explicit-id content-blocks]
  (or explicit-id
      (when (= "tool" role)
        (:tool-call-id (first (filter #(= :tool-result (some-> (:type %) keyword))
                                     content-blocks))))))

(defn- payload->message [payload]
  (let [message (or (:message payload) payload)
        raw-content (or (:content message) "")
        content-blocks (or (:content-blocks message)
                           (when (sequential? raw-content)
                             (vec raw-content)))]
    {:role (or (:role message) "user")
     :content (content-preview (or content-blocks raw-content))
     :content-blocks content-blocks
     :tool-calls (:tool-calls message)
     :tool-call-id (result-call-id (:role message) (:tool-call-id message) content-blocks)
     :metadata (:metadata message)
     :excluded-from-context? (true? (:excluded-from-context? message))}))

(defn ^:no-doc current-leaf-id [conn session-id]
  (or (:leaf_entry_id (common/select-one conn
                                         (queries/get-session-leaf-selection-sqlvec {:session_id session-id})
                                         identity))
      (:id (common/select-one conn
                              (queries/latest-session-entry-sqlvec {:session_id session-id})
                              identity))))

(defn ^:no-doc upsert-leaf! [conn session-id leaf-id now]
  (common/execute! conn (queries/upsert-session-leaf-selection-sqlvec
                         {:session_id session-id
                          :leaf_entry_id leaf-id
                          :updated_at now})))

(defn ^:no-doc insert-entry-row! [conn {:keys [id session-id parent-id type payload created-at]}]
  (let [entry {:id (or id (common/uuid-str))
               :session_id session-id
               :parent_id parent-id
               :type (name (valid-entry-type! type))
               :payload_json (common/json-string payload)
               :created_at (or created-at (common/now-str))}]
    (common/execute! conn (queries/insert-session-entry-sqlvec entry))
    (row->entry {:id (:id entry)
                 :session_id (:session_id entry)
                 :parent_id (:parent_id entry)
                 :type (:type entry)
                 :payload_json (:payload_json entry)
                 :created_at (:created_at entry)})))

(defn append-entry!
  ([store session-id type payload]
   (append-entry! store session-id {:type type :payload payload}))
  ([store session-id {:keys [id parent-id type payload created-at select-leaf?]
                      :or {select-leaf? true}}]
   (common/with-transaction
     store
     (fn [conn]
       (let [type* (valid-entry-type! type)
             now (or created-at (common/now-str))
             payload* (if (= :message type*)
                        (let [{:keys [role content content-blocks tool-calls tool-call-id
                                      metadata excluded-from-context?]} (payload->message payload)
                              tool-calls-json (when (seq tool-calls) (common/json-string tool-calls))
                              message {:session_id session-id
                                       :role role
                                       :content content
                                       :tool_calls tool-calls-json
                                       :tool_call_id tool-call-id
                                       :metadata_json (common/json-string metadata)
                                       :excluded_from_context (if excluded-from-context? 1 0)
                                       :created_at now}]
                          (common/execute! conn (queries/insert-message-sqlvec message))
                          (let [message-id (:id (common/select-one conn (queries/last-insert-row-id-sqlvec) identity))]
                            (cond-> (assoc payload :message-id message-id
                                           :role role
                                           :content content)
                              content-blocks (assoc :content-blocks content-blocks)
                              tool-calls (assoc :tool-calls tool-calls)
                              tool-call-id (assoc :tool-call-id tool-call-id)
                              metadata (assoc :metadata metadata)
                              excluded-from-context? (assoc :excluded-from-context? true))))
                        payload)
             entry (insert-entry-row! conn {:id id
                                            :session-id session-id
                                            :parent-id (if (some? parent-id)
                                                         parent-id
                                                         (current-leaf-id conn session-id))
                                            :type type*
                                            :payload payload*
                                            :created-at now})]
         (when select-leaf?
           (upsert-leaf! conn session-id (:id entry) (:created-at entry)))
         entry)))))

(defn list-entries [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->entry
            (common/select-many conn
                                (queries/list-session-entries-sqlvec {:session_id session-id})
                                identity)))))

(defn leaf-entry [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (when-let [leaf-id (current-leaf-id conn session-id)]
        (some-> (common/select-one conn
                                   (queries/get-session-entry-sqlvec {:session_id session-id
                                                              :id leaf-id})
                                   identity)
                row->entry)))))

(defn select-leaf! [store session-id entry-id]
  (common/with-transaction
    store
    (fn [conn]
      (let [entry (or (some-> (common/select-one conn
                                                 (queries/get-session-entry-sqlvec {:session_id session-id
                                                                            :id entry-id})
                                                 identity)
                              row->entry)
                      (throw (ex-info "Session entry not found"
                                      {:type :entry-not-found
                                       :session-id session-id
                                       :entry-id entry-id})))]
        (upsert-leaf! conn session-id entry-id (common/now-str))
        entry))))

(defn branch-path
  "Read only the selected leaf's ancestors. Reject corrupt cycles rather than hang."
  ([store session-id]
   (branch-path store session-id (some-> (leaf-entry store session-id) :id)))
  ([store session-id leaf-id]
   (let [entries (common/with-connection
                 store (fn [conn]
                         (common/select-many conn
                           (queries/session-ancestor-entries-sqlvec
                            {:session_id session-id :leaf_id leaf-id}) row->entry)))
         by-id (into {} (map (juxt :id identity)) entries)]
     (loop [entry (get by-id leaf-id) path () seen #{}]
       (if entry
         (do
           (when (contains? seen (:id entry))
             (throw (ex-info "Cycle in session history" {:type :session-entry-cycle :session-id session-id})))
           (recur (get by-id (:parent-id entry)) (conj path entry) (conj seen (:id entry))))
         (vec path))))))

(defn- latest-labels [entries]
  (reduce (fn [acc {:keys [payload created-at]}]
            (let [target (:target-id payload)]
              (if target
                (assoc acc target {:label (:label payload)
                                   :label-timestamp created-at})
                acc)))
          {}
          (filter #(= :label (:type %)) entries)))

(defn session-tree [store session-id]
  (let [entries (list-entries store session-id)
        labels (latest-labels entries)
        children (group-by :parent-id entries)]
    (letfn [(node [entry]
              (merge {:entry entry
                      :children (mapv node (get children (:id entry)))}
                     (when-let [label (get labels (:id entry))]
                       label)))]
      (mapv node (get children nil)))))

(defn- latest-compaction [entries]
  (last (filter #(= :compaction (:type %)) entries)))

(defn- entries-after-compaction-cut [entries compaction-entry]
  (if-let [first-kept-id (get-in compaction-entry [:payload :first-kept-entry-id])]
    (let [kept (vec (drop-while #(not= first-kept-id (:id %)) entries))]
      (if (seq kept) kept entries))
    entries))

(defn- compaction-summary-message [compaction-entry]
  (when-let [summary (get-in compaction-entry [:payload :summary])]
    {:role "system"
     :content (str "Context summary for compacted earlier conversation:\n"
                   summary)}))

(defn- entry->llm-message [{:keys [id type payload]} include-entry-id?]
  (let [with-id (fn [message]
                  (cond-> message
                    include-entry-id? (assoc :id id)))]
    (case type
      :message (when-not (:excluded-from-context? payload)
                 (with-id (cond-> {:role (:role payload)
                                   :content (or (:content-blocks payload)
                                                (:content payload))}
                            (:tool-calls payload) (assoc :tool-calls (:tool-calls payload))
                            (:tool-call-id payload) (assoc :tool-call-id (:tool-call-id payload))
                            (:metadata payload) (assoc :metadata (:metadata payload)))))
      :custom_message (with-id {:role "user"
                                :content (:content payload)})
      nil)))

(defn current-llm-context
  ([store session-id] (current-llm-context store session-id nil))
  ([store session-id {:keys [include-entry-id?]}]
	   (let [entries (branch-path store session-id)
	         compaction-entry (latest-compaction entries)
	         entries* (entries-after-compaction-cut entries compaction-entry)
	         summary-message (compaction-summary-message compaction-entry)
	         messages (vec (keep #(entry->llm-message % include-entry-id?) entries*))]
	     (if summary-message
	       (vec (cons summary-message messages))
	       messages))))
