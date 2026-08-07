(ns agent.api.routes.sessions
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private create-session-body
  [:map [:title {:optional true} :string]])

(def ^:private append-entry-body
  [:map
   [:id {:optional true} :string]
   [:parent_id {:optional true} [:maybe :string]]
   [:type schemas/NonBlankString]
   [:payload {:optional true} :map]
   [:select_leaf {:optional true} :boolean]])

(def ^:private select-leaf-body
  [:map
   [:entry_id schemas/NonBlankString]
   [:branch_summary {:optional true} :boolean]])

(def ^:private set-session-mode-body
  [:map [:mode [:maybe schemas/NonBlankString]]])

(def routes
  [["/v1/sessions" {:get {:handler/id :list-sessions
                           :parameters {:query [:map [:kind {:optional true} [:enum "chat" "cron"]]]}}
                    :post {:handler/id :create-session
                           :parameters {:body create-session-body}}}]
   ["/v1/sessions/:session-id" {:get {:handler/id :get-session}}]
   ["/v1/sessions/:session-id/mode" {:post {:handler/id :set-session-mode
                                            :parameters {:body set-session-mode-body}}}]
   ["/v1/sessions/:session-id/messages" {:get {:handler/id :list-session-messages}}]
   ["/v1/sessions/:session-id/entries" {:get {:handler/id :list-session-entries}
                                        :post {:handler/id :append-session-entry
                                               :parameters {:body append-entry-body}}}]
   ["/v1/sessions/:session-id/path" {:get {:handler/id :session-current-path}}]
   ["/v1/sessions/:session-id/tree" {:get {:handler/id :session-tree}}]
   ["/v1/sessions/:session-id/leaf" {:post {:handler/id :select-session-leaf
                                            :parameters {:body select-leaf-body}}}]
   ["/v1/sessions/:session-id/compact" {:post {:handler/id :compact-session}}]])
