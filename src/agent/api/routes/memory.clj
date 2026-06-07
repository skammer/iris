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

(def ^:private memory-graph-save-body
  [:map
   [:subject schemas/NonBlankString]
   [:predicate schemas/NonBlankString]
   [:object schemas/NonBlankString]
   [:id {:optional true} :string]
   [:type {:optional true} :string]
   [:source {:optional true} :string]
   [:session_id {:optional true} :string]
   [:source_request_id {:optional true} :string]
   [:episode_id {:optional true} :string]
   [:episode_content {:optional true} :string]
   [:confidence {:optional true} number?]
   [:valid_from {:optional true} :string]
   [:valid_to {:optional true} :string]
   [:observed_at {:optional true} :string]
   [:invalidated_by {:optional true} :string]
   [:tags {:optional true} schemas/StringVec]])

(def ^:private memory-graph-query-body
  [:map
   [:mode {:optional true} :string]
   [:query {:optional true} :string]
   [:limit {:optional true} :int]
   [:entity {:optional true} :string]
   [:depth {:optional true} :int]
   [:from {:optional true} :string]
   [:to {:optional true} :string]
   [:max_depth {:optional true} :int]
   [:as_of {:optional true} :string]
   [:include_historical {:optional true} :boolean]])

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
                                     :parameters {:body memory-vault-write-body}}}]
   ["/v1/memory/graph/facts" {:post {:handler/id :memory-graph-save
                                     :parameters {:body memory-graph-save-body}}}]
   ["/v1/memory/graph/query" {:post {:handler/id :memory-graph-query
                                     :parameters {:body memory-graph-query-body}}}]])
