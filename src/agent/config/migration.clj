(ns agent.config.migration)
  "Legacy provider migration and recursive configuration merging."

(defn ^:no-doc deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left) (map? right))
             (deep-merge left right)
             right))
         maps))

(def ^:no-doc provider-keys
  #{:ollama :openrouter :openai-compatible :deepseek})

(def ^:private provider-default-types
  {:ollama :ollama
   :openrouter :openrouter
   :openai-compatible :openai-compatible
   :deepseek :deepseek})

(def ^:no-doc legacy-llm-provider-option-keys
  [:model :api :temperature :max-tokens :stream? :prompt-cache?
   :stream-structured-output? :structured-output-format :timeout-ms :site-url :app-name])

(defn- normalize-provider-config
  [provider-key provider-cfg]
  (assoc provider-cfg :type (or (:type provider-cfg)
                                (provider-default-types provider-key)
                                provider-key)))

(defn migrate-legacy-llm-config
  "Move pre-provider LLM keys into the canonical provider map."
  [llm-cfg]
  (let [active-provider (or (:provider llm-cfg)
                            (:active-provider llm-cfg)
                            (:default-provider llm-cfg)
                            (ffirst (:providers llm-cfg))
                            :ollama)
        legacy-provider-configs (into {}
                                      (keep (fn [provider-key]
                                              (when-let [provider-cfg (get llm-cfg provider-key)]
                                                [provider-key provider-cfg])))
                                      provider-keys)
        provider-configs (deep-merge legacy-provider-configs
                                     (:providers llm-cfg))
        legacy-provider-options (select-keys llm-cfg legacy-llm-provider-option-keys)
        provider-configs* (if (seq legacy-provider-options)
                            (update provider-configs active-provider deep-merge legacy-provider-options)
                            provider-configs)
        provider-configs** (into {}
                                 (map (fn [[provider-key provider-cfg]]
                                        [provider-key (normalize-provider-config provider-key provider-cfg)]))
                                 provider-configs*)]
    (assoc (apply dissoc llm-cfg :provider :default-provider
                  (concat legacy-llm-provider-option-keys provider-keys))
           :active-provider active-provider
           :providers provider-configs**)))

(defn migrate-legacy-config
  "Return cfg with legacy LLM shape converted to canonical config shape."
  [cfg]
  (cond-> cfg
    (contains? cfg :llm) (update :llm migrate-legacy-llm-config)))
