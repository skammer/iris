(ns agent.api.routes.runs
  (:require
   [agent.api.schemas :as schemas]))

(def ^:private create-run-substrate
  [:enum "external"])

(def ^:private create-run-body
  [:map {:closed true}
   [:agent_id {:optional true} :string]
   [:parent_run_id {:optional true} :string]
   [:idempotency_key {:optional true} :string]
   [:name {:optional true} :string]
   [:substrate {:optional true} create-run-substrate]
   [:capabilities {:optional true} schemas/StringVec]
   [:network_identity {:optional true} :map]
   [:requested_by {:optional true} :string]])

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

(def routes
  [["/v1/runs" {:get {:handler/id :list-runs}
                :post {:handler/id :create-run
                       :parameters {:body create-run-body}}}]
   ["/v1/runs/reclaim-stale" {:post {:handler/id :reclaim-stale-runs}}]
   ["/v1/runs/:run-id" {:get {:handler/id :get-run}}]
   ["/v1/runs/:run-id/heartbeats" {:get {:handler/id :run-heartbeats
                                         :parameters {:query since-limit-query}}}]
   ["/v1/runs/:run-id/checkpoints" {:get {:handler/id :run-checkpoints
                                          :parameters {:query since-limit-query}}}]
   ["/v1/runs/:run-id/commands" {:get {:handler/id :run-commands
                                       :parameters {:query commands-query}}}]
   ["/v1/runs/:run-id/events" {:get {:handler/id :run-events
                                     :parameters {:query events-query}}}]
   ["/v1/runs/:run-id/stream" {:get {:handler/id :run-events-stream
                                     :parameters {:query stream-query}}}]
   ["/v1/runs/:run-id/wait" {:get {:handler/id :run-wait
                                   :parameters {:query wait-query}}}]
   ["/v1/runs/:run-id/recover" {:post {:handler/id :run-recover}}]])
