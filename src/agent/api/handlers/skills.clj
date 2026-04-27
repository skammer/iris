(ns agent.api.handlers.skills
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.skills :as skills]))

(defn list-skills [system _request]
  (responses/json-response 200
                           {:data (mapv ser/skill->response
                                        (skills/list-skills (:skills-registry system)))}))
