(ns agent.api.handlers.telemetry
  (:require
   [agent.api.responses :as responses]
   [agent.telemetry :as telemetry]))

(defn snapshot [system _request]
  (responses/json-response 200 {:data (telemetry/snapshot (:telemetry system))}))
