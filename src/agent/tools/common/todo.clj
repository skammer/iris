(ns agent.tools.common.todo
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(def ^:private status-schema
  [:or
   [:enum :pending :in_progress :completed :cancelled]
   [:enum "pending" "in_progress" "completed" "cancelled"]])

(def ^:private priority-schema
  [:or
   [:enum :high :medium :low]
   [:enum "high" "medium" "low"]])

(def ^:private item-schema
  [:map {:closed true}
   [:content :string]
   [:description :string]
   [:status {:optional true} status-schema]
   [:priority {:optional true} priority-schema]])

(defn- ensure-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

(defn- validate-search-input [input]
  (when (str/blank? (or (:query input) ""))
    (throw (tools/validation-error "query must be a non-blank string" {:query (:query input)})))
  input)

(defn- normalize-slug [slug]
  (let [slug* (str/trim (or slug ""))]
    (if (str/blank? slug*) "default" slug*)))

(defn- explicit-thread-id [input]
  (let [value (:thread-id input)]
    (when-not (str/blank? (or value ""))
      value)))

(defn- thread-id! [input context]
  (or (explicit-thread-id input)
      (:session-id context)
      (throw (tools/validation-error
              "thread-id is required when session-id is unavailable"
              {:thread-id (:thread-id input)
               :session-id (:session-id context)}))))

(defn- scoped-thread-id! [input context]
  (when-not (true? (:all-threads? input))
    (thread-id! input context)))

(defn- found-response [row thread-id slug]
  (if row
    (assoc row :found? true)
    {:found? false
     :thread-id thread-id
     :slug slug}))

(defn create-todo-write-tool [store]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :todo_write
     "Replace a session-scoped todo list."
     :category :memory
     :input-schema [:map {:closed true}
                    [:thread-id {:optional true} [:maybe :string]]
                    [:slug {:optional true} [:maybe :string]]
                    [:description {:optional true} [:maybe :string]]
                    [:todos [:vector item-schema]]
                    [:metadata {:optional true} [:maybe :any]]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [{:keys [slug description todos metadata] :as input} context]
      (ensure-permission! context :todo-write)
      (sqlite/save-todo-list! store
                              {:thread-id (thread-id! input context)
                               :slug (normalize-slug slug)
                               :description (or description "")
                               :todos todos
                               :metadata (or metadata {})}))}))

(defn create-todo-get-tool [store]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :todo_get
     "Get one session-scoped todo list."
     :category :memory
     :input-schema [:map {:closed true}
                    [:thread-id {:optional true} [:maybe :string]]
                    [:slug {:optional true} [:maybe :string]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [slug] :as input} context]
      (ensure-permission! context :todo-read)
      (let [thread-id (thread-id! input context)
            slug* (normalize-slug slug)]
        (found-response (sqlite/get-todo-list store {:thread-id thread-id
                                                     :slug slug*})
                        thread-id
                        slug*)))}))

(defn create-todo-list-tool [store]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :todo_list
     "List todo lists in the current thread unless all-threads? is true."
     :category :memory
     :input-schema [:map {:closed true}
                    [:thread-id {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:all-threads? {:optional true} [:maybe :boolean]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [limit all-threads?] :as input} context]
      (ensure-permission! context :todo-read)
      (let [thread-id (scoped-thread-id! input context)
            rows (sqlite/list-todo-lists store
                                         (cond-> {:limit limit}
                                           thread-id (assoc :thread-id thread-id)))]
        {:lists rows
         :count (count rows)
         :all-threads? (true? all-threads?)}))}))

(defn create-todo-search-tool [store]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :todo_search
     "Search todo lists in the current thread unless all-threads? is true."
     :category :memory
     :input-schema [:map {:closed true}
                    [:query :string]
                    [:thread-id {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:all-threads? {:optional true} [:maybe :boolean]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :validate-fn validate-search-input
    :execute-fn
    (fn [{:keys [query limit all-threads?] :as input} context]
      (ensure-permission! context :todo-read)
      (let [thread-id (scoped-thread-id! input context)
            rows (sqlite/search-todo-lists store
                                           query
                                           (cond-> {:limit limit}
                                             thread-id (assoc :thread-id thread-id)))]
        {:query query
         :lists rows
         :count (count rows)
         :all-threads? (true? all-threads?)}))}))

(defn create-todo-tools [store]
  [(create-todo-write-tool store)
   (create-todo-get-tool store)
   (create-todo-list-tool store)
   (create-todo-search-tool store)])
