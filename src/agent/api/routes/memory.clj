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

(def ^:private memory-vault-write-body
  [:map
   [:path schemas/NonBlankString]
   [:content schemas/NonBlankString]])

(def routes
  [["/v1/memory/surfaces" {:get {:handler/id :memory-surfaces}}]
   ["/v1/memory/recall" {:post {:handler/id :memory-recall
                                :parameters {:body memory-recall-body}}}]
   ["/v1/memory/vault/read" {:post {:handler/id :memory-vault-read
                                    :parameters {:body memory-vault-read-body}}}]
   ["/v1/memory/vault/write" {:post {:handler/id :memory-vault-write
                                     :parameters {:body memory-vault-write-body}}}]
   ["/v1/memory/vault/reindex" {:post {:handler/id :memory-vault-reindex}}]])
