(ns agent.api.routes.events)

(def ^:private events-list-query
  [:map
   [:limit {:optional true} :int]])

(def routes
  [["/v1/events" {:get {:handler/id :list-events
                        :parameters {:query events-list-query}}}]
   ["/v1/events/stream" {:get {:handler/id :events-stream}}]])
