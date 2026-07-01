(ns agent.api.routes.a2a)

(def routes
  [["/.well-known/agent-card.json" {:get {:handler/id :a2a-agent-card}}]
   ["/message:send" {:post {:handler/id :a2a-send-message}}]
   ["/tasks" {:get {:handler/id :a2a-list-tasks}}]
   ["/tasks/:task-id" {:get {:handler/id :a2a-get-task}
                       :post {:handler/id :a2a-task-operation}}]])
