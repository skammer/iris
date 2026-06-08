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
   [:subject {:optional true} :string]
   [:predicate {:optional true} :string]
   [:object {:optional true} :string]
   [:path {:optional true} :string]
   [:content {:optional true} :string]])

(def ^:private ui-create-run-form
  [:map
   [:agent_id {:optional true} :string]
   [:name {:optional true} :string]])

(def ^:private ui-tool-approval-request-form
  [:map
   [:tool schemas/NonBlankString]
   [:reason {:optional true} :string]
   [:path {:optional true} :string]
   [:action {:optional true} :string]
   [:content {:optional true} :string]
   [:argv {:optional true} :string]
   [:command {:optional true} :string]
   [:working_dir {:optional true} :string]])

(def ^:private ui-tool-approval-decision-form
  [:map
   [:actor {:optional true} :string]
   [:reason {:optional true} :string]])

(def ^:private shell-query
  [:map
   [:tab {:optional true} :string]
   [:session_id {:optional true} :string]
   [:run_id {:optional true} :string]])

(def ^:private optional-session-id-query
  [:map [:session_id {:optional true} :string]])

(def ^:private session-id-query
  [:map [:session_id schemas/NonBlankString]])

(def ^:private run-id-query
  [:map [:run_id schemas/NonBlankString]])

(def routes
  [["/ui/shell" {:get {:handler/id :ui-shell
                       :parameters {:query shell-query}}}]
   ["/ui/dashboard" {:get {:handler/id :ui-dashboard}}]
   ["/ui/operator-board" {:get {:handler/id :ui-operator-board}}]
   ["/ui/sessions" {:get {:handler/id :ui-sessions
                          :parameters {:query optional-session-id-query}}
                    :post {:handler/id :ui-create-session
                           :parameters {:form ui-create-session-form}}}]
   ["/ui/session-detail" {:get {:handler/id :ui-session-detail
                                :parameters {:query session-id-query}}}]
   ["/ui/session-messages" {:get {:handler/id :ui-session-messages
                                  :parameters {:query session-id-query}}}]
   ["/ui/session/live" {:get {:handler/id :ui-session-live
                              :parameters {:query session-id-query}}}]
   ["/ui/chat" {:post {:handler/id :ui-chat}}]
   ["/ui/chat/stop" {:post {:handler/id :ui-chat-stop
                            :parameters {:form [:map [:session_id schemas/NonBlankString]]}}}]
   ["/ui/events" {:get {:handler/id :ui-events}}]
   ["/ui/logs" {:get {:handler/id :ui-logs}}]
   ["/ui/events/live" {:get {:handler/id :ui-events-live}}]
   ["/ui/memory/prompt" {:get {:handler/id :ui-memory-prompt}}]
   ["/ui/memory/search" {:post {:handler/id :ui-memory-search
                                :parameters {:form ui-memory-search-form}}}]
   ["/ui/memory/tool" {:post {:handler/id :ui-memory-tool
                              :parameters {:form ui-memory-tool-form}}}]
   ["/ui/memory/facts/reset" {:post {:handler/id :ui-memory-facts-reset}}]
   ["/ui/runs" {:get {:handler/id :ui-runs}
                :post {:handler/id :ui-create-run
                       :parameters {:form ui-create-run-form}}}]
   ["/ui/run-detail" {:get {:handler/id :ui-run-detail
                            :parameters {:query run-id-query}}}]
   ["/ui/run-detail-body" {:get {:handler/id :ui-run-detail-body
                                 :parameters {:query run-id-query}}}]
   ["/ui/run-detail/live" {:get {:handler/id :ui-run-detail-live
                                 :parameters {:query run-id-query}}}]
   ["/ui/tools" {:get {:handler/id :ui-tools}}]
   ["/ui/system/reload" {:post {:handler/id :ui-system-reload}}]
   ["/ui/tool-approvals" {:get {:handler/id :ui-tool-approvals}}]
   ["/ui/tool-approvals/request" {:post {:handler/id :ui-tool-approval-request
                                         :parameters {:form ui-tool-approval-request-form}}}]
   ["/ui/tool-approvals/:approval-id/approve" {:post {:handler/id :ui-tool-approval-approve
                                                      :parameters {:form ui-tool-approval-decision-form}}}]
   ["/ui/tool-approvals/:approval-id/deny" {:post {:handler/id :ui-tool-approval-deny
                                                   :parameters {:form ui-tool-approval-decision-form}}}]
   ["/ui/tool-approvals/:approval-id/run" {:post {:handler/id :ui-tool-approval-run}}]])
