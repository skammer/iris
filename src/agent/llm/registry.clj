(ns agent.llm.registry
  "Provider/model capability registry layered over configured providers."
  (:require
   [agent.config :as config]
   [agent.llm.core :as llm-core]
   [clojure.string :as str]))

(def model-fields
  [:provider :api-kind :model-id :display-name :context-window
   :max-output-tokens :input-modalities :tool-support :reasoning-levels
   :cache-support :transport-support :usage-cost-support])

(def default-provider-metadata
  {:ollama
   {:api-kind :ollama
    :display-name "Ollama"
    :context-window nil
    :max-output-tokens nil
    :input-modalities #{:text :image}
    :tool-support {:native? true}
    :reasoning-levels #{}
    :cache-support {:prompt-cache? false}
    :transport-support #{:http :streaming}
    :usage-cost-support {:usage? true :cost? false}}

   :openrouter
   {:api-kind :openrouter
    :display-name "OpenRouter"
    :context-window nil
    :max-output-tokens nil
    :input-modalities #{:text :image :audio :video :file}
    :tool-support {:native? true}
    :reasoning-levels #{:off :low :medium :high}
    :cache-support {:prompt-cache? true :retention #{:ephemeral :in-memory}}
    :transport-support #{:http :sse}
    :usage-cost-support {:usage? true :cost? true}}

   :openai-compatible
   {:api-kind :openai-compatible
    :display-name "OpenAI compatible"
    :context-window nil
    :max-output-tokens nil
    :input-modalities #{:text :image :audio :video :file}
    :tool-support {:native? true}
    :reasoning-levels #{:off :low :medium :high :xhigh}
    :cache-support {:prompt-cache? true :retention #{:ephemeral}}
    :transport-support #{:http :sse}
    :usage-cost-support {:usage? true :cost? false}}

   :deepseek
   {:api-kind :deepseek
    :display-name "DeepSeek"
    :context-window nil
    :max-output-tokens nil
    :input-modalities #{:text}
    :tool-support {:native? true}
    :reasoning-levels #{:off}
    :cache-support {:prompt-cache? true :retention #{:in-memory}}
    :transport-support #{:http :sse}
    :usage-cost-support {:usage? true :cost? false}}})

(defn- normalize-provider-key [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- normalize-reasoning [value]
  (cond
    (nil? value) nil
    (keyword? value) value
    (string? value) (keyword (str/lower-case value))
    (map? value) (update value :level normalize-reasoning)
    :else value))

(defn- maybe-long [value]
  (cond
    (nil? value) nil
    (integer? value) (long value)
    (number? value) (long value)
    (string? value) (try
                      (Long/parseLong value)
                      (catch NumberFormatException e
                        (throw (ex-info "Invalid integer LLM option"
                                        {:type :invalid-llm-option
                                         :value value}
                                        e))))
    :else value))

(defn- maybe-double [value]
  (cond
    (nil? value) nil
    (number? value) (double value)
    (string? value) (try
                      (Double/parseDouble value)
                      (catch NumberFormatException e
                        (throw (ex-info "Invalid numeric LLM option"
                                        {:type :invalid-llm-option
                                         :value value}
                                        e))))
    :else value))

(defn- compact-map [m]
  (into {} (remove (comp nil? val) m)))

(defn- provider-kind [provider-key provider-cfg]
  (normalize-provider-key (or (:type provider-cfg) provider-key)))

(defn- provider-defaults [provider-key provider-cfg]
  (let [kind (provider-kind provider-key provider-cfg)]
    (or (default-provider-metadata provider-key)
        (default-provider-metadata kind)
        (throw (ex-info "Unknown LLM provider type"
                        {:type :unknown-provider-type
                         :provider provider-key
                         :provider-type kind})))))

(defn- model-overrides [provider-cfg model-id]
  (let [models (:models provider-cfg)]
    (cond
      (map? models) (get models model-id)
      (sequential? models) (some #(when (= model-id (:model-id %)) %) models)
      :else nil)))

(defn model-metadata
  [provider-key provider-cfg model-id]
  (let [provider-key* (normalize-provider-key provider-key)
        defaults (provider-defaults provider-key* provider-cfg)
        model-id* (or model-id (:model provider-cfg))
        override (model-overrides provider-cfg model-id*)]
    (select-keys
     (merge defaults
            {:provider provider-key*
             :api-kind (:api-kind defaults)
             :model-id model-id*
             :display-name (or (:display-name override)
                               (:display-name provider-cfg)
                               model-id*
                               (:display-name defaults))
             :context-window (or (:context-window override)
                                 (:context-window provider-cfg)
                                 (:context-window defaults))
             :max-output-tokens (or (:max-output-tokens override)
                                    (:max-output-tokens provider-cfg)
                                    (:max-tokens provider-cfg)
                                    (:max-output-tokens defaults))}
            override)
     model-fields)))

(defn normalize-options
  ([opts] (normalize-options nil nil opts))
  ([_registry _provider-key opts]
   (compact-map
    {:temperature (maybe-double (:temperature opts))
     :api (:api opts)
     :max-tokens (maybe-long (or (:max-tokens opts) (:max_tokens opts)))
     :reasoning (normalize-reasoning (:reasoning opts))
     :cache-retention (or (:cache-retention opts)
                          (:cache_retention opts)
                          (:prompt-cache-retention opts)
                          (:prompt_cache_retention opts))
     :session-id (or (:session-id opts) (:session_id opts))
     :timeout-ms (maybe-long (or (:timeout-ms opts) (:timeout_ms opts)))
     :max-retries (maybe-long (or (:max-retries opts) (:max_retries opts)))})))

(defn create-registry
  ([llm-cfg] (create-registry llm-cfg {}))
  ([llm-cfg {:keys [api-key-resolver]}]
   (let [llm-cfg* (config/llm-config {:llm llm-cfg})
         active-provider (config/active-provider-key llm-cfg*)
         providers (into {}
                         (map (fn [[provider-key provider-cfg]]
                                (let [provider-key* (normalize-provider-key provider-key)
                                      model-id (:model provider-cfg)]
                                  [provider-key*
                                   {:key provider-key*
                                    :config provider-cfg
                                    :metadata (provider-defaults provider-key* provider-cfg)
                                    :models (cond-> []
                                              model-id (conj (model-metadata provider-key*
                                                                             provider-cfg
                                                                             model-id)))}])))
                         (:providers llm-cfg*))]
     {:active-provider active-provider
      :providers providers
      :api-key-resolver api-key-resolver})))

(defn provider
  [registry provider-key]
  (or (get-in registry [:providers (normalize-provider-key provider-key)])
      (throw (ex-info (str "Unknown LLM provider: " provider-key)
                      {:type :unknown-provider
                       :provider provider-key}))))

(defn active-provider
  [registry]
  (provider registry (:active-provider registry)))

(defn model-capabilities
  ([registry model-id]
   (model-capabilities registry (:active-provider registry) model-id))
  ([registry provider-key model-id]
   (let [{:keys [config]} (provider registry provider-key)]
     (model-metadata provider-key config model-id))))

(defn configured-models
  ([registry] (mapcat :models (vals (:providers registry))))
  ([registry provider-key] (:models (provider registry provider-key))))

(defn resolve-api-key
  [registry provider-key]
  (let [{:keys [config]} (provider registry provider-key)]
    (or (when-let [resolver (:api-key-resolver registry)]
          (resolver provider-key config))
        (:api-key config))))

(defn enrich-provider
  [registry provider-key]
  (let [{:keys [key config] :as provider*} (provider registry provider-key)]
    (assoc provider*
           :api-key-configured? (boolean (resolve-api-key registry key))
           :options (normalize-options registry key config))))

(defn list-providers
  [registry]
  (mapv #(enrich-provider registry %)
        (sort (keys (:providers registry)))))

(defn provider-health
  [registry provider-key llm-provider]
  (let [base {:provider (normalize-provider-key provider-key)
              :configured? (contains? (:providers registry) (normalize-provider-key provider-key))}]
    (if (and llm-provider (= (normalize-provider-key provider-key) (:active-provider registry)))
      (assoc base :active? true :health (llm-core/health-check llm-provider))
      (assoc base :active? false :health {:healthy nil :details {:reason "not active"}}))))
