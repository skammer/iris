(ns agent.api.routes.memory
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private memory-recall-body
  [:map
   [:query schemas/NonBlankString]
   [:limit {:optional true} :int]
   [:scope {:optional true} schemas/MemoryScope]])

(def ^:private memory-vault-read-body
  [:map [:path schemas/NonBlankString]])

(def ^:private memory-note-update-changes
  [:map
   [:type {:optional true} :string]
   [:title {:optional true} :string]
   [:description {:optional true} :string]
   [:body {:optional true} :string]
   [:tags {:optional true} schemas/StringVec]
   [:scope {:optional true} :string]])

(def ^:private memory-vault-propose-update-body
  [:map
   [:note_id schemas/NonBlankString]
   [:expected_revision schemas/NonBlankString]
   [:changes memory-note-update-changes]
   [:evidence {:optional true}
    [:map
     [:user {:optional true} :string]
     [:assistant {:optional true} :string]]]])

(def routes
  [["/v1/memory/surfaces" {:get {:handler/id :memory-surfaces}}]
   ["/v1/memory/recall" {:post {:handler/id :memory-recall
                                :parameters {:body memory-recall-body}}}]
   ["/v1/memory/vault/read" {:post {:handler/id :memory-vault-read
                                    :parameters {:body memory-vault-read-body}}}]
   ["/v1/memory/vault/propose-update"
    {:post {:handler/id :memory-vault-propose-update
            :parameters {:body memory-vault-propose-update-body}}}]
   ["/v1/memory/vault/reindex" {:post {:handler/id :memory-vault-reindex}}]])
