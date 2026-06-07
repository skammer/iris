(ns agent.api.routes.federation
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private federation-peer-key-body
  [:map
   [:key_id schemas/NonBlankString]
   [:public_key schemas/NonBlankString]
   [:status {:optional true} schemas/NonBlankString]
   [:valid_from {:optional true} schemas/NonBlankString]
   [:valid_until {:optional true} schemas/NonBlankString]])

(def ^:private federation-peer-body
  [:map
   [:id {:optional true} schemas/NonBlankString]
   [:name {:optional true} schemas/NonBlankString]
   [:base_url {:optional true} schemas/NonBlankString]
   [:logical_address_prefix {:optional true} schemas/NonBlankString]
   [:capabilities {:optional true} schemas/StringVec]
   [:status {:optional true} :string]
   [:keys {:optional true} [:vector federation-peer-key-body]]])

(def ^:private federation-auth-body
  [:map
   [:scheme schemas/NonBlankString]
   [:key_id schemas/NonBlankString]
   [:timestamp schemas/NonBlankString]
   [:nonce schemas/NonBlankString]
   [:signature schemas/NonBlankString]])

(def ^:private federation-inbox-body
  [:map
   [:peer_id schemas/NonBlankString]
   [:to_agent_ref schemas/NonBlankString]
   [:envelope :map]
   [:auth federation-auth-body]])

(def routes
  [["/v1/federation/peers" {:get {:handler/id :list-federated-peers}
                            :post {:handler/id :create-federated-peer
                                   :orchestrator/mutating? true
                                   :parameters {:body federation-peer-body}}}]
   ["/v1/federation/inbox" {:post {:handler/id :federation-inbox
                                   :orchestrator/mutating? true
                                   :parameters {:body federation-inbox-body}}}]])
