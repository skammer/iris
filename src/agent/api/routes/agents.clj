(ns agent.api.routes.agents
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private create-agent-body
  [:map
   [:name {:optional true} :string]
   [:kind {:optional true} schemas/NonBlankString]
   [:role {:optional true} :string]
   [:parent_id {:optional true} schemas/NonBlankString]
   [:system_prompt {:optional true} schemas/NonBlankString]
   [:capabilities {:optional true} schemas/StringVec]
   [:tool_access {:optional true} schemas/StringVec]
   [:memory_scopes {:optional true} schemas/StringVec]
   [:budgets {:optional true} :map]
   [:task {:optional true} :map]
   [:allow_direct {:optional true} :boolean]
   [:trusted_peers {:optional true} schemas/StringVec]
   [:trust_policies {:optional true} schemas/TrustPolicies]
   [:rate_limit_per_minute {:optional true} :int]])

(def ^:private interop-capabilities-body
  [:map
   [:capabilities {:optional true} schemas/StringVec]
   [:tool_access {:optional true} schemas/StringVec]
   [:memory_scopes {:optional true} schemas/StringVec]
   [:budgets {:optional true} :map]
   [:allow_direct {:optional true} :boolean]
   [:trusted_peers {:optional true} schemas/StringVec]
   [:trust_policies {:optional true} schemas/TrustPolicies]
   [:rate_limit_per_minute {:optional true} :int]])

(def ^:private orchestrator-spawn-worker-body
  [:map
   [:name {:optional true} schemas/NonBlankString]
   [:role {:optional true} schemas/NonBlankString]
   [:task :map]
   [:capabilities {:optional true} schemas/StringVec]
   [:tool_access {:optional true} schemas/StringVec]
   [:memory_scopes {:optional true} schemas/StringVec]
   [:budgets {:optional true} :map]
   [:system_prompt {:optional true} :string]])

(def ^:private agent-step-execute-body
  [:map
   [:directives {:optional true} [:vector schemas/Directive]]
   [:schema-version {:optional true} :string]
   [:schema_version {:optional true} :string]
   [:state {:optional true} :map]
   [:receipts {:optional true} [:vector :map]]
   [:yolo {:optional true} :boolean]
   [:yolo? {:optional true} :boolean]])

(def ^:private agent-message-body
  [:map
   [:role {:optional true} :string]
   [:content schemas/NonBlankString]])

(def ^:private agent-tool-execute-body
  [:map
   [:input :map]
   [:approval_id {:optional true} :string]
   [:request_id {:optional true} :string]
   [:activity {:optional true} :any]])

(def ^:private interop-message-post-body
  [:map
   [:from_agent_id schemas/NonBlankString]
   [:to_agent_ref {:optional true} :string]
   [:content schemas/NonBlankString]
   [:message_type {:optional true} :string]
   [:route {:optional true} :string]
   [:delivery_mode {:optional true} :string]
   [:request_id {:optional true} :string]])

(def ^:private interop-ack-body
  [:map [:ack_type {:optional true} :string]])

(def ^:private interop-list-query
  [:map
   [:direction {:optional true} :string]
   [:status {:optional true} :string]])

(def routes
  [["/v1/agents" {:get {:handler/id :list-agents}
                  :post {:handler/id :create-agent
                         :orchestrator/mutating? true
                         :parameters {:body create-agent-body}}}]
   ["/v1/agents/:agent-id/messages" {:get {:handler/id :agent-messages}
                                     :post {:handler/id :agent-message
                                            :orchestrator/mutating? true
                                            :parameters {:body agent-message-body}}}]
   ["/v1/agents/:agent-id/tools/:tool-name/execute" {:post {:handler/id :agent-tool-execute
                                                            :orchestrator/mutating? true
                                                            :parameters {:body agent-tool-execute-body}}}]
   ["/v1/agents/:agent-id/spawn-worker" {:post {:handler/id :orchestrator-spawn-worker
                                                :orchestrator/mutating? true
                                                :parameters {:body orchestrator-spawn-worker-body}}}]
   ["/v1/agents/:agent-id/steps" {:post {:handler/id :agent-step-execute
                                         :orchestrator/mutating? true
                                         :parameters {:body agent-step-execute-body}}}]
   ["/v1/agents/:agent-id/inbox/consume" {:post {:handler/id :consume-agent-inbox
                                                 :orchestrator/mutating? true}}]
   ["/v1/agents/:agent-id/interop" {:get {:handler/id :agent-interop}}]
   ["/v1/agents/:agent-id/interop/capabilities" {:post {:handler/id :agent-interop-capabilities
                                                        :orchestrator/mutating? true
                                                        :parameters {:body interop-capabilities-body}}}]
   ["/v1/agents/:agent-id/interop/messages" {:get {:handler/id :agent-interop-messages
                                                   :parameters {:query interop-list-query}}
                                             :post {:handler/id :agent-interop-message
                                                    :orchestrator/mutating? true
                                                    :parameters {:body interop-message-post-body}}}]
   ["/v1/agents/:agent-id/interop/messages/:message-id/ack" {:post {:handler/id :agent-interop-ack
                                                                    :orchestrator/mutating? true
                                                                    :parameters {:body interop-ack-body}}}]
   ["/v1/agents/:agent-id/interop/messages/:message-id/retry" {:post {:handler/id :agent-interop-retry
                                                                      :orchestrator/mutating? true}}]])
