(ns agent.llm.service
  "LLM provider factory and small runtime helpers."
  (:require
   [agent.config :as config]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]))

(defn create-llm-provider
  [cfg]
  (let [{:keys [provider type model base-url stream-structured-output?
                embedding-model keep-alive]
         :as provider-cfg}
        (config/active-provider-config cfg)]
    (case type
      :ollama
      (ollama/create-ollama-provider
       {:base-url base-url
        :default-model model
        :embedding-model embedding-model
        :keep-alive keep-alive
        :stream-structured-output? stream-structured-output?})

      :openrouter
      (openai-compatible/create-openrouter-provider
       provider-cfg)

      :openai-compatible
      (openai-compatible/create-openai-compatible-provider
       (assoc provider-cfg :default-model model))

      (throw (ex-info (str "Unsupported provider type: " type)
                      {:provider provider
                       :type type})))))

(defn create-fact-llm-provider
  [cfg]
  (let [extractor (get-in cfg [:memory :facts :extractor])
        provider (:provider extractor)
        model (:model extractor)]
    (when (or provider model)
      (create-llm-provider
       (cond-> (:llm cfg)
         provider (assoc :active-provider provider)
         model (assoc-in [:providers (or provider
                                         (config/active-provider-key (:llm cfg)))
                          :model]
                         model))))))
