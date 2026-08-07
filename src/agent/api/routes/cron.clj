(ns agent.api.routes.cron)

(def ^:private job-body
  [:map
   [:name {:optional true} :string]
   [:prompt {:optional true} :string]
   [:schedule {:optional true} :map]
   [:timezone {:optional true} :string]
   [:notification {:optional true} :map]
   [:provider {:optional true} [:maybe :string]]
   [:model {:optional true} [:maybe :string]]
   [:tool_profile {:optional true} [:maybe :string]]
   [:max_occurrences {:optional true} [:maybe :int]]
   [:revision {:optional true} :int]])

(def routes
  [["/v1/cron/jobs" {:get {:handler/id :list-cron-jobs}
                           :post {:handler/id :create-cron-job :parameters {:body job-body}}}]
   ["/v1/cron/jobs/:id" {:get {:handler/id :get-cron-job}
                               :patch {:handler/id :update-cron-job :parameters {:body job-body}}
                               :delete {:handler/id :delete-cron-job :parameters {:body [:map [:revision :int]]}}}]
   ["/v1/cron/jobs/:id/pause" {:post {:handler/id :pause-cron-job :parameters {:body [:map [:revision :int]]}}}]
   ["/v1/cron/jobs/:id/resume" {:post {:handler/id :resume-cron-job :parameters {:body [:map [:revision :int]]}}}]
   ["/v1/cron/jobs/:id/run" {:post {:handler/id :run-cron-job}}]
   ["/v1/cron/jobs/:id/runs" {:get {:handler/id :list-cron-runs}}]
   ["/v1/cron/runs/:id" {:get {:handler/id :get-cron-run}}]
   ["/v1/cron/status" {:get {:handler/id :cron-status}}]
   ["/v1/cron/preview" {:post {:handler/id :preview-cron-job :parameters {:body job-body}}}]])
