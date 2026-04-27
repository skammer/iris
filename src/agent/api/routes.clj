(ns agent.api.routes
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private create-session-body
  [:map [:title {:optional true} :string]])

(def ^:private chat-completions-body
  [:map
   [:messages {:optional true} [:vector [:map
                                         [:role :string]
                                         [:content :string]]]]
   [:prompt {:optional true} :string]
   [:session_id {:optional true} :string]
   [:stream {:optional true} :boolean]])

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
   [:tags {:optional true} schemas/StringVec]])

(def ^:private memory-graph-query-body
  [:map
   [:query {:optional true} :string]
   [:limit {:optional true} :int]])

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

(def routes
  [["/" {:get {:handler/id :ui-index}}]
   ["/health" {:get {:handler/id :health}}]
   ["/public/*" {:get {:handler/id :public-file}}]

   ["/ui/shell" {:get {:handler/id :ui-shell}}]
   ["/ui/dashboard" {:get {:handler/id :ui-dashboard}}]
   ["/ui/operator-board" {:get {:handler/id :ui-operator-board}}]
   ["/ui/sessions" {:get {:handler/id :ui-sessions}
                    :post {:handler/id :ui-create-session}}]
   ["/ui/session-detail" {:get {:handler/id :ui-session-detail}}]
   ["/ui/session-messages" {:get {:handler/id :ui-session-messages}}]
   ["/ui/session/live" {:get {:handler/id :ui-session-live}}]
   ["/ui/chat" {:post {:handler/id :ui-chat}}]
   ["/ui/events" {:get {:handler/id :ui-events}}]
   ["/ui/events/live" {:get {:handler/id :ui-events-live}}]
   ["/ui/memory/prompt" {:get {:handler/id :ui-memory-prompt}}]
   ["/ui/memory/search" {:post {:handler/id :ui-memory-search}}]
   ["/ui/runs" {:get {:handler/id :ui-runs}
                :post {:handler/id :ui-create-run}}]
   ["/ui/run-detail" {:get {:handler/id :ui-run-detail}}]
   ["/ui/run-detail-body" {:get {:handler/id :ui-run-detail-body}}]
   ["/ui/run-detail/live" {:get {:handler/id :ui-run-detail-live}}]
   ["/ui/runs/:run-id/launch" {:post {:handler/id :ui-run-launch}}]
   ["/ui/runs/:run-id/signal" {:post {:handler/id :ui-run-signal}}]
   ["/ui/tools" {:get {:handler/id :ui-tools}}]
   ["/ui/tool-approvals" {:get {:handler/id :ui-tool-approvals}}]
   ["/ui/tool-approvals/request" {:post {:handler/id :ui-tool-approval-request}}]
   ["/ui/tool-approvals/:approval-id/approve" {:post {:handler/id :ui-tool-approval-approve}}]
   ["/ui/tool-approvals/:approval-id/deny" {:post {:handler/id :ui-tool-approval-deny}}]
   ["/ui/tool-approvals/:approval-id/run" {:post {:handler/id :ui-tool-approval-run}}]

   ["/v1/sessions" {:get {:handler/id :list-sessions}
                    :post {:handler/id :create-session
                           :parameters {:body create-session-body}}}]
   ["/v1/sessions/:session-id/messages" {:get {:handler/id :list-session-messages}}]
   ["/v1/chat/completions" {:post {:handler/id :chat-completions}}]
   ["/v1/runs" {:get {:handler/id :list-runs}
                :post {:handler/id :create-run}}]
   ["/v1/runs/reclaim-stale" {:post {:handler/id :reclaim-stale-runs}}]
   ["/v1/runs/:run-id" {:get {:handler/id :get-run}}]
   ["/v1/runs/:run-id/launch" {:post {:handler/id :launch-run}}]
   ["/v1/runs/:run-id/signal" {:post {:handler/id :signal-run
                                      :parameters {:body signal-body}}}]
   ["/v1/runs/:run-id/heartbeats" {:get {:handler/id :run-heartbeats}}]
   ["/v1/runs/:run-id/checkpoints" {:get {:handler/id :run-checkpoints}}]
   ["/v1/runs/:run-id/commands" {:get {:handler/id :run-commands}}]
   ["/v1/runs/:run-id/control/register" {:post {:handler/id :run-control-register}}]
   ["/v1/runs/:run-id/control/heartbeat" {:post {:handler/id :run-control-heartbeat}}]
   ["/v1/runs/:run-id/control/checkpoint" {:post {:handler/id :run-control-checkpoint}}]
   ["/v1/runs/:run-id/control/commands" {:get {:handler/id :run-control-commands}}]
   ["/v1/runs/:run-id/control/commands/:command-id/ack" {:post {:handler/id :run-control-command-ack}}]
   ["/v1/runs/:run-id/control/commands/:command-id/complete" {:post {:handler/id :run-control-command-complete}}]
   ["/v1/runs/:run-id/control/transition" {:post {:handler/id :run-control-transition}}]
   ["/v1/runs/:run-id/events" {:get {:handler/id :run-events}}]
   ["/v1/runs/:run-id/stream" {:get {:handler/id :run-events-stream}}]
   ["/v1/runs/:run-id/wait" {:get {:handler/id :run-wait}}]
   ["/v1/runs/:run-id/recover" {:post {:handler/id :run-recover}}]

   ["/v1/tools" {:get {:handler/id :list-tools}}]
   ["/v1/tools/:tool-name/execute" {:post {:handler/id :execute-tool
                                           :parameters {:body tool-execute-body}}}]
   ["/v1/tool-approvals" {:get {:handler/id :list-tool-approvals}
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
                  :post {:handler/id :create-agent}}]
   ["/v1/agents/:agent-id/messages" {:get {:handler/id :agent-messages}
                                     :post {:handler/id :agent-message
                                            :parameters {:body agent-message-body}}}]
   ["/v1/agents/:agent-id/tools/:tool-name/execute" {:post {:handler/id :agent-tool-execute
                                                            :parameters {:body agent-tool-execute-body}}}]
   ["/v1/agents/:agent-id/spawn-worker" {:post {:handler/id :orchestrator-spawn-worker}}]
   ["/v1/agents/:agent-id/steps" {:post {:handler/id :agent-step-execute}}]
   ["/v1/agents/:agent-id/inbox/consume" {:post {:handler/id :consume-agent-inbox}}]
   ["/v1/agents/:agent-id/interop" {:get {:handler/id :agent-interop}}]
   ["/v1/agents/:agent-id/interop/capabilities" {:post {:handler/id :agent-interop-capabilities}}]
   ["/v1/agents/:agent-id/interop/messages" {:get {:handler/id :agent-interop-messages}
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
