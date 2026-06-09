(ns agent.api.handlers.providers
  (:require
   [agent.api.responses :as responses]
   [agent.api.serializers :as serializers]
   [agent.llm.registry :as registry]))

(defn list-providers [system _request]
  (let [reg (:llm-registry system)
        active (:active-provider reg)]
    (responses/json-response
     200
     {:data (mapv #(serializers/provider->response (assoc % :active-provider active))
                  (registry/list-providers reg))})))

(defn provider-health [system _request provider-key]
  (registry/provider (:llm-registry system) (keyword provider-key))
  (responses/json-response
   200
   {:data (registry/provider-health (:llm-registry system)
                                    (keyword provider-key)
                                    (:llm-provider system))}))

(defn provider-models [system _request provider-key]
  (responses/json-response
   200
   {:data (mapv serializers/model->response
                (registry/configured-models (:llm-registry system)
                                            (keyword provider-key)))}))
