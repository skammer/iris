(ns agent.api.routes.root)

(def routes
  [["/" {:get {:handler/id :ui-index}}]
   ["/ui" {:get {:handler/id :ui-catalog}}]
   ["/overview" {:get {:handler/id :ui-index}}]
   ["/chat" {:get {:handler/id :ui-index}}]
   ["/chat/:session-id" {:get {:handler/id :ui-index}}]
   ["/tools" {:get {:handler/id :ui-index}}]
   ["/memory" {:get {:handler/id :ui-index}}]
   ["/magi" {:get {:handler/id :ui-index}}]
   ["/logs" {:get {:handler/id :ui-index}}]
   ["/health" {:get {:handler/id :health}}]
   ["/public/*" {:get {:handler/id :public-file}}]])
