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

(defn- content-preview [content]
  (cond
    (nil? content) ""
    (string? content) content
    (sequential? content) (str/join "\n" (keep content-block-preview content))
    :else (str content)))

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
  ([store title] (create-session! store title {}))
  ([store title {:keys [kind metadata] :or {kind :chat}}]
   (let [session {:id (common/uuid-str)
                  :title title
                  :kind (name kind)
                  :metadata_json (common/json-string metadata)
                  :created_at (common/now-str)}]
     (common/with-connection store
       (fn [conn]
         (common/execute! conn (create-session-sqlvec session))))
     {:id (:id session)
      :title title
      :active-mode nil
      :kind kind
      :metadata metadata
      :created-at (:created_at session)})))

(defn- row->session [{:keys [id title active_mode kind metadata_json created_at]}]
  {:id id
   :title title
   :active-mode active_mode
   :kind (keyword (or kind "chat"))
   :metadata (common/parse-json-string metadata_json)
   :created-at created_at})

(defn list-sessions
  ([store] (list-sessions store {}))
  ([store {:keys [kind] :or {kind :chat}}]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->session
            (common/select-many conn (list-sessions-sqlvec {:kind (name kind)}) identity))))))

(defn count-sessions [store]
  (common/count-rows store (count-sessions-sqlvec)))

(defn get-session [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-session-sqlvec {:id session-id}) identity)
              row->session))))

(defn set-session-active-mode!
  [store session-id mode]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (update-session-active-mode-sqlvec
                        {:id session-id
                         :active_mode mode}))))
  (get-session store session-id))

(defn set-session-title-if-blank!
  [store session-id title]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (update-session-title-sqlvec
                        {:id session-id
                         :title title}))))
  (get-session store session-id))

(defn update-session-metadata!
  [store session-id metadata]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (update-session-metadata-sqlvec
                        {:id session-id
                         :metadata_json (common/json-string (or metadata {}))}))))
  (get-session store session-id))

(defn session-exists? [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (boolean (common/select-one conn (session-exists-sqlvec {:id session-id}) identity)))))

(defn append-message!
  ([store session-id role content]
   (append-message! store session-id role content nil))
  ([store session-id role content {:keys [tool-calls tool-call-id metadata excluded-from-context?
                                          select-leaf? content-blocks]
                                   :or {select-leaf? true}}]
   (let [tool-calls-json (when (seq tool-calls) (common/json-string tool-calls))
         metadata-json (common/json-string metadata)
         content-preview* (content-preview (or content-blocks content))
         message {:session_id session-id
                  :role role
                  :content content-preview*
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
                                                                       :content content-preview*}
                                                                content-blocks (assoc :content-blocks content-blocks)
                                                                tool-calls (assoc :tool-calls tool-calls)
                                                                tool-call-id (assoc :tool-call-id tool-call-id)
                                                                metadata (assoc :metadata metadata)
                                                                excluded-from-context? (assoc :excluded-from-context? true))
                                                     :created-at (:created_at message)})]
                  (when select-leaf?
                    (upsert-leaf! conn session-id (:id entry) (:created-at entry)))
                  message-id)))]
     (cond-> {:id id
              :session-id session-id
              :role role
              :content content-preview*
              :created-at (:created_at message)}
       content-blocks (assoc :content-blocks content-blocks)
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

(defn- message-entry-overrides [conn session-id]
  (into {}
        (keep (fn [row]
                (let [{:keys [type payload]} (row->entry row)]
                  (when (and (= :message type) (:message-id payload))
                    [(:message-id payload)
                     (select-keys payload [:content-blocks
                                           :tool-calls
                                           :tool-call-id
                                           :metadata
                                           :excluded-from-context?])]))))
        (common/select-many conn
                            (list-session-entries-sqlvec {:session_id session-id})
                            identity)))

(defn- message-entry-overrides-for-ids [conn session-id message-ids]
  (if-not (seq message-ids)
    {}
    (let [placeholders (str/join "," (repeat (count message-ids) "?"))
          sql (str "select id, session_id, parent_id, type, payload_json, created_at "
                   "from session_entries where session_id = ? and type = 'message' "
                   "and cast(json_extract(payload_json, '$.\"message-id\"') as integer) "
                   "in (" placeholders ")")]
      (into {}
            (keep (fn [row]
                    (let [{:keys [type payload]} (row->entry row)]
                      (when (and (= :message type) (:message-id payload))
                        [(:message-id payload)
                         (select-keys payload [:content-blocks
                                               :tool-calls
                                               :tool-call-id
                                               :metadata
                                               :excluded-from-context?])]))))
            (common/select-many conn
                                (into [sql session-id] message-ids)
                                identity)))))

(defn- merge-entry-overrides [message overrides]
  (if-let [entry (get overrides (:id message))]
    (let [metadata (merge (:metadata entry) (:metadata message))]
      (cond-> (merge entry message)
        (seq metadata) (assoc :metadata metadata)))
    message))

(defn list-messages [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (let [overrides (message-entry-overrides conn session-id)]
        (mapv #(assoc (merge-entry-overrides (row->message %) overrides)
                      :session-id session-id)
              (common/select-many conn
                                  (list-messages-sqlvec {:session_id session-id})
                                  identity))))))

(defn count-messages [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (long (or (:n (common/select-one conn
                                       (count-messages-sqlvec {:session_id session-id})
                                       identity))
                0)))))

(defn list-recent-messages [store session-id limit]
  (common/with-connection
    store
    (fn [conn]
      (let [rows (common/select-many conn
                                     (list-recent-messages-sqlvec
                                      {:session_id session-id
                                       :limit (common/bounded-limit limit 60 400)})
                                     identity)
            messages (mapv row->message rows)
            overrides (message-entry-overrides-for-ids conn session-id (mapv :id messages))]
        (mapv #(assoc (merge-entry-overrides % overrides) :session-id session-id)
              messages)))))

(defn session-thread-stats [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (let [{:keys [total_tokens prompt_tokens completion_tokens cached_tokens
                    timed_completion_tokens timed_duration_ms]}
            (common/select-one conn
                               (message-usage-stats-sqlvec {:session_id session-id})
                               identity)
            latest (common/select-one conn
                                      (latest-message-usage-sqlvec {:session_id session-id})
                                      identity)
            breakdown (mapv (fn [{:keys [tool_name n]}] [tool_name (long n)])
                            (common/select-many conn
                                                (message-tool-counts-sqlvec
                                                 {:session_id session-id})
                                                identity))
            duration-ms (long (or timed_duration_ms 0))]
        {:total-tokens (long (or total_tokens 0))
         :prompt-tokens (long (or prompt_tokens 0))
         :completion-tokens (long (or completion_tokens 0))
         :cached-tokens (long (or cached_tokens 0))
         :context-tokens (when latest
                           (+ (long (or (:prompt_tokens latest) 0))
                              (long (or (:completion_tokens latest) 0))))
         :average-tps (when (pos? duration-ms)
                        (/ (* 1000.0 (double (or timed_completion_tokens 0)))
                           (double duration-ms)))
         :tool-calls (reduce + 0 (map second breakdown))
         :tool-breakdown breakdown}))))

(defn list-messages-after
  [store session-id {:keys [after-id through-id limit] :or {after-id 0 limit 80}}]
  (common/with-connection
    store
    (fn [conn]
      (let [overrides (message-entry-overrides conn session-id)]
        (mapv #(assoc (merge-entry-overrides (row->message %) overrides)
                      :session-id session-id)
              (common/select-many conn
                                  (list-messages-after-sqlvec
                                   {:session_id session-id
                                    :after_id (long (or after-id 0))
                                    :through_id through-id
                                    :limit (common/bounded-limit limit 80 200)})
                                  identity))))))

(defn update-message-runtime-flags!
  [store message-id {:keys [metadata excluded-from-context? session-id reparent-to-current-leaf? select-leaf?]}]
  (common/with-transaction
    store
    (fn [conn]
      (let [params {:id message-id
                    :metadata_json (common/json-string metadata)
                    :excluded_from_context (if excluded-from-context? 1 0)}]
        (common/execute! conn (update-message-runtime-flags-sqlvec params))
        (common/execute! conn (update-message-entry-runtime-flags-sqlvec params))
        (when (and reparent-to-current-leaf? session-id)
          (common/execute! conn
                           (update-message-entry-parent-sqlvec
                            {:id message-id
                             :parent_id (current-leaf-id conn session-id)})))
        (when select-leaf?
          (when-let [entry (some-> (common/select-one conn
                                                      (get-message-entry-by-message-id-sqlvec
                                                       {:id message-id})
                                                      identity)
                                   row->entry)]
            (upsert-leaf! conn (:session-id entry) (:id entry) (common/now-str))))))))

(defn- row->search-message
  [{:keys [id session_id role content created_at session_kind session_title]}]
  {:id id
   :session-id session_id
   :session-kind (keyword (or session_kind "chat"))
   :session-title session_title
   :role role
   :content content
   :created-at created_at})

(defn search-messages
  ([store query] (search-messages store query {}))
  ([store query {:keys [limit session-id session-kind include-tool-results? since until]
                 :or {limit 20 include-tool-results? true}}]
   (let [fts-query (common/fts5-query query)]
     (common/with-connection
       store
       (fn [conn]
         (mapv row->search-message
	               (common/select-many conn
	                                   (if fts-query
	                                     (search-messages-fts-sqlvec {:query fts-query
	                                                                   :session_id session-id
	                                                                   :session_kind (some-> session-kind name)
	                                                                   :since since
	                                                                   :until until
	                                                                   :include_tool_results (if include-tool-results? 1 0)
	                                                                   :limit (common/bounded-limit limit 20 100)})
	                                     (search-messages-like-sqlvec {:needle (str "%" (or query "") "%")
	                                                                    :session_id session-id
	                                                                    :session_kind (some-> session-kind name)
	                                                                    :since since
	                                                                    :until until
	                                                                    :include_tool_results (if include-tool-results? 1 0)
	                                                                    :limit (common/bounded-limit limit 20 100)}))
	                                   identity)))))))

(defn get-search-message
  ([store message-id] (get-search-message store message-id {}))
  ([store message-id {:keys [session-id]}]
   (common/with-connection
     store
     (fn [conn]
       (some-> (common/select-one conn
                                  (get-search-message-by-id-sqlvec
                                   {:id message-id
                                    :session_id session-id})
                                  identity)
               row->search-message)))))

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

(defn list-channel-session-mappings [store source]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->channel-session
            (common/select-many conn
                                (list-channel-session-mappings-sqlvec
                                 {:source (common/normalize-name source)})
                                identity)))))

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
                          (common/execute! conn (insert-message-sqlvec message))
                          (let [message-id (:id (common/select-one conn (last-insert-row-id-sqlvec) identity))]
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
                                (list-session-entries-sqlvec {:session_id session-id})
                                identity)))))

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
