(ns agent.api.routes.tools)

(def ^:private tool-approval-create-body
  [:map
   [:tool :string]
   [:input :map]
   [:requested_by {:optional true} :string]
   [:reason {:optional true} :string]])

(def ^:private tool-approval-decision-body
  [:map
   [:actor {:optional true} :string]
   [:reason {:optional true} :string]])

(def ^:private tool-execute-body
  [:map
   [:input :map]
   [:approval_id {:optional true} :string]
   [:activity {:optional true} :any]])

(def ^:private system-reload-body
  [:map
   [:mode {:optional true} [:enum "soft" "full"]]])

(def ^:private slash-commands-query
  [:map
   [:prefix {:optional true} :string]
   [:page {:optional true} :int]
   [:page_size {:optional true} :int]])

(def ^:private status-query
  [:map [:status {:optional true} :string]])

(def routes
  [["/v1/tools" {:get {:handler/id :list-tools}}]
   ["/v1/tools/:tool-name/execute" {:post {:handler/id :execute-tool
                                           :parameters {:body tool-execute-body}}}]
   ["/v1/system/reload" {:post {:handler/id :system-reload
                                :parameters {:body system-reload-body}}}]
   ["/v1/tool-approvals" {:get {:handler/id :list-tool-approvals
                                :parameters {:query status-query}}
                          :post {:handler/id :create-tool-approval
                                 :parameters {:body tool-approval-create-body}}}]
   ["/v1/tool-approvals/:approval-id/approve" {:post {:handler/id :approve-tool-approval
                                                      :parameters {:body tool-approval-decision-body}}}]
   ["/v1/tool-approvals/:approval-id/deny" {:post {:handler/id :deny-tool-approval
                                                   :parameters {:body tool-approval-decision-body}}}]
   ["/v1/skills" {:get {:handler/id :list-skills}}]
   ["/v1/slash-commands" {:get {:handler/id :slash-commands
                                :parameters {:query slash-commands-query}}}]
   ["/v1/channel-adapters" {:get {:handler/id :list-channel-adapters}}]
   ["/v1/telemetry" {:get {:handler/id :telemetry}}]])
