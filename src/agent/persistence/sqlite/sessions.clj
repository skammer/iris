(ns agent.persistence.sqlite.sessions
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/sessions.sql")

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

(defn- row->entry
  [{:keys [id session_id parent_id type payload_json created_at]}]
  {:id id
   :session-id session_id
   :parent-id parent_id
   :type (keyword type)
   :payload (common/parse-json-string payload_json)
   :created-at created_at})

(defn- payload->message [payload]
  (let [message (or (:message payload) payload)]
    {:role (or (:role message) "user")
     :content (or (:content message) "")
     :tool-calls (:tool-calls message)
     :tool-call-id (:tool-call-id message)
     :metadata (:metadata message)
     :excluded-from-context? (true? (:excluded-from-context? message))}))

(defn- current-leaf-id [conn session-id]
  (or (:leaf_entry_id (common/select-one conn
                                         (get-session-leaf-selection-sqlvec {:session_id session-id})
                                         identity))
      (:id (common/select-one conn
                              (latest-session-entry-sqlvec {:session_id session-id})
                              identity))))

(defn- upsert-leaf! [conn session-id leaf-id now]
  (common/execute! conn (upsert-session-leaf-selection-sqlvec
                         {:session_id session-id
                          :leaf_entry_id leaf-id
                          :updated_at now})))

(defn- insert-entry-row! [conn {:keys [id session-id parent-id type payload created-at]}]
  (let [entry {:id (or id (common/uuid-str))
               :session_id session-id
               :parent_id parent-id
               :type (name (valid-entry-type! type))
               :payload_json (common/json-string payload)
               :created_at (or created-at (common/now-str))}]
    (common/execute! conn (insert-session-entry-sqlvec entry))
    (row->entry {:id (:id entry)
                 :session_id (:session_id entry)
                 :parent_id (:parent_id entry)
                 :type (:type entry)
                 :payload_json (:payload_json entry)
                 :created_at (:created_at entry)})))

(defn create-session!
  ([store] (create-session! store nil))
  ([store title]
   (let [session {:id (common/uuid-str)
                  :title title
                  :created_at (common/now-str)}]
     (common/with-connection store
       (fn [conn]
         (common/execute! conn (create-session-sqlvec session))))
     {:id (:id session)
      :title title
      :created-at (:created_at session)})))

(defn list-sessions [store]
  (common/with-connection
    store
    (fn [conn]
      (mapv (fn [{:keys [id title created_at]}]
              {:id id
               :title title
               :created-at created_at})
            (common/select-many conn (list-sessions-sqlvec) identity)))))

(defn get-session [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-session-sqlvec {:id session-id}) identity)
              ((fn [{:keys [id title created_at]}]
                 {:id id
                  :title title
                  :created-at created_at}))))))

(defn session-exists? [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (boolean (common/select-one conn (session-exists-sqlvec {:id session-id}) identity)))))

(defn append-message!
  ([store session-id role content]
   (append-message! store session-id role content nil))
  ([store session-id role content {:keys [tool-calls tool-call-id metadata excluded-from-context?]}]
   (let [tool-calls-json (when (seq tool-calls) (common/json-string tool-calls))
         metadata-json (common/json-string metadata)
         message {:session_id session-id
                  :role role
                  :content content
                  :tool_calls tool-calls-json
                  :tool_call_id tool-call-id
                  :metadata_json metadata-json
                  :excluded_from_context (if excluded-from-context? 1 0)
                  :created_at (common/now-str)}
         id (common/with-transaction
              store
              (fn [conn]
                (common/execute! conn (insert-message-sqlvec message))
                (let [message-id (:id (common/select-one conn (last-insert-row-id-sqlvec) identity))
                      entry (insert-entry-row! conn {:session-id session-id
                                                     :parent-id (current-leaf-id conn session-id)
                                                     :type :message
                                                     :payload (cond-> {:message-id message-id
                                                                       :role role
                                                                       :content content}
                                                                tool-calls (assoc :tool-calls tool-calls)
                                                                tool-call-id (assoc :tool-call-id tool-call-id)
                                                                metadata (assoc :metadata metadata)
                                                                excluded-from-context? (assoc :excluded-from-context? true))
                                                     :created-at (:created_at message)})]
                  (upsert-leaf! conn session-id (:id entry) (:created-at entry))
                  message-id)))]
     (cond-> {:id id
              :session-id session-id
              :role role
              :content content
              :created-at (:created_at message)}
       tool-calls (assoc :tool-calls tool-calls)
       tool-call-id (assoc :tool-call-id tool-call-id)
       metadata (assoc :metadata metadata)
       excluded-from-context? (assoc :excluded-from-context? true)))))

(defn- row->message
  [{:keys [id role content tool_calls tool_call_id metadata_json excluded_from_context created_at]}]
  (cond-> {:id id
           :role role
           :content content
           :created-at created_at}
    (seq tool_calls) (assoc :tool-calls (common/parse-json-string tool_calls))
    tool_call_id (assoc :tool-call-id tool_call_id)
    metadata_json (assoc :metadata (common/parse-json-string metadata_json))
    (pos? (int (or excluded_from_context 0))) (assoc :excluded-from-context? true)))

(defn list-messages [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->message
            (common/select-many conn (list-messages-sqlvec {:session_id session-id}) identity)))))

(defn update-message-runtime-flags!
  [store message-id {:keys [metadata excluded-from-context?]}]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (update-message-runtime-flags-sqlvec
                        {:id message-id
                         :metadata_json (common/json-string metadata)
                         :excluded_from_context (if excluded-from-context? 1 0)})))))

(defn- row->search-message
  [{:keys [id session_id role content created_at]}]
  {:id id
   :session-id session_id
   :role role
   :content content
   :created-at created_at})

(defn search-messages
  ([store query] (search-messages store query {}))
  ([store query {:keys [limit session-id] :or {limit 20}}]
   (let [fts-query (common/fts5-query query)]
     (common/with-connection
       store
       (fn [conn]
         (mapv row->search-message
               (common/select-many conn
                                   (if fts-query
                                     (search-messages-fts-sqlvec {:query fts-query
                                                                   :session_id session-id
                                                                   :limit limit})
                                     (search-messages-like-sqlvec {:needle (str "%" (or query "") "%")
                                                                    :session_id session-id
                                                                    :limit limit}))
                                   identity)))))))

(defn log-completion! [store {:keys [session-id provider model prompt response]}]
  (let [completion {:session_id session-id
                    :provider (name provider)
                    :model model
                    :prompt prompt
                    :response response
                    :created_at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (insert-completion-sqlvec completion))))
    {:session-id session-id
     :provider provider
     :model model
     :prompt prompt
     :response response
     :created-at (:created_at completion)}))

(defn- row->channel-session
  [{:keys [source external_chat_id session_id metadata_json created_at updated_at]}]
  {:source source
   :external-chat-id external_chat_id
   :session-id session_id
   :metadata (common/parse-json-string metadata_json)
   :created-at created_at
   :updated-at updated_at})

(defn get-channel-session-mapping [store source external-chat-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                  (get-channel-session-mapping-sqlvec
                                   {:source (common/normalize-name source)
                                    :external_chat_id (str external-chat-id)})
                                  identity)
              row->channel-session))))

(defn upsert-channel-session-mapping!
  [store {:keys [source external-chat-id session-id metadata]}]
  (let [now (common/now-str)
        mapping {:source (common/normalize-name source)
                 :external_chat_id (str external-chat-id)
                 :session_id session-id
                 :metadata_json (common/json-string metadata)
                 :created_at now
                 :updated_at now}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (upsert-channel-session-mapping-sqlvec mapping))))
    (row->channel-session mapping)))

(defn ensure-channel-session!
  [store {:keys [source external-chat-id title metadata]}]
  (common/with-transaction
    store
    (fn [conn]
      (let [source* (common/normalize-name source)
            external-chat-id* (str external-chat-id)
            now (common/now-str)
            existing (common/select-one
                      conn
                      (get-channel-session-mapping-sqlvec
                       {:source source*
                        :external_chat_id external-chat-id*})
                      identity)]
        (if existing
          (row->channel-session existing)
          (let [session-id (common/uuid-str)
                session {:id session-id
                         :title title
                         :created_at now}
                mapping {:source source*
                         :external_chat_id external-chat-id*
                         :session_id session-id
                         :metadata_json (common/json-string metadata)
                         :created_at now
                         :updated_at now}]
            (common/execute! conn (insert-session-ignore-sqlvec session))
            (common/execute! conn (insert-channel-session-mapping-ignore-sqlvec mapping))
            (row->channel-session
             (common/select-one conn
                                (get-channel-session-mapping-sqlvec
                                 {:source source*
                                  :external_chat_id external-chat-id*})
                                identity))))))))

(defn reset-channel-session!
  [store {:keys [source external-chat-id title metadata]}]
  (let [session (create-session! store title)]
    (upsert-channel-session-mapping!
     store
     {:source source
      :external-chat-id external-chat-id
      :session-id (:id session)
      :metadata metadata})))

(defn get-channel-offset [store source]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                  (get-channel-offset-sqlvec
                                   {:source (common/normalize-name source)})
                                  identity)
              (update :next_offset long)))))

(defn save-channel-offset!
  [store source next-offset]
  (let [row {:source (common/normalize-name source)
             :next_offset (long next-offset)
             :updated_at (common/now-str)}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (upsert-channel-offset-sqlvec row))))
    {:source (:source row)
     :next-offset (:next_offset row)
     :updated-at (:updated_at row)}))

(defn upsert-channel-inbox-update!
  [store source update-id update]
  (let [now (common/now-str)
        row {:source (common/normalize-name source)
             :update_id (long update-id)
             :status "received"
             :raw_json (common/json-string update)
             :attempts 0
             :last_error nil
             :created_at now
             :updated_at now}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (upsert-channel-inbox-sqlvec row))))
    row))

(defn mark-channel-inbox-update!
  [store source update-id status last-error]
  (let [row {:source (common/normalize-name source)
             :update_id (long update-id)
             :status (common/normalize-name status)
             :attempt_delta (if (= "failed" (common/normalize-name status)) 1 0)
             :last_error last-error
             :updated_at (common/now-str)}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (update-channel-inbox-status-sqlvec row))))
    row))

(defn get-channel-inbox-update [store source update-id]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one conn
                         (get-channel-inbox-update-sqlvec
                          {:source (common/normalize-name source)
                           :update_id (long update-id)})
                         identity))))

(defn migrate-messages-to-entries! [store]
  (common/with-transaction
    store
    (fn [conn]
      (let [inserted (common/execute! conn (insert-missing-message-entries-sqlvec))
            leaves (common/execute! conn (upsert-missing-session-leaves-sqlvec))]
        {:inserted inserted
         :leaf-selections leaves}))))

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
                        (let [{:keys [role content tool-calls tool-call-id metadata excluded-from-context?]} (payload->message payload)
                              tool-calls-json (when (seq tool-calls) (common/json-string tool-calls))
                              message {:session_id session-id
                                       :role role
                                       :content content
                                       :tool_calls tool-calls-json
                                       :tool_call_id tool-call-id
                                       :metadata_json (common/json-string metadata)
                                       :excluded_from_context (if excluded-from-context? 1 0)
                                       :created_at now}]
                          (common/execute! conn (insert-message-sqlvec message))
                          (let [message-id (:id (common/select-one conn (last-insert-row-id-sqlvec) identity))]
                            (cond-> (assoc payload :message-id message-id
                                           :role role
                                           :content content)
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
                                (list-session-entries-sqlvec {:session_id session-id})
                                identity)))))

(defn get-entry [store session-id entry-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                 (get-session-entry-sqlvec {:session_id session-id
                                                            :id entry-id})
                                 identity)
              row->entry))))

(defn leaf-entry [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (when-let [leaf-id (current-leaf-id conn session-id)]
        (some-> (common/select-one conn
                                   (get-session-entry-sqlvec {:session_id session-id
                                                              :id leaf-id})
                                   identity)
                row->entry)))))

(defn select-leaf! [store session-id entry-id]
  (common/with-transaction
    store
    (fn [conn]
      (let [entry (or (some-> (common/select-one conn
                                                 (get-session-entry-sqlvec {:session_id session-id
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
  ([store session-id]
   (branch-path store session-id (some-> (leaf-entry store session-id) :id)))
  ([store session-id leaf-id]
   (let [entries (list-entries store session-id)
         by-id (into {} (map (juxt :id identity)) entries)]
     (loop [entry (get by-id leaf-id)
            path ()]
       (if entry
         (recur (get by-id (:parent-id entry)) (conj path entry))
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

(defn current-llm-context [store session-id]
  (->> (branch-path store session-id)
       (keep (fn [{:keys [type payload]}]
               (case type
                 :message (when-not (:excluded-from-context? payload)
                            {:role (:role payload)
                             :content (:content payload)})
                 :custom_message {:role "user"
                                  :content (:content payload)}
                 nil)))
       vec))
