(ns agent.llm.service
  "LLM provider factory. Chooses the active configured provider, constructs the
   concrete adapter, and builds the note-extraction provider used by memory."
  (:require
   [agent.config :as config]
   [agent.llm.fallback :as fallback]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]))

(defn- create-single-provider
  [cfg]
  (let [{:keys [provider type model]
         :as provider-cfg}
        (config/active-provider-config cfg)]
    (case type
      :ollama
      (ollama/create-ollama-provider
       (assoc provider-cfg :default-model model))

      :openrouter
      (openai-compatible/create-openrouter-provider
       provider-cfg)

      :openai-compatible
      (openai-compatible/create-openai-compatible-provider
       (assoc provider-cfg :default-model model))

      :deepseek
      (openai-compatible/create-deepseek-provider
       (assoc provider-cfg :default-model model))

      (throw (ex-info (str "Unsupported provider type: " type)
                      {:provider provider
                       :type type})))))

(defn create-llm-provider
  "Build primary plus the global ordered :fallbacks vector.
   Each fallback explicitly names a configured provider and model."
  [cfg]
  (let [primary (config/active-provider-key cfg)
        chain (:fallbacks cfg [])]
    (when-not (and (vector? chain)
                   (every? #(and (map? %)
                                 (keyword? (:provider %))
                                 (contains? (:providers cfg) (:provider %))
                                 (string? (:model %))
                                 (seq (:model %))) chain))
      (throw (ex-info "Invalid LLM fallback chain"
                      {:type :invalid-fallback-config})))
    (let [selections (distinct (cons {:provider primary
                                      :model (config/active-model cfg)} chain))
          candidates (mapv (fn [{:keys [provider model] :as selection}]
                             (assoc selection :client
                                    (create-single-provider
                                     (-> cfg
                                         (assoc :active-provider provider)
                                         (assoc-in [:providers provider :model] model)))))
                           selections)]
      (if (seq chain)
        (fallback/->FallbackProvider candidates)
        (:client (first candidates))))))

(defn resolve-provider-selection
  [llm-cfg {:keys [provider model]}]
  (let [provider* (or provider (config/active-provider-key llm-cfg))
        provider-cfg (get-in llm-cfg [:providers provider*])]
    {:provider provider*
     :model (or model (:model provider-cfg))}))

(defn llm-config-with-provider-override
  [llm-cfg {:keys [provider model timeout-ms]}]
  (let [provider* (or provider (config/active-provider-key llm-cfg))]
    (cond-> llm-cfg
      provider (assoc :active-provider provider)
      model (assoc-in [:providers provider* :model] model)
      timeout-ms (assoc-in [:providers provider* :timeout-ms] timeout-ms))))

(defn create-llm-provider-with-override
  [llm-cfg override]
  (create-llm-provider
   (llm-config-with-provider-override llm-cfg override)))

(defn create-note-llm-provider
  [cfg]
  (let [extractor (get-in cfg [:memory :notes :extractor])
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
