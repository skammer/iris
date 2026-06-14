(ns agent.persistence.sqlite.memory
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [hugsql.core :as hugsql]))

(declare reset-vault-chunks-fts-sqlvec
         reset-vault-chunks-sqlvec
         reset-vault-index-sqlvec
         reset-vault-note-embeddings-sqlvec
         reset-vault-chunk-embeddings-sqlvec
         insert-vault-note-sqlvec
         insert-vault-chunk-sqlvec
         insert-vault-chunk-fts-sqlvec
         insert-memory-embedding-sqlvec
         insert-vault-chunk-embedding-sqlvec
         search-vault-chunks-fts-sqlvec
         search-vault-chunks-like-sqlvec
         list-vault-notes-sqlvec
         count-vault-notes-sqlvec
         count-vault-chunks-sqlvec
         list-vault-chunks-sqlvec
         list-memory-embeddings-sqlvec
         list-vault-chunk-embeddings-sqlvec
         list-vault-chunk-embedding-candidates-sqlvec)

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/memory.sql")

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

(defn- row->vault-chunk
  [{:keys [chunk_id path heading block_id content_hash text note_id type title
           description tags_json timestamp iris_scope iris_status iris_confidence
           origins_json frontmatter_json updated_at retrieval_score
           embedding_json embedding_model embedding_dimensions]}]
  (cond-> {:chunk-id chunk_id
           :path path
           :heading heading
           :block-id block_id
           :content-hash content_hash
           :text text
           :note-id note_id
           :type type
           :title title
           :description description
           :tags (vec (or (common/parse-json-string tags_json) []))
           :timestamp timestamp
           :iris-scope iris_scope
           :iris-status iris_status
           :iris-confidence iris_confidence
           :origins (vec (or (common/parse-json-string origins_json) []))
           :frontmatter (or (common/parse-json-string frontmatter_json) {})
           :updated-at updated_at
           :retrieval-score retrieval_score}
    embedding_json (assoc :embedding (vec (or (common/parse-json-string embedding_json) []))
                          :embedding-model embedding_model
                          :embedding-dimensions embedding_dimensions)))

(defn- row->memory-embedding
  [{:keys [id surface surface_id content_hash model embedding_json dimensions updated_at]}]
  {:id id
   :surface surface
   :surface-id surface_id
   :content-hash content_hash
   :model model
   :embedding (vec (or (common/parse-json-string embedding_json) []))
   :dimensions dimensions
   :updated-at updated_at})

(defn- row->vault-chunk-embedding
  [{:keys [chunk_id content_hash model embedding_json dimensions updated_at]}]
  {:chunk-id chunk_id
   :content-hash content_hash
   :model model
   :embedding (vec (or (common/parse-json-string embedding_json) []))
   :dimensions dimensions
   :updated-at updated_at})

(defn- row->vault-note
  [{:keys [path id type title description tags_json timestamp iris_scope
           iris_status iris_confidence origins_json frontmatter_json body_hash
           updated_at]}]
  {:path path
   :id id
   :type type
   :title title
   :description description
   :tags (vec (or (common/parse-json-string tags_json) []))
   :timestamp timestamp
   :iris-scope iris_scope
   :iris-status iris_status
   :iris-confidence iris_confidence
   :origins (vec (or (common/parse-json-string origins_json) []))
   :frontmatter (or (common/parse-json-string frontmatter_json) {})
   :body-hash body_hash
   :updated-at updated_at})

(defn replace-vault-index!
  ([store notes] (replace-vault-index! store notes {}))
  ([store notes {:keys [memory-embeddings vault-chunk-embeddings]}]
  (common/with-transaction
    store
    (fn [conn]
      (common/execute! conn (reset-vault-chunk-embeddings-sqlvec))
      (common/execute! conn (reset-vault-note-embeddings-sqlvec))
      (common/execute! conn (reset-vault-chunks-fts-sqlvec))
      (common/execute! conn (reset-vault-chunks-sqlvec))
      (common/execute! conn (reset-vault-index-sqlvec))
      (doseq [{:keys [chunks] :as note} notes]
        (common/execute!
         conn
         (insert-vault-note-sqlvec
          {:path (:path note)
           :id (:id note)
           :type (:type note)
           :title (:title note)
           :description (:description note)
           :tags_json (common/json-string (vec (or (:tags note) [])))
           :timestamp (:timestamp note)
           :iris_scope (:iris-scope note)
           :iris_status (:iris-status note)
           :iris_confidence (:iris-confidence note)
           :origins_json (common/json-string (vec (or (:origins note) [])))
           :frontmatter_json (common/json-string (or (:frontmatter note) {}))
           :body_hash (:body-hash note)
           :updated_at (:updated-at note)}))
        (doseq [chunk chunks]
          (let [row {:chunk_id (:chunk-id chunk)
                     :path (:path note)
                     :heading (:heading chunk)
                     :block_id (:block-id chunk)
                     :content_hash (:content-hash chunk)
                     :text (:text chunk)}]
            (common/execute! conn (insert-vault-chunk-sqlvec row))
            (common/execute! conn (insert-vault-chunk-fts-sqlvec row)))))
      (doseq [{:keys [embedding] :as row} memory-embeddings]
        (common/execute!
         conn
         (insert-memory-embedding-sqlvec
          (-> row
              (assoc :surface "vault_note"
                     :embedding_json (common/json-string (vec embedding))
                     :dimensions (count embedding))
              (dissoc :embedding)))))
      (doseq [{:keys [embedding] :as row} vault-chunk-embeddings]
        (common/execute!
         conn
         (insert-vault-chunk-embedding-sqlvec
          (-> row
              (assoc :embedding_json (common/json-string (vec embedding))
                     :dimensions (count embedding))
              (dissoc :embedding)))))
      {:note-count (count notes)
       :chunk-count (reduce + 0 (map #(count (:chunks %)) notes))
       :memory-embedding-count (count memory-embeddings)
       :vault-chunk-embedding-count (count vault-chunk-embeddings)}))))

(defn search-vault-chunks
  ([store query] (search-vault-chunks store query {}))
  ([store query {:keys [limit] :or {limit 20} :as opts}]
   (let [fts-query (common/fts5-query query)
         session-id (:session-id opts)
         params {:needle (when-not (str/blank? (or query ""))
                           (str "%" query "%"))
                 :query fts-query
                 :session_id session-id
                 :session_origin_needle (when session-id
                                          (str "%\"session_id\":\"" session-id "\"%"))
                 :limit (common/bounded-limit limit 20 100)}]
     (common/with-connection
       store
       (fn [conn]
         (mapv row->vault-chunk
               (common/select-many conn
                                   (if fts-query
                                     (search-vault-chunks-fts-sqlvec params)
                                     (search-vault-chunks-like-sqlvec params))
                                   identity)))))))

(defn list-vault-notes
  ([store] (list-vault-notes store {}))
  ([store {:keys [limit status] :or {limit 50}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->vault-note
             (common/select-many conn
                                 (list-vault-notes-sqlvec
                                  {:limit (common/bounded-limit limit 50 200)
                                   :status status})
                                 identity))))))

(defn count-vault-notes [store]
  (common/count-rows store (count-vault-notes-sqlvec)))

(defn count-vault-chunks [store]
  (common/count-rows store (count-vault-chunks-sqlvec)))

(defn list-vault-chunks
  ([store] (list-vault-chunks store {}))
  ([store {:keys [limit] :or {limit 1000}}]
   (common/with-connection
     store
     (fn [conn]
       (common/select-many conn
                           (list-vault-chunks-sqlvec
                            {:limit (common/bounded-limit limit 1000 10000)})
                           identity)))))

(defn list-memory-embeddings
  ([store] (list-memory-embeddings store {}))
  ([store {:keys [limit surface] :or {limit 1000}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->memory-embedding
             (common/select-many conn
                                 (list-memory-embeddings-sqlvec
                                  {:limit (common/bounded-limit limit 1000 10000)
                                   :surface surface})
                                 identity))))))

(defn list-vault-chunk-embeddings
  ([store] (list-vault-chunk-embeddings store {}))
  ([store {:keys [limit] :or {limit 1000}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->vault-chunk-embedding
             (common/select-many conn
                                 (list-vault-chunk-embeddings-sqlvec
                                  {:limit (common/bounded-limit limit 1000 10000)})
                                 identity))))))

(defn list-vault-chunk-embedding-candidates
  ([store] (list-vault-chunk-embedding-candidates store {}))
  ([store {:keys [limit session-id] :or {limit 1000}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->vault-chunk
             (common/select-many conn
                                 (list-vault-chunk-embedding-candidates-sqlvec
                                  {:session_id session-id
                                   :session_origin_needle (when session-id
                                                            (str "%\"session_id\":\"" session-id "\"%"))
                                   :limit (common/bounded-limit limit 1000 10000)})
                                 identity))))))
