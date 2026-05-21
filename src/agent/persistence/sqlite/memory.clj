(ns agent.persistence.sqlite.memory
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/memory.sql")

(defn normalize-text [value]
  (-> (or value "")
      str/trim
      str/lower-case
      (str/replace #"\s+" " ")))

(defn normalize-scope [{:keys [scope scope-type scope-id session-id agent-id]}]
  (let [scope* (or scope
                   (cond
                     session-id {:type :session :id session-id}
                     agent-id {:type :agent :id agent-id}
                     :else {:type :global}))
        type* (or (:type scope*) scope-type :global)
        id* (or (:id scope*) scope-id)]
    {:scope-type (name type*)
     :scope-id (when-not (= "global" (name type*)) id*)}))

(defn- row->fact
  [{:keys [id scope_type scope_id subject predicate object normalized_subject
           normalized_predicate normalized_object source_session_id
           source_message_ids_json source_request_id confidence status
           metadata_json created_at updated_at]}]
  {:id id
   :scope {:type scope_type :id scope_id}
   :subject subject
   :predicate predicate
   :object object
   :normalized {:subject normalized_subject
                :predicate normalized_predicate
                :object normalized_object}
   :source-session-id source_session_id
   :source-message-ids (vec (or (common/parse-json-string source_message_ids_json) []))
   :source-request-id source_request_id
   :confidence confidence
   :status status
   :metadata (or (common/parse-json-string metadata_json) {})
   :created-at created_at
   :updated-at updated_at})

(defn- fact-row [fact]
  (let [{:keys [scope-type scope-id]} (normalize-scope fact)
        now (common/now-str)
        subject (:subject fact)
        predicate (:predicate fact)
        object (:object fact)]
    {:id (or (:id fact) (common/uuid-str))
     :scope_type scope-type
     :scope_id scope-id
     :subject subject
     :predicate predicate
     :object object
     :normalized_subject (normalize-text subject)
     :normalized_predicate (normalize-text predicate)
     :normalized_object (normalize-text object)
     :source_session_id (or (:source-session-id fact) (:session-id fact))
     :source_message_ids_json (common/json-string (vec (or (:source-message-ids fact) [])))
     :source_request_id (:source-request-id fact)
     :confidence (:confidence fact)
     :status (or (:status fact) "active")
     :metadata_json (common/json-string (or (:metadata fact) {}))
     :created_at (or (:created-at fact) now)
     :updated_at now}))

(defn save-fact! [store fact]
  (let [row (fact-row fact)]
    (common/with-transaction
      store
      (fn [conn]
        (if-let [existing (common/select-one conn
                                             (get-fact-by-normalized-sqlvec row)
                                             row->fact)]
          (let [row* (assoc row
                            :id (:id existing)
                            :created_at (:created-at existing)
                            :source_message_ids_json
                            (common/json-string
                             (vec (distinct (concat (:source-message-ids existing)
                                                    (or (:source-message-ids fact) []))))))]
            (common/execute! conn (update-fact-sqlvec row*))
            (assoc (row->fact (merge row* {:source_message_ids_json (:source_message_ids_json row*)}))
                   :created? false))
          (do
            (common/execute! conn (insert-fact-sqlvec row))
            (assoc (row->fact row) :created? true)))))))

(defn merge-fact-source! [store existing fact]
  (let [source-message-ids (vec (distinct (concat (:source-message-ids existing)
                                                  (or (:source-message-ids fact) []))))
        row (merge (fact-row (merge existing fact))
                   {:id (:id existing)
                    :created_at (:created-at existing)
                    :subject (:subject existing)
                    :predicate (:predicate existing)
                    :object (:object existing)
                    :normalized_subject (get-in existing [:normalized :subject])
                    :normalized_predicate (get-in existing [:normalized :predicate])
                    :normalized_object (get-in existing [:normalized :object])
                    :source_message_ids_json (common/json-string source-message-ids)})]
    (common/with-transaction
      store
      (fn [conn]
        (common/execute! conn (update-fact-sqlvec row))
        (assoc (row->fact row)
               :created? false
               :similar-duplicate? true)))))

(defn get-fact [store id]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one conn (get-fact-sqlvec {:id id}) row->fact))))

(defn remove-fact! [store {:keys [id] :as fact}]
  (let [row (fact-row fact)
        now (common/now-str)
        params (assoc row :updated_at now)
        removed (common/with-transaction
                  store
                  (fn [conn]
                    (if id
                      (common/execute! conn (remove-fact-by-id-sqlvec {:id id
                                                                       :updated_at now}))
                      (common/execute! conn (remove-fact-by-normalized-sqlvec params)))))]
    {:id id
     :subject (:subject fact)
     :predicate (:predicate fact)
     :object (:object fact)
     :scope {:type (:scope_type row)
             :id (:scope_id row)}
     :removed-count removed
     :removed? (pos? (long removed))
     :updated-at now}))

(defn search-facts
  ([store query] (search-facts store query {}))
  ([store query {:keys [limit include-global?] :or {limit 20 include-global? true} :as opts}]
   (let [fts-query (common/fts5-query query)
         {:keys [scope-type scope-id]} (normalize-scope opts)
         params {:needle (when-not (str/blank? (or query ""))
                           (str "%" query "%"))
                 :query fts-query
                 :limit limit
                 :include_global (if include-global? 1 0)
                 :scope_type scope-type
                 :scope_id scope-id}]
     (common/with-connection
       store
       (fn [conn]
         (mapv row->fact
               (common/select-many conn
                                   (if (:all-scopes? opts)
                                     (if fts-query
                                       (search-facts-all-fts-sqlvec params)
                                       (search-facts-all-like-sqlvec params))
                                     (if fts-query
                                       (search-facts-scoped-fts-sqlvec params)
                                       (search-facts-scoped-like-sqlvec params)))
                                   identity)))))))

(defn count-facts [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-facts-sqlvec) identity) :n int))))
