(ns agent.config.env
  "Environment-variable overrides for iris config maps."
  (:require
   [clojure.string :as str]))

(defn- parse-bool [value]
  (when-not (nil? value)
    (contains? #{"1" "true" "yes" "on"} (str/lower-case (str value)))))

(defn- parse-long* [value]
  (when (some? value)
    (Long/parseLong (str value))))

(defn- parse-double* [value]
  (when (some? value)
    (Double/parseDouble (str value))))

(defn- parse-csv [value]
  (when (some? value)
    (->> (str/split (str value) #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- parse-keyword-csv [value]
  (some->> (parse-csv value)
           (map keyword)
           vec))

(defn- parse-keyword* [value]
  (some-> value str/lower-case not-empty keyword))

(defn- configured-env-value [getenv names]
  (some (fn [name]
          (let [value (getenv name)]
            (when (some? value) value)))
        (if (sequential? names) names [names])))

(defn- assoc-path [path]
  (fn [cfg value]
    (assoc-in cfg path value)))

(defn- active-provider [cfg]
  (or (get-in cfg [:llm :active-provider]) :ollama))

(defn- assoc-active-provider-option [option-key]
  (fn [cfg value]
    (assoc-in cfg [:llm :providers (active-provider cfg) option-key] value)))

(def ^:private overrides
  [{:names "AGENT_LLM_PROVIDER" :parse parse-keyword* :apply (assoc-path [:llm :active-provider])}
   {:names "OPENROUTER_MODEL" :apply (assoc-path [:llm :providers :openrouter :model])}
   {:names "OLLAMA_MODEL" :apply (assoc-path [:llm :providers :ollama :model])}
   {:names "AGENT_LLM_MODEL" :apply (assoc-active-provider-option :model)}
   {:names "AGENT_LLM_TIMEOUT_MS" :parse parse-long* :apply (assoc-active-provider-option :timeout-ms)}
   {:names "AGENT_LLM_TEMPERATURE" :parse parse-double* :apply (assoc-active-provider-option :temperature)}
   {:names "AGENT_LLM_MAX_TOKENS" :parse parse-long* :apply (assoc-active-provider-option :max-tokens)}
   {:names "AGENT_LLM_STREAM" :parse parse-bool :apply (assoc-active-provider-option :stream?)}
   {:names "AGENT_LLM_PROMPT_CACHE" :parse parse-bool :apply (assoc-active-provider-option :prompt-cache?)}
   {:names "AGENT_LLM_STREAM_STRUCTURED_OUTPUT" :parse parse-bool :apply (assoc-active-provider-option :stream-structured-output?)}
   {:names "AGENT_LLM_STREAM_CONTENT" :parse parse-bool :apply (assoc-path [:llm :stream-content?])}
   {:names "AGENT_APP_NAME" :apply (assoc-active-provider-option :app-name)}
   {:names "OPENROUTER_SITE_URL" :apply (assoc-path [:llm :providers :openrouter :site-url])}
   {:names "OPENROUTER_APP_NAME" :apply (assoc-path [:llm :providers :openrouter :app-name])}
   {:names "OPENROUTER_BASE_URL" :apply (assoc-path [:llm :providers :openrouter :base-url])}
   {:names "OPENROUTER_API_KEY" :apply (assoc-path [:llm :providers :openrouter :api-key])}
   {:names "OLLAMA_BASE_URL" :apply (assoc-path [:llm :providers :ollama :base-url])}
   {:names "OLLAMA_KEEP_ALIVE" :apply (assoc-path [:llm :providers :ollama :keep-alive])}
   {:names "OLLAMA_EMBEDDING_MODEL" :apply (assoc-path [:llm :providers :ollama :embedding-model])}
   {:names "OPENAI_BASE_URL" :apply (assoc-path [:llm :providers :openai-compatible :base-url])}
   {:names "OPENAI_API_KEY" :apply (assoc-path [:llm :providers :openai-compatible :api-key])}
   {:names "AGENT_SQLITE_PATH" :apply (assoc-path [:storage :sqlite :path])}
   {:names "AGENT_SQLITE_MAXIMUM_POOL_SIZE" :parse parse-long* :apply (assoc-path [:storage :sqlite :maximum-pool-size])}
   {:names "AGENT_SQLITE_MINIMUM_IDLE" :parse parse-long* :apply (assoc-path [:storage :sqlite :minimum-idle])}
   {:names "AGENT_SQLITE_CONNECTION_TIMEOUT_MS" :parse parse-long* :apply (assoc-path [:storage :sqlite :connection-timeout-ms])}
   {:names "AGENT_SQLITE_DESTRUCTIVE_RESET_ON_DRIFT" :parse parse-bool :apply (assoc-path [:storage :sqlite :destructive-reset-on-drift?])}
   {:names "AGENT_CHAT_MAX_STEPS" :parse parse-long* :apply (assoc-path [:chat :max-steps])}
   {:names "AGENT_LOOP_MAX_ITERATIONS" :parse parse-long* :apply (assoc-path [:loop :max-iterations])}
   {:names "AGENT_LOOP_PLAN_FILE" :apply (assoc-path [:loop :plan-file])}
   {:names "AGENT_LOOP_SUMMARY_MAX_CHARS" :parse parse-long* :apply (assoc-path [:loop :summary-max-chars])}
   {:names "AGENT_LOOP_VALIDATION_MAX_CHARS" :parse parse-long* :apply (assoc-path [:loop :validation-max-chars])}
   {:names "AGENT_MEMORY_PROMPT_PATHS" :parse parse-csv :apply (assoc-path [:memory :prompt :paths])}
   {:names "AGENT_MEMORY_SEARCH_DEFAULT_LIMIT" :parse parse-long* :apply (assoc-path [:memory :search :default-limit])}
   {:names "AGENT_MEMORY_SEARCH_MAX_LIMIT" :parse parse-long* :apply (assoc-path [:memory :search :max-limit])}
   {:names "AGENT_MEMORY_SEARCH_MIN_SCORE" :parse parse-double* :apply (assoc-path [:memory :search :min-score])}
   {:names "AGENT_MEMORY_VAULT_PATHS" :parse parse-csv :apply (assoc-path [:memory :vault :paths])}
   {:names "AGENT_MEMORY_VAULT_WRITABLE" :parse parse-bool :apply (assoc-path [:memory :vault :writable?])}
   {:names "AGENT_FACT_EXTRACTOR_ENABLED" :parse parse-bool :apply (assoc-path [:memory :facts :extractor :enabled])}
   {:names "AGENT_FACT_EXTRACTOR_PROVIDER" :parse parse-keyword* :apply (assoc-path [:memory :facts :extractor :provider])}
   {:names "AGENT_FACT_EXTRACTOR_MODEL" :apply (assoc-path [:memory :facts :extractor :model])}
   {:names "AGENT_FACT_DEDUP_SIMILARITY_THRESHOLD" :parse parse-double* :apply (assoc-path [:memory :facts :dedup :similarity-threshold])}
   {:names "AGENT_MEMORY_GRAPH_ENABLED" :parse parse-bool :apply (assoc-path [:memory :graph :enabled])}
   {:names "AGENT_MEMORY_GRAPH_PATH" :apply (assoc-path [:memory :graph :datahike :path])}
   {:names "AGENT_TELEGRAM_ENABLED" :parse parse-bool :apply (assoc-path [:channel-adapters :telegram :enabled])}
   {:names "AGENT_TELEGRAM_BOT_TOKEN" :apply (assoc-path [:channel-adapters :telegram :bot-token])}
   {:names "AGENT_TELEGRAM_ALLOW_ALL" :parse parse-bool :apply (assoc-path [:channel-adapters :telegram :allowlist :allow-all?])}
   {:names "AGENT_TELEGRAM_ALLOWED_USER_IDS" :parse parse-csv :apply (assoc-path [:channel-adapters :telegram :allowlist :user-ids])}
   {:names "AGENT_TELEGRAM_ALLOWED_CHAT_IDS" :parse parse-csv :apply (assoc-path [:channel-adapters :telegram :allowlist :chat-ids])}
   {:names "AGENT_LOG_FILE" :apply (assoc-path [:logging :file :path])}
   {:names "AGENT_LOG_ENABLED" :parse parse-bool :apply (assoc-path [:logging :enabled])}
   {:names "AGENT_TELEMETRY_ENABLED" :parse parse-bool :apply (assoc-path [:telemetry :enabled])}
   {:names "AGENT_TELEMETRY_MAX_LATENCY_SAMPLES" :parse parse-long* :apply (assoc-path [:telemetry :max-latency-samples])}
   {:names "AGENT_OBSERVER_ENABLED" :parse parse-bool :apply (assoc-path [:observer :enabled])}
   {:names "AGENT_OBSERVER_BEST_EFFORT" :parse parse-bool :apply (assoc-path [:observer :best-effort?])}
   {:names "AGENT_OBSERVER_SINKS" :parse parse-keyword-csv :apply (assoc-path [:observer :sinks])}
   {:names "AGENT_TRACE_MODE" :parse parse-keyword* :apply (assoc-path [:trace :mode])}
   {:names "AGENT_TRACE_PATH" :apply (assoc-path [:trace :path])}
   {:names "AGENT_TRACE_ROLLING_MAX_ENTRIES" :parse parse-long* :apply (assoc-path [:trace :rolling-max-entries])}
   {:names ["AGENT_OTEL_ENABLED" "OTEL_ENABLED"] :parse parse-bool :apply (assoc-path [:logging :otel :enabled])}
   {:names ["AGENT_OTEL_URL" "OTEL_EXPORTER_OTLP_ENDPOINT"] :apply (assoc-path [:logging :otel :url])}
   {:names "AGENT_OTEL_SEND" :parse parse-keyword-csv :apply (assoc-path [:logging :otel :send])}
   {:names "AGENT_OTEL_PUBLISH_DELAY_MS" :parse parse-long* :apply (assoc-path [:logging :otel :publish-delay])}
   {:names "AGENT_OTEL_MAX_ITEMS" :parse parse-long* :apply (assoc-path [:logging :otel :max-items])}
   {:names "AGENT_TOOLS_YOLO" :parse parse-bool :apply (assoc-path [:tools :yolo?])}
   {:names "AGENT_TOOL_ALLOWLIST" :parse parse-keyword-csv :apply (assoc-path [:tools :policy :allowlist])}
   {:names "AGENT_TOOL_BLOCKLIST" :parse parse-keyword-csv :apply (assoc-path [:tools :policy :blocklist])}
   {:names "AGENT_TOOL_APPROVAL_TTL_SECONDS" :parse parse-long* :apply (assoc-path [:tools :approvals :ttl-seconds])}
   {:names "AGENT_API_TOOL_PERMISSIONS" :parse parse-keyword-csv :apply (assoc-path [:tools :permissions :api])}
   {:names "AGENT_UI_TOOL_PERMISSIONS" :parse parse-keyword-csv :apply (assoc-path [:tools :permissions :ui])}
   {:names "AGENT_AGENT_TOOL_PERMISSIONS" :parse parse-keyword-csv :apply (assoc-path [:tools :permissions :agent])}
   {:names "AGENT_CHAT_TOOL_PERMISSIONS" :parse parse-keyword-csv :apply (assoc-path [:tools :permissions :chat])}
   {:names "AGENT_API_HOST" :apply (assoc-path [:api :host])}
   {:names "AGENT_API_KEY" :apply (assoc-path [:api :key])}
   {:names "AGENT_API_PORT" :parse parse-long* :apply (assoc-path [:api :port])}
   {:names "AGENT_ORCHESTRATOR_ENABLED" :parse parse-bool :apply (assoc-path [:orchestrator :enabled])}
   {:names "AGENT_RUNNER_DEFAULT_SUBSTRATE" :parse parse-keyword* :apply (assoc-path [:runners :default-substrate])}
   {:names "AGENT_NREPL_ENABLED" :parse parse-bool :apply (assoc-path [:nrepl :enabled])}
   {:names "AGENT_NREPL_BIND" :apply (assoc-path [:nrepl :bind])}
   {:names "AGENT_NREPL_PORT" :parse parse-long* :apply (assoc-path [:nrepl :port])}
   {:names "AGENT_NREPL_PORT_FILE" :apply (assoc-path [:nrepl :port-file])}])

(defn apply-env-config
  [cfg getenv]
  (reduce
   (fn [acc {:keys [names parse] apply-fn :apply}]
     (if-let [raw (configured-env-value getenv names)]
       (let [value ((or parse identity) raw)]
         (if (some? value)
           (apply-fn acc value)
           acc))
       acc))
   cfg
   overrides))
