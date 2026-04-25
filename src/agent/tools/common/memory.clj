(ns agent.tools.common.memory
  (:require
   [agent.memory.core :as memory]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(def ^:private allowed-actions
  #{:search :save-fact :read-vault :write-vault})

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
      (throw (tools/validation-error "action must be one of search/save-fact/read-vault/write-vault"
                                     {:action (:action input)})))
    (assoc input :action action)))

(defn create-memory-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory
     "Durable memory tool"
     :category :memory
     :input-schema [:map {:closed true}
                    [:action [:or
                              [:enum :search :save-fact :read-vault :write-vault]
                              [:enum "search" "save-fact" "read-vault" "write-vault"]]]
                    [:query {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:scope {:optional true} [:maybe [:map {:closed true}
                                                [:type [:or
                                                        [:enum :global :session :agent]
                                                        [:enum "global" "session" "agent"]]]
                                                [:id {:optional true} [:maybe :string]]]]]
                    [:subject {:optional true} [:maybe :string]]
                    [:predicate {:optional true} [:maybe :string]]
                    [:object {:optional true} [:maybe :string]]
                    [:path {:optional true} [:maybe :string]]
                    [:content {:optional true} [:maybe :string]]]
     :source :builtin)
    :validate-fn validate-input
    :execute-fn
    (fn [{:keys [action query limit scope subject predicate object path content]} context]
      (case action
        :search
        (do
          (ensure-permission! context :memory-read)
          (memory/search-memory memory-service
                                query
                                (cond-> {:limit (or limit 20)}
                                  scope (assoc :scope scope)
                                  (:session-id context) (assoc :session-id (:session-id context))
                                  (:agent-id context) (assoc :agent-id (:agent-id context)))))

        :save-fact
        (do
          (ensure-permission! context :memory-write)
          (doseq [[field value] {:subject subject :predicate predicate :object object}]
            (when (str/blank? (or value ""))
              (throw (tools/validation-error "fact fields must be non-blank strings"
                                             {:field field}))))
          (memory/save-memory-fact! memory-service
                                    {:subject subject
                                     :predicate predicate
                                     :object object}
                                    {:scope (or scope
                                                {:type :session
                                                 :id (:session-id context)})
                                     :source-session-id (:session-id context)
                                     :source-request-id (:request-id context)}))

        :read-vault
        (do
          (ensure-permission! context :memory-read)
          (memory/read-vault-file memory-service path))

        :write-vault
        (do
          (ensure-permission! context :memory-write)
          (memory/write-vault-file! memory-service path content))))}))
