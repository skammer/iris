(ns agent.api.routes
  (:require
   [agent.api.routes.chat :as chat]
   [agent.api.routes.events :as events]
   [agent.api.routes.memory :as memory]
   [agent.api.routes.providers :as providers]
   [agent.api.routes.root :as root]
   [agent.api.routes.sessions :as sessions]
   [agent.api.routes.tools :as tools]
   [agent.api.routes.ui :as ui]))

(def routes
  (vec (mapcat identity
               [root/routes
                ui/routes
                sessions/routes
                chat/routes
                providers/routes
                tools/routes
                events/routes
                memory/routes])))
