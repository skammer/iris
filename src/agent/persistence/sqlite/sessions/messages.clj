(ns agent.persistence.sqlite.sessions.messages
  "Message persistence, paging, search and usage aggregation."
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [agent.persistence.sqlite.sessions.queries :as queries]
   [agent.persistence.sqlite.sessions.entries :as entries]))

(defn append-message!
  ([store session-id role content]
   (append-message! store session-id role content nil))
  ([store session-id role content {:keys [tool-calls tool-call-id metadata excluded-from-context?
                                          select-leaf? content-blocks]
                                   :or {select-leaf? true}}]
   (let [tool-call-id (entries/result-call-id role tool-call-id content-blocks)
         tool-calls-json (when (seq tool-calls) (common/json-string tool-calls))
         metadata-json (common/json-string metadata)
         content-preview* (entries/content-preview (or content-blocks content))
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
                (common/execute! conn (queries/insert-message-sqlvec message))
                (let [message-id (:id (common/select-one conn (queries/last-insert-row-id-sqlvec) identity))
                      entry (entries/insert-entry-row! conn {:session-id session-id
                                                     :parent-id (entries/current-leaf-id conn session-id)
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
                    (entries/upsert-leaf! conn session-id (:id entry) (:created-at entry)))
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
                (let [{:keys [type payload]} (entries/row->entry row)]
                  (when (and (= :message type) (:message-id payload))
                    [(:message-id payload)
                     (select-keys payload [:content-blocks
                                           :tool-calls
                                           :tool-call-id
                                           :metadata
                                           :excluded-from-context?])]))))
        (common/select-many conn
                            (queries/list-session-entries-sqlvec {:session_id session-id})
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
                    (let [{:keys [type payload]} (entries/row->entry row)]
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
                                  (queries/list-messages-sqlvec {:session_id session-id})
                                  identity))))))

(defn tool-detail-messages
  "Read only the selected call and its first subsequent result, scoped to a session."
  [store session-id message-id call-id]
  (common/with-connection
    store
    (fn [conn]
      (if-let [message (common/select-one conn
                                         (queries/get-message-sqlvec {:session_id session-id :id message-id})
                                         identity)]
        (let [result (when (= "assistant" (:role message))
                       (common/select-one conn
                                          (queries/get-tool-result-sqlvec {:session_id session-id
                                                                   :after_id (:id message)
                                                                   :tool_call_id call-id})
                                          identity))
              rows (cond-> [message] result (conj result))
              overrides (message-entry-overrides-for-ids conn session-id (mapv :id rows))]
          (mapv #(assoc (merge-entry-overrides (row->message %) overrides) :session-id session-id)
                rows))
        []))))

(defn count-messages [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (long (or (:n (common/select-one conn
                                       (queries/count-messages-sqlvec {:session_id session-id})
                                       identity))
                0)))))

(defn list-recent-messages [store session-id limit]
  (common/with-connection
    store
    (fn [conn]
      (let [rows (common/select-many conn
                                     (queries/list-recent-messages-sqlvec
                                      {:session_id session-id
                                       :limit (common/bounded-limit limit 60 400)})
                                     identity)
            messages (mapv row->message rows)
            overrides (message-entry-overrides-for-ids conn session-id (mapv :id messages))]
        (mapv #(assoc (merge-entry-overrides % overrides) :session-id session-id)
              messages)))))

(defn message-page [store session-id {:keys [before after]}]
  (common/with-connection
   store
   (fn [conn]
     (let [query (if after queries/list-message-page-after-sqlvec queries/list-message-page-before-sqlvec)
           rows (common/select-many conn (query {:session_id session-id :cursor (or after before) :limit 61}) identity)
           selected (cond-> (take 60 rows) (not after) reverse)
           messages (mapv row->message selected)
           overrides (message-entry-overrides-for-ids conn session-id (mapv :id messages))]
       {:messages (mapv #(assoc (merge-entry-overrides % overrides) :session-id session-id) messages)
        :more? (> (count rows) 60)}))))

(defn session-thread-stats [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (let [{:keys [total_tokens prompt_tokens completion_tokens cached_tokens
                    timed_completion_tokens timed_duration_ms]}
            (common/select-one conn
                               (queries/message-usage-stats-sqlvec {:session_id session-id})
                               identity)
            latest (common/select-one conn
                                      (queries/latest-message-usage-sqlvec {:session_id session-id})
                                      identity)
            breakdown (mapv (fn [{:keys [tool_name n]}] [tool_name (long n)])
                            (common/select-many conn
                                                (queries/message-tool-counts-sqlvec
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
                                  (queries/list-messages-after-sqlvec
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
        (common/execute! conn (queries/update-message-runtime-flags-sqlvec params))
        (common/execute! conn (queries/update-message-entry-runtime-flags-sqlvec params))
        (when (and reparent-to-current-leaf? session-id)
          (common/execute! conn
                           (queries/update-message-entry-parent-sqlvec
                            {:id message-id
                             :parent_id (entries/current-leaf-id conn session-id)})))
        (when select-leaf?
          (when-let [entry (some-> (common/select-one conn
                                                      (queries/get-message-entry-by-message-id-sqlvec
                                                       {:id message-id})
                                                      identity)
                                   entries/row->entry)]
            (entries/upsert-leaf! conn (:session-id entry) (:id entry) (common/now-str))))))))

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
	                                     (queries/search-messages-fts-sqlvec {:query fts-query
	                                                                   :session_id session-id
	                                                                   :session_kind (some-> session-kind name)
	                                                                   :since since
	                                                                   :until until
	                                                                   :include_tool_results (if include-tool-results? 1 0)
	                                                                   :limit (common/bounded-limit limit 20 100)})
	                                     (queries/search-messages-like-sqlvec {:needle (str "%" (or query "") "%")
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
                                  (queries/get-search-message-by-id-sqlvec
                                   {:id message-id
                                    :session_id session-id})
                                  identity)
               row->search-message)))))
