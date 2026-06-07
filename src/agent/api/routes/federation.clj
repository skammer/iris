(ns agent.api.routes.federation
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private federation-peer-body
  [:map
   [:id {:optional true} schemas/NonBlankString]
   [:name {:optional true} schemas/NonBlankString]
   [:base_url {:optional true} schemas/NonBlankString]
   [:logical_address_prefix {:optional true} schemas/NonBlankString]
   [:capabilities {:optional true} schemas/StringVec]
   [:status {:optional true} :string]
   [:key_id {:optional true} schemas/NonBlankString]
   [:public_key {:optional true} schemas/NonBlankString]])

(def ^:private federation-inbox-body
  [:map
   [:peer_id schemas/NonBlankString]
   [:to_agent_ref schemas/NonBlankString]
   [:envelope :map]])

(def routes
  [["/v1/federation/peers" {:get {:handler/id :list-federated-peers}
                            :post {:handler/id :create-federated-peer
                                   :orchestrator/mutating? true
                                   :parameters {:body federation-peer-body}}}]
   ["/v1/federation/inbox" {:post {:handler/id :federation-inbox
                                   :orchestrator/mutating? true
                                   :parameters {:body federation-inbox-body}}}]])
