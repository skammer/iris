(ns agent.api.routes.ui
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private ui-create-session-form
  [:map [:title {:optional true} :string]])

(def ^:private ui-memory-search-form
  [:map [:query schemas/NonBlankString]])

(def ^:private ui-memory-tool-form
  [:map
   [:action schemas/NonBlankString]
   [:query {:optional true} :string]
   [:limit {:optional true} :string]
   [:scope_type {:optional true} :string]
   [:scope_id {:optional true} :string]
   [:old_text {:optional true} :string]
   [:new_text {:optional true} :string]
   [:expected_revision {:optional true} :string]])

(def ^:private ui-memory-vault-status-form
  [:map
   [:path schemas/NonBlankString]
   [:status {:optional true} :string]
   [:scope {:optional true} :string]])

(def ^:private ui-memory-vault-move-form
  [:map
   [:path schemas/NonBlankString]
   [:folder schemas/NonBlankString]])

(def ^:private ui-memory-vault-magi-form
  [:map
   [:path schemas/NonBlankString]
   [:action [:enum "review" "advice"]]])

(def ^:private ui-memory-update-magi-form
  [:map
   [:update_id schemas/NonBlankString]
   [:action [:enum "review" "advice"]]])

(def ^:private ui-tool-approval-decision-form
  [:map
   [:actor {:optional true} :string]
   [:reason {:optional true} :string]])

(def ^:private shell-query
  [:map
   [:tab {:optional true} :string]
   [:session_id {:optional true} :string]
   [:client_id {:optional true} :string]])

(def ^:private optional-session-id-query
  [:map [:session_id {:optional true} :string]])

(def ^:private progressive-limit-query
  [:map [:limit {:optional true} :string]])

(def ^:private cron-query
  [:map
   [:limit {:optional true} :string]
   [:tab {:optional true} :string]
   [:view {:optional true} :string]
   [:date {:optional true} :string]])

(def ^:private session-id-query
  [:map
   [:session_id schemas/NonBlankString]
   [:client_id {:optional true} :string]])

(def ^:private session-messages-query
  [:map
   [:session_id schemas/NonBlankString]
   [:limit {:optional true} :string]])

(def ^:private tool-detail-query
  [:map
   [:session_id schemas/NonBlankString]
   [:message_id schemas/NonBlankString]
   [:tool_call_id schemas/NonBlankString]])

(def routes
  [["/ui/shell" {:get {:handler/id :ui-shell
                       :parameters {:query shell-query}}}]
   ["/ui/route" {:get {:handler/id :ui-route
                       :parameters {:query shell-query}}}]
   ["/ui/dashboard" {:get {:handler/id :ui-dashboard}}]
   ["/ui/cron" {:get {:handler/id :ui-cron
                        :parameters {:query cron-query}}
                 :post {:handler/id :ui-cron-create}}]
   ["/ui/cron/jobs" {:post {:handler/id :ui-cron-create}}]
   ["/ui/cron/status" {:get {:handler/id :ui-cron-status
                              :parameters {:query cron-query}}}]
   ["/ui/cron/jobs/:job-id/detail" {:get {:handler/id :ui-cron-job-detail}}]
   ["/ui/cron/runs/:run-id/detail" {:get {:handler/id :ui-cron-run-detail}}]
   ["/ui/cron/preview" {:post {:handler/id :ui-cron-preview}}]
   ["/ui/cron/action" {:post {:handler/id :ui-cron-action}}]
   ["/ui/operator-board" {:get {:handler/id :ui-operator-board}}]
   ["/ui/sessions" {:get {:handler/id :ui-sessions
                          :parameters {:query optional-session-id-query}}
                    :post {:handler/id :ui-create-session
                           :parameters {:form ui-create-session-form}}}]
   ["/ui/session-detail" {:get {:handler/id :ui-session-detail
                                :parameters {:query session-id-query}}}]
   ["/ui/session-messages" {:get {:handler/id :ui-session-messages
                                  :parameters {:query session-messages-query}}}]
   ["/ui/session/live" {:get {:handler/id :ui-session-live
                              :parameters {:query session-id-query}}}]
   ["/ui/chat" {:post {:handler/id :ui-chat}}]
   ["/ui/chat/tool-detail" {:get {:handler/id :ui-chat-tool-detail
                                   :parameters {:query tool-detail-query}}}]
   ["/ui/chat/stop" {:post {:handler/id :ui-chat-stop
                            :parameters {:form [:map [:session_id schemas/NonBlankString]]}}}]
   ["/ui/events" {:get {:handler/id :ui-events}}]
   ["/ui/logs" {:get {:handler/id :ui-logs
                        :parameters {:query progressive-limit-query}}}]
   ["/ui/logs/:source/:entry-id/detail" {:get {:handler/id :ui-log-detail}}]
   ["/ui/magi" {:get {:handler/id :ui-magi
                        :parameters {:query progressive-limit-query}}}]
   ["/ui/magi/:event-id/detail" {:get {:handler/id :ui-magi-detail}}]
   ["/ui/events/live" {:get {:handler/id :ui-events-live}}]
   ["/ui/memory" {:get {:handler/id :ui-memory
                          :parameters {:query progressive-limit-query}}}]
   ["/ui/memory/vault/:note-id/detail" {:get {:handler/id :ui-memory-vault-detail}}]
   ["/ui/memory/updates/:update-id/detail" {:get {:handler/id :ui-memory-update-detail}}]
   ["/ui/memory/search" {:post {:handler/id :ui-memory-search
                                :parameters {:form ui-memory-search-form}}}]
   ["/ui/memory/tool" {:post {:handler/id :ui-memory-tool
                              :parameters {:form ui-memory-tool-form}}}]
   ["/ui/memory/vault/status" {:post {:handler/id :ui-memory-vault-status
                                      :parameters {:form ui-memory-vault-status-form}}}]
   ["/ui/memory/vault/magi" {:post {:handler/id :ui-memory-vault-magi
                                    :parameters {:form ui-memory-vault-magi-form}}}]
   ["/ui/memory/vault/magi-update"
    {:post {:handler/id :ui-memory-vault-magi-update
            :parameters {:form ui-memory-update-magi-form}}}]
   ["/ui/memory/vault/move" {:post {:handler/id :ui-memory-vault-move
                                    :parameters {:form ui-memory-vault-move-form}}}]
   ["/ui/memory/vault/reindex" {:post {:handler/id :ui-memory-vault-reindex}}]
   ["/ui/system/reload" {:post {:handler/id :ui-system-reload}}]
   ["/ui/tool-approvals" {:get {:handler/id :ui-tool-approvals
                                 :parameters {:query progressive-limit-query}}}]
   ["/ui/tool-approvals/status" {:get {:handler/id :ui-tool-approvals-status
                                        :parameters {:query progressive-limit-query}}}]
   ["/ui/tool-approvals/:approval-id/detail" {:get {:handler/id :ui-tool-approval-detail}}]
   ["/ui/tool-approvals/:approval-id/approve" {:post {:handler/id :ui-tool-approval-approve
                                                      :parameters {:form ui-tool-approval-decision-form}}}]
   ["/ui/tool-approvals/:approval-id/deny" {:post {:handler/id :ui-tool-approval-deny
                                                   :parameters {:form ui-tool-approval-decision-form}}}]
   ["/ui/tool-approvals/:approval-id/run" {:post {:handler/id :ui-tool-approval-run}}]])
