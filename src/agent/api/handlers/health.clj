(ns agent.api.handlers.health
  (:require
   [agent.api.responses :as responses]
   [agent.health :as health]))

(defn handle [system _request]
  (health/mark-ok! (:health-registry system) :api)
  (responses/json-response
   200
   (assoc ((requiring-resolve 'agent.system/health-check) system)
          :ok true)))
