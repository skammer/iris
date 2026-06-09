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

(defn- normalize-enum [field allowed fallback item]
  (common/valid-enum! (or (common/normalize-name (get item field)) fallback) allowed
                      {:message (str "Invalid todo " (name field))
                       :type :invalid-todo-item
                       :field field
                       :value (get item field)
                       :allowed allowed}))

(defn- normalize-item [item]
  (let [unknown (seq (remove allowed-item-keys (keys item)))]
    (when unknown
      (throw (ex-info "Todo item has unsupported keys"
                      {:type :invalid-todo-item
                       :keys (vec unknown)})))
    (when (str/blank? (or (:content item) ""))
      (throw (ex-info "Todo item content must be non-blank"
                      {:type :invalid-todo-item
                       :field :content
                       :value (:content item)})))
    (when-not (contains? item :description)
      (throw (ex-info "Todo item description is required"
                      {:type :invalid-todo-item
                       :field :description})))
    (when-not (string? (:description item))
      (throw (ex-info "Todo item description must be a string"
                      {:type :invalid-todo-item
                       :field :description
                       :value (:description item)})))
    {:content (:content item)
     :description (:description item)
     :status (normalize-enum :status statuses "pending" item)
     :priority (normalize-enum :priority priorities "medium" item)}))

(defn- normalize-slug [slug]
  (let [slug* (str/trim (or slug ""))]
    (if (str/blank? slug*) "default" slug*)))

(defn- row->todo-item [{:keys [content description status priority]}]
  {:content content
   :description description
   :status status
   :priority priority})

(defn- list-items* [conn list-id]
  (mapv row->todo-item
        (common/select-many conn (list-items-sqlvec {:list_id list-id}) identity)))

(defn- row->todo-list [conn {:keys [id thread_id slug description metadata_json created_at updated_at]}]
  {:id id
   :thread-id thread_id
   :slug slug
   :description (or description "")
   :todos (list-items* conn id)
   :metadata (or (common/parse-json-string metadata_json) {})
   :created-at created_at
   :updated-at updated_at})

(defn- list-row [{:keys [id thread-id slug description metadata created-at]}]
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
     :metadata_json (common/json-string (or metadata {}))
     :created_at (or created-at now)
     :updated_at now}))

(defn- item-row [list-id position item now]
  (merge {:id (common/uuid-str)
          :list_id list-id
          :position position
          :created_at now
          :updated_at now}
         (normalize-item item)))

(defn- replace-items! [conn list-id todos now]
  (common/execute! conn (delete-list-items-sqlvec {:list_id list-id}))
  (doseq [[position item] (map-indexed vector todos)]
    (common/execute! conn (insert-list-item-sqlvec (item-row list-id position item now)))))

(defn save-list! [store {:keys [todos] :as todo-list}]
  (let [row (list-row todo-list)]
    (common/with-transaction
      store
      (fn [conn]
        (if-let [existing (common/select-one conn (get-list-sqlvec row) identity)]
          (let [row* (assoc row
                            :id (:id existing)
                            :created_at (:created_at existing))]
            (common/execute! conn (update-list-sqlvec row*))
            (replace-items! conn (:id row*) (or todos []) (:updated_at row*))
            (assoc (row->todo-list conn row*) :created? false))
          (do
            (common/execute! conn (insert-list-sqlvec row))
            (replace-items! conn (:id row) (or todos []) (:updated_at row))
            (assoc (row->todo-list conn row) :created? true)))))))

(defn get-list [store {:keys [thread-id slug]}]
  (common/with-connection
    store
    (fn [conn]
      (some->> (common/select-one conn
                                  (get-list-sqlvec {:thread_id thread-id
                                                    :slug (normalize-slug slug)})
                                  identity)
               (row->todo-list conn)))))

(defn list-lists
  ([store] (list-lists store {}))
  ([store {:keys [thread-id limit]}]
   (common/with-connection
     store
     (fn [conn]
       (mapv #(row->todo-list conn %)
             (common/select-many conn
                                 (list-lists-sqlvec {:thread_id thread-id
                                                     :limit (common/bounded-limit limit default-limit max-limit)})
                                 identity))))))

(defn search-lists
  ([store query] (search-lists store query {}))
  ([store query {:keys [thread-id limit]}]
   (let [fts-query (common/fts5-query query)
         needle (when-not (str/blank? (or query ""))
                  (str "%" query "%"))
         params {:thread_id thread-id
                 :query fts-query
                 :needle needle
                 :limit (common/bounded-limit limit default-limit max-limit)}]
     (if (str/blank? (or query ""))
       []
       (common/with-connection
         store
         (fn [conn]
           (mapv #(row->todo-list conn %)
                 (common/select-many conn
                                     (if fts-query
                                       (search-lists-fts-sqlvec params)
                                       (search-lists-like-sqlvec params))
                                     identity))))))))

(defn count-lists [store]
  (common/count-rows store (count-lists-sqlvec)))
