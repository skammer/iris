(ns agent.tools.common.todo
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(def ^:private allowed-actions #{:write :get :list :search})

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

(defn- normalize-action [action]
  (cond
    (keyword? action) action
    (string? action) (keyword (str/lower-case action))
    :else nil))

(defn- ensure-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

(defn- validate-input [input]
  (let [action (normalize-action (:action input))]
    (when-not (allowed-actions action)
      (throw (tools/validation-error
              "action must be one of write/get/list/search"
              {:action (:action input)})))
    (case action
      :write
      (when-not (vector? (:todos input))
        (throw (tools/validation-error "todos must be a vector" {:todos (:todos input)})))

      :search
      (when (str/blank? (or (:query input) ""))
        (throw (tools/validation-error "query must be a non-blank string" {:query (:query input)})))

      nil)
    (assoc input :action action)))

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

(defn create-todo-tool [store]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :todo
     "Persist and retrieve session-scoped todo lists. write replaces a whole list; get/list/search read current thread unless all-threads? is true."
     :category :memory
     :input-schema [:map {:closed true}
                    [:action [:or
                              [:enum :write :get :list :search]
                              [:enum "write" "get" "list" "search"]]]
                    [:thread-id {:optional true} [:maybe :string]]
                    [:slug {:optional true} [:maybe :string]]
                    [:description {:optional true} [:maybe :string]]
                    [:todos {:optional true} [:maybe [:vector item-schema]]]
                    [:metadata {:optional true} [:maybe :any]]
                    [:query {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:all-threads? {:optional true} [:maybe :boolean]]]
     :operation :act
     :approval-sensitive? false
     :action-key :action
     :read-only-actions #{:get :list :search}
     :parallel-safe-actions #{:get :list :search}
     :source :builtin)
    :validate-fn validate-input
    :execute-fn
    (fn [{:keys [action slug description todos metadata query limit all-threads?] :as input} context]
      (case action
        :write
        (do
          (ensure-permission! context :todo-write)
          (sqlite/save-todo-list! store
                                  {:thread-id (thread-id! input context)
                                   :slug (normalize-slug slug)
                                   :description (or description "")
                                   :todos todos
                                   :metadata (or metadata {})}))

        :get
        (do
          (ensure-permission! context :todo-read)
          (let [thread-id (thread-id! input context)
                slug* (normalize-slug slug)]
            (found-response (sqlite/get-todo-list store {:thread-id thread-id
                                                         :slug slug*})
                            thread-id
                            slug*)))

        :list
        (do
          (ensure-permission! context :todo-read)
          (let [thread-id (scoped-thread-id! input context)
                rows (sqlite/list-todo-lists store
                                             (cond-> {:limit limit}
                                               thread-id (assoc :thread-id thread-id)))]
            {:lists rows
             :count (count rows)
             :all-threads? (true? all-threads?)}))

        :search
        (do
          (ensure-permission! context :todo-read)
          (let [thread-id (scoped-thread-id! input context)
                rows (sqlite/search-todo-lists store
                                               query
                                               (cond-> {:limit limit}
                                                 thread-id (assoc :thread-id thread-id)))]
            {:query query
             :lists rows
             :count (count rows)
             :all-threads? (true? all-threads?)}))))}))
