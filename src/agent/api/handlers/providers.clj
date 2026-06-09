(ns agent.api.handlers.providers
  (:require
   [agent.api.responses :as responses]
   [agent.llm.registry :as registry]))

(defn- model-response [model]
  {:provider (some-> (:provider model) name)
   :api_kind (some-> (:api-kind model) name)
   :model_id (:model-id model)
   :display_name (:display-name model)
   :context_window (:context-window model)
   :max_output_tokens (:max-output-tokens model)
   :input_modalities (mapv name (:input-modalities model))
   :tool_support (:tool-support model)
   :reasoning_levels (mapv name (:reasoning-levels model))
   :cache_support (:cache-support model)
   :transport_support (mapv name (:transport-support model))
   :usage_cost_support (:usage-cost-support model)})

(defn- provider-response [provider]
  {:key (name (:key provider))
   :active (= (:key provider) (:active-provider provider))
   :api_kind (some-> (get-in provider [:metadata :api-kind]) name)
   :display_name (get-in provider [:metadata :display-name])
   :api_key_configured (:api-key-configured? provider)
   :options (:options provider)
   :models (mapv model-response (:models provider))})

(defn list-providers [system _request]
  (let [reg (:llm-registry system)
        active (:active-provider reg)]
    (responses/json-response
     200
     {:data (mapv #(provider-response (assoc % :active-provider active))
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
   {:data (mapv model-response
                (registry/configured-models (:llm-registry system)
                                            (keyword provider-key)))}))
