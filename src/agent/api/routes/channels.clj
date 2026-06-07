(ns agent.api.routes.channels
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private channel-create-body
  [:map
   [:name {:optional true} :string]
   [:participants {:optional true} schemas/StringVec]])

(def ^:private channel-message-body
  [:map
   [:sender_id schemas/NonBlankString]
   [:content schemas/NonBlankString]])

(def routes
  [["/v1/channels" {:get {:handler/id :list-channels}
                    :post {:handler/id :create-channel
                           :orchestrator/mutating? true
                           :parameters {:body channel-create-body}}}]
   ["/v1/channels/:channel-id/messages" {:get {:handler/id :channel-messages}
                                         :post {:handler/id :channel-message
                                                :orchestrator/mutating? true
                                                :parameters {:body channel-message-body}}}]])
