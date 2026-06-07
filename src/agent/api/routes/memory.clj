(ns agent.api.routes.memory
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private memory-search-body
  [:map
   [:query schemas/NonBlankString]
   [:limit {:optional true} :int]])

(def ^:private memory-fact-save-body
  [:map
   [:subject schemas/NonBlankString]
   [:predicate schemas/NonBlankString]
   [:object schemas/NonBlankString]
   [:confidence {:optional true} number?]
   [:scope {:optional true} schemas/MemoryScope]
   [:source_session_id {:optional true} :string]
   [:source_message_ids {:optional true} schemas/StringVec]
   [:source_request_id {:optional true} :string]])

(def ^:private memory-fact-search-body
  [:map
   [:query {:optional true} :string]
   [:limit {:optional true} :int]
   [:scope {:optional true} schemas/MemoryScope]
   [:all_scopes {:optional true} :boolean]])

(def ^:private memory-vault-read-body
  [:map [:path schemas/NonBlankString]])

(def ^:private memory-vault-write-body
  [:map
   [:path schemas/NonBlankString]
   [:content schemas/NonBlankString]])

(def routes
  [["/v1/memory/surfaces" {:get {:handler/id :memory-surfaces}}]
   ["/v1/memory/prompt" {:get {:handler/id :memory-prompt}}]
   ["/v1/memory/search" {:post {:handler/id :memory-search
                                :parameters {:body memory-search-body}}}]
   ["/v1/memory/facts" {:post {:handler/id :memory-fact-save
                               :parameters {:body memory-fact-save-body}}}]
   ["/v1/memory/facts/search" {:post {:handler/id :memory-fact-search
                                      :parameters {:body memory-fact-search-body}}}]
   ["/v1/memory/vault/read" {:post {:handler/id :memory-vault-read
                                    :parameters {:body memory-vault-read-body}}}]
   ["/v1/memory/vault/write" {:post {:handler/id :memory-vault-write
                                     :parameters {:body memory-vault-write-body}}}]])
