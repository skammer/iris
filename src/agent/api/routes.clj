(ns agent.api.routes
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private create-session-body
  [:map [:title {:optional true} :string]])

(def ^:private chat-completions-body
  [:map
   [:messages {:optional true} [:vector {:min 1} schemas/ChatMessage]]
   [:prompt {:optional true} :string]
   [:session_id {:optional true} :string]
   [:stream {:optional true} :boolean]])

(def ^:private create-run-body
  [:map
   [:agent_id {:optional true} :string]
   [:parent_run_id {:optional true} :string]
   [:idempotency_key {:optional true} :string]
   [:name {:optional true} :string]
   [:substrate {:optional true} :string]
   [:capabilities {:optional true} schemas/StringVec]
   [:network_identity {:optional true} :map]
   [:runner_options {:optional true} :map]
   [:requested_by {:optional true} :string]
   [:auto_launch {:optional true} :boolean]])

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
   [:schema-version {:optional true} :any]
   [:schema_version {:optional true} :any]
   [:state {:optional true} :map]
   [:receipts {:optional true} [:vector :any]]
   [:yolo {:optional true} :boolean]
   [:yolo? {:optional true} :boolean]])

(def ^:private memory-search-body
  [:map
   [:query schemas/NonBlankString]
   [:limit {:optional true} :int]])

(def ^:private memory-fact-save-body
  [:map
   [:subject schemas/NonBlankString]
   [:predicate schemas/NonBlankString]
   [:object schemas/NonBlankString]
   [:confidence {:optional true} number?]
   [:scope {:optional true} schemas/MemoryScope]
   [:source_session_id {:optional true} :string]
   [:source_message_ids {:optional true} schemas/StringVec]
   [:source_request_id {:optional true} :string]])

(def ^:private memory-fact-search-body
  [:map
   [:query {:optional true} :string]
   [:limit {:optional true} :int]
   [:scope {:optional true} schemas/MemoryScope]
   [:all_scopes {:optional true} :boolean]])

(def ^:private memory-vault-read-body
  [:map [:path schemas/NonBlankString]])

(def ^:private memory-vault-write-body
  [:map
   [:path schemas/NonBlankString]
   [:content schemas/NonBlankString]])

(def ^:private memory-graph-save-body
  [:map
   [:subject schemas/NonBlankString]
   [:predicate schemas/NonBlankString]
   [:object schemas/NonBlankString]
   [:id {:optional true} :string]
   [:type {:optional true} :string]
   [:source {:optional true} :string]
   [:session_id {:optional true} :string]
   [:source_request_id {:optional true} :string]
   [:episode_id {:optional true} :string]
   [:episode_content {:optional true} :string]
   [:confidence {:optional true} number?]
   [:valid_from {:optional true} :string]
   [:valid_to {:optional true} :string]
   [:observed_at {:optional true} :string]
   [:invalidated_by {:optional true} :string]
   [:tags {:optional true} schemas/StringVec]])

(def ^:private memory-graph-query-body
  [:map
   [:mode {:optional true} :string]
   [:query {:optional true} :string]
   [:limit {:optional true} :int]
   [:entity {:optional true} :string]
   [:depth {:optional true} :int]
   [:from {:optional true} :string]
   [:to {:optional true} :string]
   [:max_depth {:optional true} :int]
   [:as_of {:optional true} :string]
   [:include_historical {:optional true} :boolean]])

(def ^:private channel-create-body
  [:map
   [:name {:optional true} :string]
   [:participants {:optional true} schemas/StringVec]])

(def ^:private channel-message-body
  [:map
   [:sender_id schemas/NonBlankString]
   [:content schemas/NonBlankString]])

(def ^:private federation-peer-body
  [:map
   [:id {:optional true} schemas/NonBlankString]
   [:name {:optional true} schemas/NonBlankString]
   [:base_url {:optional true} schemas/NonBlankString]
   [:logical_address_prefix {:optional true} schemas/NonBlankString]
   [:capabilities {:optional true} schemas/StringVec]
   [:status {:optional true} :string]
   [:key_id {:optional true} schemas/NonBlankString]
   [:public_key {:optional true} schemas/NonBlankString]
   [:private_key {:optional true} schemas/NonBlankString]])

(def ^:private federation-inbox-body
  [:map
   [:peer_id schemas/NonBlankString]
   [:to_agent_ref schemas/NonBlankString]
   [:envelope :map]])

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

(def ^:private signal-body
  [:map [:command_type schemas/NonBlankString]])

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

(def ^:private ui-create-session-form
  [:map [:title {:optional true} :string]])

(def ^:private ui-chat-form
  [:map
   [:session_id schemas/NonBlankString]
   [:prompt schemas/NonBlankString]])

(def ^:private ui-memory-search-form
  [:map [:query schemas/NonBlankString]])

(def ^:private ui-create-run-form
  [:map
   [:agent_id {:optional true} :string]
   [:name {:optional true} :string]
   [:substrate {:optional true} :string]
   [:command {:optional true} :string]
   [:working_dir {:optional true} :string]
   [:image {:optional true} :string]
   [:share_network {:optional true} :string]])

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

(def ^:private since-limit-query
  [:map
   [:limit {:optional true} :int]
   [:since_sequence {:optional true} :int]])

(def ^:private commands-query
  [:map
   [:limit {:optional true} :int]
   [:status {:optional true} :string]
   [:request_id {:optional true} :string]])

(def ^:private events-query
  [:map
   [:limit {:optional true} :int]
   [:after_id {:optional true} :int]])

(def ^:private wait-query
  [:map
   [:timeout_ms {:optional true} :int]
   [:interval_ms {:optional true} :int]])

(def ^:private stream-query
  [:map
   [:after_id {:optional true} :int]
   [:replay_limit {:optional true} :int]])

(def ^:private interop-list-query
  [:map
   [:direction {:optional true} :string]
   [:status {:optional true} :string]])

(def ^:private status-query
  [:map [:status {:optional true} :string]])

(def ^:private shell-query
  [:map [:tab {:optional true} :string]])

(def ^:private session-id-query
  [:map [:session_id schemas/NonBlankString]])

(def ^:private run-id-query
  [:map [:run_id schemas/NonBlankString]])

(def routes
  [["/" {:get {:handler/id :ui-index}}]
   ["/health" {:get {:handler/id :health}}]
   ["/public/*" {:get {:handler/id :public-file}}]

   ["/ui/shell" {:get {:handler/id :ui-shell
                       :parameters {:query shell-query}}}]
   ["/ui/dashboard" {:get {:handler/id :ui-dashboard}}]
   ["/ui/operator-board" {:get {:handler/id :ui-operator-board}}]
   ["/ui/sessions" {:get {:handler/id :ui-sessions}
                    :post {:handler/id :ui-create-session
                           :parameters {:form ui-create-session-form}}}]
   ["/ui/session-detail" {:get {:handler/id :ui-session-detail
                                :parameters {:query session-id-query}}}]
   ["/ui/session-messages" {:get {:handler/id :ui-session-messages
                                  :parameters {:query session-id-query}}}]
   ["/ui/session/live" {:get {:handler/id :ui-session-live
                              :parameters {:query session-id-query}}}]
   ["/ui/chat" {:post {:handler/id :ui-chat
                       :parameters {:form ui-chat-form}}}]
   ["/ui/events" {:get {:handler/id :ui-events}}]
   ["/ui/events/live" {:get {:handler/id :ui-events-live}}]
   ["/ui/memory/prompt" {:get {:handler/id :ui-memory-prompt}}]
   ["/ui/memory/search" {:post {:handler/id :ui-memory-search
                                :parameters {:form ui-memory-search-form}}}]
   ["/ui/runs" {:get {:handler/id :ui-runs}
                :post {:handler/id :ui-create-run
                       :parameters {:form ui-create-run-form}}}]
   ["/ui/run-detail" {:get {:handler/id :ui-run-detail
                            :parameters {:query run-id-query}}}]
   ["/ui/run-detail-body" {:get {:handler/id :ui-run-detail-body
                                 :parameters {:query run-id-query}}}]
   ["/ui/run-detail/live" {:get {:handler/id :ui-run-detail-live
                                 :parameters {:query run-id-query}}}]
   ["/ui/runs/:run-id/launch" {:post {:handler/id :ui-run-launch}}]
   ["/ui/runs/:run-id/signal" {:post {:handler/id :ui-run-signal}}]
   ["/ui/tools" {:get {:handler/id :ui-tools}}]
   ["/ui/tool-approvals" {:get {:handler/id :ui-tool-approvals}}]
   ["/ui/tool-approvals/request" {:post {:handler/id :ui-tool-approval-request
                                         :parameters {:form ui-tool-approval-request-form}}}]
   ["/ui/tool-approvals/:approval-id/approve" {:post {:handler/id :ui-tool-approval-approve
                                                      :parameters {:form ui-tool-approval-decision-form}}}]
   ["/ui/tool-approvals/:approval-id/deny" {:post {:handler/id :ui-tool-approval-deny
                                                   :parameters {:form ui-tool-approval-decision-form}}}]
   ["/ui/tool-approvals/:approval-id/run" {:post {:handler/id :ui-tool-approval-run}}]

   ["/v1/sessions" {:get {:handler/id :list-sessions}
                    :post {:handler/id :create-session
                           :parameters {:body create-session-body}}}]
   ["/v1/sessions/:session-id/messages" {:get {:handler/id :list-session-messages}}]
   ["/v1/chat/completions" {:post {:handler/id :chat-completions
                                    :parameters {:body chat-completions-body}}}]
   ["/v1/runs" {:get {:handler/id :list-runs}
                :post {:handler/id :create-run
                       :parameters {:body create-run-body}}}]
   ["/v1/runs/reclaim-stale" {:post {:handler/id :reclaim-stale-runs}}]
   ["/v1/runs/:run-id" {:get {:handler/id :get-run}}]
   ["/v1/runs/:run-id/launch" {:post {:handler/id :launch-run}}]
   ["/v1/runs/:run-id/signal" {:post {:handler/id :signal-run
                                      :parameters {:body signal-body}}}]
   ["/v1/runs/:run-id/heartbeats" {:get {:handler/id :run-heartbeats
                                         :parameters {:query since-limit-query}}}]
   ["/v1/runs/:run-id/checkpoints" {:get {:handler/id :run-checkpoints
                                          :parameters {:query since-limit-query}}}]
   ["/v1/runs/:run-id/commands" {:get {:handler/id :run-commands
                                       :parameters {:query commands-query}}}]
   ["/v1/runs/:run-id/control/register" {:post {:handler/id :run-control-register}}]
   ["/v1/runs/:run-id/control/heartbeat" {:post {:handler/id :run-control-heartbeat}}]
   ["/v1/runs/:run-id/control/checkpoint" {:post {:handler/id :run-control-checkpoint}}]
   ["/v1/runs/:run-id/control/commands" {:get {:handler/id :run-control-commands}}]
   ["/v1/runs/:run-id/control/commands/:command-id/ack" {:post {:handler/id :run-control-command-ack}}]
   ["/v1/runs/:run-id/control/commands/:command-id/complete" {:post {:handler/id :run-control-command-complete}}]
   ["/v1/runs/:run-id/control/transition" {:post {:handler/id :run-control-transition}}]
   ["/v1/runs/:run-id/events" {:get {:handler/id :run-events
                                     :parameters {:query events-query}}}]
   ["/v1/runs/:run-id/stream" {:get {:handler/id :run-events-stream
                                     :parameters {:query stream-query}}}]
   ["/v1/runs/:run-id/wait" {:get {:handler/id :run-wait
                                   :parameters {:query wait-query}}}]
   ["/v1/runs/:run-id/recover" {:post {:handler/id :run-recover}}]

   ["/v1/tools" {:get {:handler/id :list-tools}}]
   ["/v1/tools/:tool-name/execute" {:post {:handler/id :execute-tool
                                           :parameters {:body tool-execute-body}}}]
   ["/v1/tool-approvals" {:get {:handler/id :list-tool-approvals
                                :parameters {:query status-query}}
                          :post {:handler/id :create-tool-approval
                                 :parameters {:body tool-approval-create-body}}}]
   ["/v1/tool-approvals/:approval-id/approve" {:post {:handler/id :approve-tool-approval
                                                      :parameters {:body tool-approval-decision-body}}}]
   ["/v1/tool-approvals/:approval-id/deny" {:post {:handler/id :deny-tool-approval
                                                   :parameters {:body tool-approval-decision-body}}}]
   ["/v1/skills" {:get {:handler/id :list-skills}}]
   ["/v1/channel-adapters" {:get {:handler/id :list-channel-adapters}}]
   ["/v1/events" {:get {:handler/id :list-events}}]
   ["/v1/events/stream" {:get {:handler/id :events-stream}}]
   ["/v1/telemetry" {:get {:handler/id :telemetry}}]
   ["/v1/memory/surfaces" {:get {:handler/id :memory-surfaces}}]
   ["/v1/memory/prompt" {:get {:handler/id :memory-prompt}}]
   ["/v1/memory/search" {:post {:handler/id :memory-search
                                :parameters {:body memory-search-body}}}]
   ["/v1/memory/facts" {:post {:handler/id :memory-fact-save
                               :parameters {:body memory-fact-save-body}}}]
   ["/v1/memory/facts/search" {:post {:handler/id :memory-fact-search
                                      :parameters {:body memory-fact-search-body}}}]
   ["/v1/memory/vault/read" {:post {:handler/id :memory-vault-read
                                    :parameters {:body memory-vault-read-body}}}]
   ["/v1/memory/vault/write" {:post {:handler/id :memory-vault-write
                                     :parameters {:body memory-vault-write-body}}}]
   ["/v1/memory/graph/facts" {:post {:handler/id :memory-graph-save
                                     :parameters {:body memory-graph-save-body}}}]
   ["/v1/memory/graph/query" {:post {:handler/id :memory-graph-query
                                     :parameters {:body memory-graph-query-body}}}]

   ["/v1/agents" {:get {:handler/id :list-agents}
                  :post {:handler/id :create-agent
                         :parameters {:body create-agent-body}}}]
   ["/v1/agents/:agent-id/messages" {:get {:handler/id :agent-messages}
                                     :post {:handler/id :agent-message
                                            :parameters {:body agent-message-body}}}]
   ["/v1/agents/:agent-id/tools/:tool-name/execute" {:post {:handler/id :agent-tool-execute
                                                            :parameters {:body agent-tool-execute-body}}}]
   ["/v1/agents/:agent-id/spawn-worker" {:post {:handler/id :orchestrator-spawn-worker
                                                :parameters {:body orchestrator-spawn-worker-body}}}]
   ["/v1/agents/:agent-id/steps" {:post {:handler/id :agent-step-execute
                                         :parameters {:body agent-step-execute-body}}}]
   ["/v1/agents/:agent-id/inbox/consume" {:post {:handler/id :consume-agent-inbox}}]
   ["/v1/agents/:agent-id/interop" {:get {:handler/id :agent-interop}}]
   ["/v1/agents/:agent-id/interop/capabilities" {:post {:handler/id :agent-interop-capabilities
                                                        :parameters {:body interop-capabilities-body}}}]
   ["/v1/agents/:agent-id/interop/messages" {:get {:handler/id :agent-interop-messages
                                                   :parameters {:query interop-list-query}}
                                             :post {:handler/id :agent-interop-message
                                                    :parameters {:body interop-message-post-body}}}]
   ["/v1/agents/:agent-id/interop/messages/:message-id/ack" {:post {:handler/id :agent-interop-ack
                                                                    :parameters {:body interop-ack-body}}}]
   ["/v1/agents/:agent-id/interop/messages/:message-id/retry" {:post {:handler/id :agent-interop-retry}}]

   ["/v1/federation/peers" {:get {:handler/id :list-federated-peers}
                            :post {:handler/id :create-federated-peer
                                   :parameters {:body federation-peer-body}}}]
   ["/v1/federation/inbox" {:post {:handler/id :federation-inbox
                                   :parameters {:body federation-inbox-body}}}]

   ["/v1/channels" {:get {:handler/id :list-channels}
                    :post {:handler/id :create-channel
                           :parameters {:body channel-create-body}}}]
   ["/v1/channels/:channel-id/messages" {:get {:handler/id :channel-messages}
                                         :post {:handler/id :channel-message
                                                :parameters {:body channel-message-body}}}]])
