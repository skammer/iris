(ns agent.config.validation
  "Pure validation of the effective application configuration."
  (:require
   [clojure.string :as str]
   [agent.config.migration :as migration]))

(def ^:private provider-required-keys
  {:ollama [:base-url :model]
   :openrouter [:base-url :model :api-key]
   :openai-compatible [:base-url :model :api-key]
   :deepseek [:model :api-key]})

(def ^:private openai-compatible-provider-types
  #{:openrouter :openai-compatible :deepseek})

(def ^:private openai-compatible-apis
  #{:chat :chat-completion :chat-completions :completions
    :response :responses})

(defn- present-config-value? [value]
  (if (string? value)
    (not (str/blank? value))
    (some? value)))

(defn- missing-provider-keys [provider provider-cfg]
  (let [type (:type provider-cfg)]
    (cond
      (nil? provider-cfg)
      [{:path [:llm :providers provider]
        :message (str "active LLM provider " provider " is not configured")}]

      (nil? type)
      [{:path [:llm :providers provider :type]
        :message (str "active LLM provider " provider " missing required key :type")}]

      (nil? (provider-required-keys type))
      [{:path [:llm :providers provider :type]
        :message (str "unsupported active LLM provider type " type)}]

      :else
      (cond-> (vec
               (for [k (provider-required-keys type)
                     :when (not (present-config-value? (get provider-cfg k)))]
                 {:path [:llm :providers provider k]
                  :message (str "active LLM provider " provider " missing required key " k)}))
        (and (openai-compatible-provider-types type)
             (some? (:api provider-cfg))
             (not (openai-compatible-apis (:api provider-cfg))))
        (conj {:path [:llm :providers provider :api]
               :message (str "active LLM provider " provider
                             " has unsupported :api " (:api provider-cfg)
                             "; expected :chat-completions or :responses")})))))

(defn- positive-number-error [cfg path]
  (let [value (get-in cfg path)]
    (when (and (some? value) (not (pos? (long value))))
      {:path path
       :message (str (str/join "/" (map name path)) " must be positive")})))

(defn- telegram-session-reset-config-errors [cfg]
  (let [{:keys [mode idle-minutes at-hour]}
        (get-in cfg [:channel-adapters :telegram :session-reset])
        mode* (cond
                (keyword? mode) mode
                (string? mode) (keyword (str/lower-case mode))
                :else mode)]
    (vec
     (concat
      (when-not (contains? #{:none :idle :daily :both} mode*)
        [{:path [:channel-adapters :telegram :session-reset :mode]
          :message "Telegram session reset mode must be :none, :idle, :daily, or :both"}])
      (when (and (#{:idle :both} mode*)
                 (or (not (number? idle-minutes))
                     (not (pos? (long idle-minutes)))))
        [{:path [:channel-adapters :telegram :session-reset :idle-minutes]
          :message "Telegram session reset idle-minutes must be positive"}])
      (when (and (#{:daily :both} mode*)
                 (or (not (integer? at-hour))
                     (not (<= 0 (long at-hour) 23))))
        [{:path [:channel-adapters :telegram :session-reset :at-hour]
          :message "Telegram session reset at-hour must be between 0 and 23"}])))))

(def ^:private legacy-llm-config-keys
  (set (concat [:provider :default-provider]
               migration/legacy-llm-provider-option-keys
               migration/provider-keys)))

(defn- legacy-llm-config-errors [llm-cfg]
  (vec
   (for [k legacy-llm-config-keys
         :when (contains? llm-cfg k)]
     {:path [:llm k]
      :message (str "legacy LLM config key " k
                    " is no longer loaded; run config migrate and use :llm/:providers")})))

(def ^:private cron-action-values
  {:http #{:get :head :post :put :patch :delete}
   :homeassistant #{:get_state :get_states :list_states :search_states :list_services :call_service}})

(defn- cron-config-errors [cfg]
  (let [{:keys [provider model timezone tool-profile]} (:cron cfg)
        provider-cfg (get-in cfg [:llm :providers provider])
        model-ids (set (concat [(:model provider-cfg)]
                               (when (map? (:models provider-cfg)) (keys (:models provider-cfg)))
                               (when (sequential? (:models provider-cfg)) (map :model-id (:models provider-cfg)))))
        profiles (get-in cfg [:tools :profiles])]
    (vec
     (concat
      (when (not= (boolean provider) (boolean model))
        [{:path [:cron :provider] :message "cron provider and model must be configured together"}])
      (when (and provider (nil? provider-cfg))
        [{:path [:cron :provider] :message (str "unknown cron provider " provider)}])
      (when (and model provider-cfg (not (contains? model-ids model)))
        [{:path [:cron :model] :message (str "cron model is not configured for provider " provider)}])
      (when-not (contains? profiles tool-profile)
        [{:path [:cron :tool-profile] :message (str "unknown cron tool profile " tool-profile)}])
      (try
        (java.time.ZoneId/of timezone)
        []
        (catch Exception _
          [{:path [:cron :timezone] :message "cron timezone must be a valid IANA timezone"}]))
      (for [[profile-key profile] profiles
            [tool actions] (:allowed-actions profile)
            :let [known (get cron-action-values tool)]
            :when (or (nil? known) (not-every? known (map keyword actions)))]
        {:path [:tools :profiles profile-key :allowed-actions tool]
         :message (str "unsupported allowed action for " tool)})))))

(defn ^:no-doc config-validation-errors [cfg]
  (let [llm-cfg (:llm cfg)
        provider (:active-provider llm-cfg)
        provider-cfg (get-in llm-cfg [:providers provider])]
    (cond-> []
      (nil? (:llm cfg))
      (conj {:path [:llm]
             :message "missing required key :llm"})

      (nil? provider)
      (conj {:path [:llm :active-provider]
             :message "missing required key :llm/:active-provider"})

      provider
      (into (missing-provider-keys provider provider-cfg))

      llm-cfg
      (into (legacy-llm-config-errors llm-cfg))

      true
      (into (cron-config-errors cfg))

      true
      (into (telegram-session-reset-config-errors cfg))

      true
      (into (keep #(positive-number-error cfg %)
                  [[:loop :max-iterations]
                   [:loop :summary-max-chars]
                   [:loop :validation-max-chars]
                   [:magi :file-review :max-tool-calls]
                   [:magi :file-review :max-tool-rounds]
                   [:magi :file-review :timeout-ms]
                   [:magi :file-review :max-evidence-chars]
                   [:magi :file-review :max-tool-result-chars]
                   [:tools :fs :max-search-files]
                   [:tools :fs :max-search-file-bytes]
                   [:tools :fs :max-search-results]
                   [:tools :fs :max-search-line-chars]
                   [:tools :fs :search-timeout-ms]
                   [:cron :poll-interval-seconds]
                   [:cron :max-concurrency]
                   [:cron :run-timeout-seconds]
                   [:cron :misfire-grace-seconds]
                   [:cron :output-max-chars]
                   [:channel-adapters :telegram :ask-timeout-seconds]])))))
