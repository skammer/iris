(ns agent.api.routes.providers)

(def routes
  [["/v1/providers" {:get {:handler/id :list-providers}}]
   ["/v1/providers/:provider-key/health" {:get {:handler/id :provider-health}}]
   ["/v1/providers/:provider-key/models" {:get {:handler/id :provider-models}}]])
