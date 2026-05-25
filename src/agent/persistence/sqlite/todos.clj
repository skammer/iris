(ns agent.persistence.sqlite.todos
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/todos.sql")

(def default-limit 20)
(def max-limit 100)

(def ^:private allowed-item-keys #{:content :description :status :priority})
(def ^:private statuses #{"pending" "in_progress" "completed" "cancelled"})
(def ^:private priorities #{"high" "medium" "low"})

(defn- bounded-limit [limit]
  (min max-limit
       (max 1 (long (if (integer? limit) limit default-limit)))))

(defn- normalized-name [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (nil? value) nil
    :else (str value)))

(defn- normalize-enum [field allowed fallback item]
  (let [value (or (normalized-name (get item field)) fallback)]
    (when-not (contains? allowed value)
      (throw (ex-info (str "Invalid todo " (name field))
                      {:type :invalid-todo-item
                       :field field
                       :value (get item field)
                       :allowed allowed})))
    value))

(defn- normalize-item [item]
  (let [unknown (seq (remove allowed-item-keys (keys item)))]
    (when unknown
      (throw (ex-info "Todo item has unsupported keys"
                      {:type :invalid-todo-item
                       :keys (vec unknown)})))
    (when-not (contains? item :description)
      (throw (ex-info "Todo item description is required"
                      {:type :invalid-todo-item
                       :field :description})))
    (when-not (string? (:description item))
      (throw (ex-info "Todo item description must be a string"
                      {:type :invalid-todo-item
                       :field :description
                       :value (:description item)})))
    (when (str/blank? (or (:content item) ""))
      (throw (ex-info "Todo item content must be non-blank"
                      {:type :invalid-todo-item
                       :field :content
                       :value (:content item)})))
    {:content (:content item)
     :description (:description item)
     :status (normalize-enum :status statuses "pending" item)
     :priority (normalize-enum :priority priorities "medium" item)}))

(defn- normalize-slug [slug]
  (let [slug* (str/trim (or slug ""))]
    (if (str/blank? slug*) "default" slug*)))

(defn- row->todo-list
  [{:keys [id thread_id slug description todos_json metadata_json created_at updated_at]}]
  {:id id
   :thread-id thread_id
   :slug slug
   :description (or description "")
   :todos (mapv normalize-item (or (common/parse-json-string todos_json) []))
   :metadata (or (common/parse-json-string metadata_json) {})
   :created-at created_at
   :updated-at updated_at})

(defn- list-row [{:keys [id thread-id slug description todos metadata created-at]}]
  (when (str/blank? (or thread-id ""))
    (throw (ex-info "thread-id is required"
                    {:type :invalid-todo-list
                     :field :thread-id})))
  (when-not (or (nil? metadata) (map? metadata))
    (throw (ex-info "metadata must be a map"
                    {:type :invalid-todo-list
                     :field :metadata
                     :value metadata})))
  (let [now (common/now-str)]
    {:id (or id (common/uuid-str))
     :thread_id thread-id
     :slug (normalize-slug slug)
     :description (or description "")
     :todos_json (common/json-string (mapv normalize-item (or todos [])))
     :metadata_json (common/json-string (or metadata {}))
     :created_at (or created-at now)
     :updated_at now}))

(defn save-list! [store todo-list]
  (let [row (list-row todo-list)]
    (common/with-transaction
      store
      (fn [conn]
        (if-let [existing (common/select-one conn (get-list-sqlvec row) row->todo-list)]
          (let [row* (assoc row
                            :id (:id existing)
                            :created_at (:created-at existing))]
            (common/execute! conn (update-list-sqlvec row*))
            (assoc (row->todo-list row*) :created? false))
          (do
            (common/execute! conn (insert-list-sqlvec row))
            (assoc (row->todo-list row) :created? true)))))))

(defn get-list [store {:keys [thread-id slug]}]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one conn
                         (get-list-sqlvec {:thread_id thread-id
                                            :slug (normalize-slug slug)})
                         row->todo-list))))

(defn list-lists
  ([store] (list-lists store {}))
  ([store {:keys [thread-id limit]}]
   (common/with-connection
     store
     (fn [conn]
       (common/select-many conn
                           (list-lists-sqlvec {:thread_id thread-id
                                               :limit (bounded-limit limit)})
                           row->todo-list)))))

(defn search-lists
  ([store query] (search-lists store query {}))
  ([store query {:keys [thread-id limit]}]
   (let [fts-query (common/fts5-query query)
         needle (when-not (str/blank? (or query ""))
                  (str "%" query "%"))
         params {:thread_id thread-id
                 :query fts-query
                 :needle needle
                 :limit (bounded-limit limit)}]
     (if (str/blank? (or query ""))
       []
       (common/with-connection
         store
         (fn [conn]
           (mapv row->todo-list
                 (common/select-many conn
                                     (if fts-query
                                       (search-lists-fts-sqlvec params)
                                       (search-lists-like-sqlvec params))
                                     identity))))))))

(defn count-lists [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-lists-sqlvec) identity) :n int))))
