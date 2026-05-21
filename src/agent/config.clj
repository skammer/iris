(ns agent.config
  "Configuration loading for the rewritten runtime."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-config
  {:llm {:active-provider :ollama
         :stream-content? true
         :providers {:ollama {:type :ollama
                              :base-url "http://localhost:11434"
                              :model "llama3.2:3b"
                              :temperature 0.2
                              :max-tokens 1024
                              :stream? false
                              :prompt-cache? true
                              :stream-structured-output? true
                              :timeout-ms 60000
                              :app-name "iris"
                              :keep-alive "5m"
                              :embedding-model "nomic-embed-text"}
                     :openrouter {:type :openrouter
                                  :base-url "https://openrouter.ai/api/v1"
                                  :model "openai/gpt-4o-mini"
                                  :temperature 0.2
                                  :max-tokens 1024
                                  :stream? false
                                  :prompt-cache? true
                                  :stream-structured-output? true
                                  :timeout-ms 60000
                                  :site-url nil
                                  :app-name "iris"
                                  :api-key nil}
                     :openai-compatible {:type :openai-compatible
                                         :base-url "https://api.openai.com/v1"
                                         :model "gpt-4o-mini"
                                         :temperature 0.2
                                         :max-tokens 1024
                                         :stream? false
                                         :prompt-cache? true
                                         :stream-structured-output? true
                                         :timeout-ms 60000
                                         :site-url nil
                                         :app-name "iris"
                                         :api-key nil}}}
   :storage {:sqlite {:path "data/agent.db"
                      :journal-mode "WAL"}}
   :chat {:max-steps 6
          :compaction {:max-context-tokens 8192
                       :reserve-output-tokens 1024
                       :keep-recent-tokens 2048
                       :max-summary-input-tokens 8192
                       :warning-threshold 0.8
                       :destructive-threshold 1.0
                       :summarizer-input-cap 8192
                       :summary-max-tokens 512
                       :tool-result-truncate-chars 2000
                       :budgets {:system 1200
                                 :memory 1200
                                 :recent-conversation 4096
                                 :tool-schema 1600
                                 :pending-tool-result 800
                                 :referenced-file 2400
                                 :output-reserve 1024}}}
   :tools {:http {:enabled true
                  :timeout-ms 30000
                  :max-timeout-ms 30000
                  :max-response-bytes 1048576
                  :allow-private? false
                  :max-redirects 3
                  :default-headers {"User-Agent" "iris/0.1"}}
           :yolo? false
           :permissions {:api [:filesystem-read :filesystem-write :http-request :system-reload]
                         :ui [:filesystem-read :filesystem-write :http-request :system-reload]
                         :agent [:http-request :memory-read :memory-write]
                         :chat [:filesystem-read :http-request :memory-read :memory-write :system-reload]}
           :policy {:allowlist []
                    :blocklist []
                    :tool-scopes {}}
           :approvals {:ttl-seconds 900}
           :fs {:enabled true
                :roots ["."]
                :max-read-bytes 1048576
                :max-write-bytes 1048576}
           :shell {:enabled true
                   :roots ["."]
                   :working-dir "."
                   :timeout-ms 30000
                   :max-timeout-ms 30000
                   :deny-by-default? true
                   :allowed-commands ["printf" "pwd" "ls" "echo" "cat" "rg" "git" "df"]
                   :blocked-commands []
                   :max-output-bytes 65536}
           :display {:web {:show-tool-calls? true
                           :collapsed? true
                           :preview-chars 800
                           :args-preview-chars 800
                           :max-result-height-px 320
                           :per-tool {}}
                     :telegram {:show-tool-calls? true
                                :preview-chars 1600
                                :args-preview-chars 1200
                                :per-tool {}}
                     :api {:show-tool-calls? true
                           :full? true
                           :per-tool {}}}}
   :skills {:dirs ["skills"]}
   :memory {:prompt {:paths ["MEMORY.md"]}
            :search {:default-limit 10
                     :max-limit 10
                     :min-score 0.3}
            :vault {:paths ["memory"]
                    :writable? true}
            :facts {:extractor {:enabled true
                                :provider nil
                                :model nil}
                    :default-scope :session
                    :dedup {:similarity-threshold nil}}
            :graph {:enabled false
                    :backend :datahike
                    :datahike {:path "data/memory-graph"
                               :scope "iris"
                               :keep-history? true}}}
   :channel-adapters {:telegram {:enabled false
                                  :bot-token nil
                                  :poll-timeout-seconds 30
                                  :poll-limit 100
                                  :allowlist {:allow-all? false
                                              :user-ids []
                                              :chat-ids []}}
                      :discord {:enabled false}
                      :slack {:enabled false}}
   :runners {:docker {:image "clojure:temurin-21-alpine"
                      :image-mode :mounted-dev
                      :pull-policy :missing
                      :container-working-dir "/workspace"
                      :container-data-dir "/tmp/iris"
                      :container-home-dir "/tmp/iris/home"
                      :user "65532:65532"
                      :host-working-dir "."
                      :share-network? true}
             :podman {:image "clojure:temurin-21-alpine"
                      :image-mode :mounted-dev
                      :pull-policy :missing
                      :container-working-dir "/workspace"
                      :container-data-dir "/tmp/iris"
                      :container-home-dir "/tmp/iris/home"
                      :user "65532:65532"
                      :host-working-dir "."
                      :share-network? true}}
   :orchestrator {:enabled true}
   :nrepl {:enabled true
           :bind "127.0.0.1"
           :port 0
           :port-file ".nrepl-port"}
   :telemetry {:enabled true
               :max-latency-samples 1000}
   :observer {:enabled true
              :best-effort? true
              :sinks [:telemetry :logging]}
   :trace {:mode :none
           :path "runtime-trace.jsonl"
           :rolling-max-entries 1000}
   :logging {:enabled false
             :file {:path "logs/iris.log"
                    :max-bytes 10485760
                    :max-files 5}
             :otel {:enabled false
                    :url "http://localhost:4318/"
                    :send [:traces :logs]
                    :max-items 5000
                    :publish-delay 5000
                    :http-opts {:conn-timeout 2000
                                :socket-timeout 2000}}}
   :api {:host "127.0.0.1"
         :key nil
         :port 8080}})

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

(def ^:dynamic *env* #(System/getenv %))
(def ^:dynamic *user-home* #(System/getProperty "user.home"))
(def ^:dynamic *cwd* #(System/getProperty "user.dir"))

(def config-file-name "config.edn")
(def markdown-file-names
  ["SOUL.md" "AGENTS.md" "USER.md" "TOOLS.md" "BOOT.md" "HEARTBEAT.md"])
(def memory-file-name "MEMORY.md")
(def context-file-names (into [config-file-name] markdown-file-names))
(def template-file-names (conj context-file-names memory-file-name))
(def app-config-keys
  [:llm :storage :tools :skills :memory :channel-adapters :runners
   :orchestrator :telemetry :observer :trace :logging :api :chat])

(def default-config-edn
  {:iris/config-version 1
   :iris/context-files markdown-file-names})

(def default-markdown-content
  {"SOUL.md" "# SOUL\n\n"
   "AGENTS.md" "# AGENTS\n\n"
   "USER.md" "# USER\n\n"
   "TOOLS.md" "# TOOLS\n\n"
   "BOOT.md" "# BOOT\n\n"
   "HEARTBEAT.md" "# HEARTBEAT\n\n"})

(defn- getenv [name]
  (*env* name))

(defn- nonblank [value]
  (when (some? value)
    (let [value* (str/trim (str value))]
      (when-not (str/blank? value*) value*))))

(defn global-config-dir
  []
  (io/file
   (or (nonblank (getenv "IRIS_CONFIG_DIR"))
       (some-> (nonblank (getenv "XDG_CONFIG_HOME"))
               (io/file "iris")
               str)
       (str (io/file (*user-home*) ".config" "iris")))))

(defn local-config-dir
  []
  (io/file (*cwd*) ".iris"))

(defn- expand-home-path [path]
  (when (some? path)
    (let [path* (str path)]
      (cond
        (= "~" path*) (*user-home*)
        (str/starts-with? path* (str "~" java.io.File/separator))
        (str (io/file (*user-home*) (subs path* 2)))
        (str/starts-with? path* "~/")
        (str (io/file (*user-home*) (subs path* 2)))
        :else path*))))

(defn data-dir
  [global-dir]
  (io/file
   (expand-home-path
    (or (nonblank (getenv "IRIS_DATA_DIR"))
        (str (io/file global-dir "data"))))))

(defn- legacy-default-path? [path filename]
  (contains? #{(str "data/" filename) (str "data\\" filename)}
             (str path)))

(defn- resolve-data-path [path data-dir filename]
  (if (or (nil? path) (legacy-default-path? path filename))
    (str (io/file data-dir filename))
    (expand-home-path path)))

(defn- absolute-path? [path]
  (.isAbsolute (io/file path)))

(defn- distinct-paths [paths]
  (->> paths
       (remove nil?)
       (reduce (fn [acc path]
                 (if (some #(= % path) acc)
                   acc
                   (conj acc path)))
               [])))

(defn- resolve-config-first-paths [paths config-dir]
  (->> paths
       (mapcat (fn [path]
                 (let [path* (expand-home-path path)]
                   (if (or (nil? path*) (absolute-path? path*))
                     [path*]
                     [(str (io/file config-dir path*))
                      (str (io/file (*cwd*) path*))]))))
       distinct-paths
       vec))

(defn- resource-template-content [name]
  (when-let [resource (io/resource (if (= config-file-name name)
                                     "config/default.edn"
                                     name))]
    (slurp resource)))

(defn- default-file-content [name]
  (or (resource-template-content name)
      (if (= config-file-name name)
        (str (binding [*print-namespace-maps* false]
               (pr-str default-config-edn))
             "\n")
        (get default-markdown-content name ""))))

(defn- warn!
  [message attrs]
  (binding [*out* *err*]
    (println (str "WARNING " message " " (pr-str attrs)))))

(defn- error!
  [message attrs]
  (binding [*out* *err*]
    (println (str "ERROR " message " " (pr-str attrs)))))

(defn bootstrap-global-config!
  []
  (let [dir (global-config-dir)]
    (.mkdirs dir)
    (doseq [name template-file-names
            :let [file (io/file dir name)]]
      (when-not (.exists file)
        (warn! "iris config file missing; writing default"
               {:path (.getPath file)})
        (spit file (default-file-content name))))
    dir))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left) (map? right))
             (deep-merge left right)
             right))
         maps))

(def ^:private provider-keys
  #{:ollama :openrouter :openai-compatible})

(def ^:private provider-default-types
  {:ollama :ollama
   :openrouter :openrouter
   :openai-compatible :openai-compatible})

(def ^:private legacy-llm-provider-option-keys
  [:model :temperature :max-tokens :stream? :prompt-cache?
   :stream-structured-output? :timeout-ms :site-url :app-name])

(defn- normalize-provider-config
  [provider-key provider-cfg]
  (assoc provider-cfg :type (or (:type provider-cfg)
                                (provider-default-types provider-key)
                                provider-key)))

(defn- normalize-llm-config
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

(defn- normalize-config
  [cfg]
  (cond-> cfg
    (contains? cfg :llm) (update :llm normalize-llm-config)))

(def ^:private provider-required-keys
  {:ollama [:base-url :model]
   :openrouter [:base-url :model :api-key]
   :openai-compatible [:base-url :model :api-key]})

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
      (vec
       (for [k (provider-required-keys type)
             :when (not (present-config-value? (get provider-cfg k)))]
         {:path [:llm :providers provider k]
          :message (str "active LLM provider " provider " missing required key " k)})))))

(defn- config-validation-errors [cfg]
  (let [llm-cfg (normalize-llm-config (:llm cfg))
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
      (into (missing-provider-keys provider provider-cfg)))))

(defn- validate-config! [cfg]
  (let [errors (config-validation-errors cfg)]
    (when (seq errors)
      (doseq [error errors]
        (error! "iris config invalid" error))
      (throw (ex-info "Invalid iris config" {:type :config-invalid
                                             :errors errors}))))
  cfg)

(defn- existing-file [path]
  (let [file (io/file path)]
    (when (.exists file)
      file)))

(defn- load-edn-file [path]
  (when-let [file (existing-file path)]
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (edn/read reader))))

(defn- load-optional-edn
  [file]
  (when (.exists file)
    (load-edn-file (.getPath file))))

(defn- normalize-iris-namespaced-config
  [cfg]
  (reduce (fn [acc k]
            (let [iris-k (keyword "iris" (name k))]
              (if (and (contains? acc iris-k)
                       (not (contains? acc k)))
                (assoc acc k (get acc iris-k))
                acc)))
          cfg
          app-config-keys))

(defn- read-context-file
  [file name required?]
  (if (.exists file)
    (slurp file)
    (do
      (when required?
        (warn! "iris context file missing; using default"
               {:path (.getPath file)}))
      (when required?
        (default-file-content name)))))

(defn- load-context-files
  [global-dir local-dir]
  (let [local-exists? (.exists local-dir)]
    (into {}
          (map (fn [name]
                 (let [global-content (read-context-file (io/file global-dir name) name true)
                       local-content (when local-exists?
                                       (read-context-file (io/file local-dir name) name false))]
                   [name (str global-content local-content)])))
          markdown-file-names)))

(defn- iris-runtime-config
  [global-dir local-dir contexts]
  {:iris {:config-dir (.getPath global-dir)
          :local-config-dir (.getPath local-dir)
          :data-dir (.getPath (data-dir global-dir))
          :context-files markdown-file-names
          :contexts contexts
          :context (str/join "\n" (map contexts markdown-file-names))}})

(defn- finalize-data-paths
  [cfg global-dir]
  (let [data-dir* (data-dir global-dir)]
    (-> cfg
        (assoc-in [:iris :data-dir] (.getPath data-dir*))
        (update-in [:storage :sqlite :path] resolve-data-path data-dir* "agent.db")
        (update-in [:memory :graph :datahike :path] resolve-data-path data-dir* "memory-graph"))))

(defn- finalize-skill-dirs
  [cfg global-dir]
  (update-in cfg [:skills :dirs] resolve-config-first-paths global-dir))

(defn- keyword-env [name]
  (some-> (getenv name) str/lower-case not-empty keyword))

(defn env-config
  []
  (let [provider (keyword-env "AGENT_LLM_PROVIDER")
        model (or (getenv "AGENT_LLM_MODEL")
                  (getenv "OPENROUTER_MODEL")
                  (getenv "OLLAMA_MODEL"))
        timeout-ms (parse-long* (getenv "AGENT_LLM_TIMEOUT_MS"))
        temperature (some-> (getenv "AGENT_LLM_TEMPERATURE")
                            Double/parseDouble)
        max-tokens (parse-long* (getenv "AGENT_LLM_MAX_TOKENS"))
        stream? (parse-bool (getenv "AGENT_LLM_STREAM"))
        prompt-cache? (parse-bool (getenv "AGENT_LLM_PROMPT_CACHE"))
        stream-structured-output? (parse-bool (getenv "AGENT_LLM_STREAM_STRUCTURED_OUTPUT"))
        stream-content? (parse-bool (getenv "AGENT_LLM_STREAM_CONTENT"))
        site-url (getenv "OPENROUTER_SITE_URL")
        app-name (or (getenv "OPENROUTER_APP_NAME")
                     (getenv "AGENT_APP_NAME"))
        openrouter-base-url (getenv "OPENROUTER_BASE_URL")
        ollama-base-url (getenv "OLLAMA_BASE_URL")
        keep-alive (getenv "OLLAMA_KEEP_ALIVE")
        embedding-model (getenv "OLLAMA_EMBEDDING_MODEL")
        openai-base-url (getenv "OPENAI_BASE_URL")
        sqlite-path (getenv "AGENT_SQLITE_PATH")
        chat-max-steps (parse-long* (getenv "AGENT_CHAT_MAX_STEPS"))
        memory-prompt-paths (parse-csv (getenv "AGENT_MEMORY_PROMPT_PATHS"))
        memory-search-limit (parse-long* (getenv "AGENT_MEMORY_SEARCH_DEFAULT_LIMIT"))
        memory-search-max-limit (parse-long* (getenv "AGENT_MEMORY_SEARCH_MAX_LIMIT"))
        memory-search-min-score (parse-double* (getenv "AGENT_MEMORY_SEARCH_MIN_SCORE"))
        memory-vault-paths (parse-csv (getenv "AGENT_MEMORY_VAULT_PATHS"))
        memory-vault-writable? (parse-bool (getenv "AGENT_MEMORY_VAULT_WRITABLE"))
        fact-extractor-enabled (parse-bool (getenv "AGENT_FACT_EXTRACTOR_ENABLED"))
        fact-extractor-provider (keyword-env "AGENT_FACT_EXTRACTOR_PROVIDER")
        fact-extractor-model (getenv "AGENT_FACT_EXTRACTOR_MODEL")
        fact-dedup-similarity-threshold (some-> (getenv "AGENT_FACT_DEDUP_SIMILARITY_THRESHOLD")
                                                Double/parseDouble)
        memory-graph-enabled (parse-bool (getenv "AGENT_MEMORY_GRAPH_ENABLED"))
        memory-graph-path (getenv "AGENT_MEMORY_GRAPH_PATH")
        telegram-enabled (parse-bool (getenv "AGENT_TELEGRAM_ENABLED"))
        telegram-bot-token (getenv "AGENT_TELEGRAM_BOT_TOKEN")
        telegram-allow-all? (parse-bool (getenv "AGENT_TELEGRAM_ALLOW_ALL"))
        telegram-user-ids (parse-csv (getenv "AGENT_TELEGRAM_ALLOWED_USER_IDS"))
        telegram-chat-ids (parse-csv (getenv "AGENT_TELEGRAM_ALLOWED_CHAT_IDS"))
        log-file (getenv "AGENT_LOG_FILE")
        log-enabled (parse-bool (getenv "AGENT_LOG_ENABLED"))
        telemetry-enabled (parse-bool (getenv "AGENT_TELEMETRY_ENABLED"))
        telemetry-max-latency-samples (parse-long* (getenv "AGENT_TELEMETRY_MAX_LATENCY_SAMPLES"))
        observer-enabled (parse-bool (getenv "AGENT_OBSERVER_ENABLED"))
        observer-best-effort? (parse-bool (getenv "AGENT_OBSERVER_BEST_EFFORT"))
        observer-sinks (parse-keyword-csv (getenv "AGENT_OBSERVER_SINKS"))
        trace-mode (keyword-env "AGENT_TRACE_MODE")
        trace-path (getenv "AGENT_TRACE_PATH")
        trace-rolling-max-entries (parse-long* (getenv "AGENT_TRACE_ROLLING_MAX_ENTRIES"))
        otel-enabled (parse-bool (or (getenv "AGENT_OTEL_ENABLED")
                                     (getenv "OTEL_ENABLED")))
        otel-url (or (getenv "AGENT_OTEL_URL")
                     (getenv "OTEL_EXPORTER_OTLP_ENDPOINT"))
        otel-send (some-> (getenv "AGENT_OTEL_SEND")
                          (str/split #",")
                          (->> (map str/trim)
                               (remove str/blank?)
                               (map keyword)
                               vec))
        otel-publish-delay (parse-long* (getenv "AGENT_OTEL_PUBLISH_DELAY_MS"))
        otel-max-items (parse-long* (getenv "AGENT_OTEL_MAX_ITEMS"))
        tools-yolo? (parse-bool (getenv "AGENT_TOOLS_YOLO"))
        tool-allowlist (parse-keyword-csv (getenv "AGENT_TOOL_ALLOWLIST"))
        tool-blocklist (parse-keyword-csv (getenv "AGENT_TOOL_BLOCKLIST"))
        approval-ttl-seconds (parse-long* (getenv "AGENT_TOOL_APPROVAL_TTL_SECONDS"))
        api-tool-permissions (parse-keyword-csv (getenv "AGENT_API_TOOL_PERMISSIONS"))
        ui-tool-permissions (parse-keyword-csv (getenv "AGENT_UI_TOOL_PERMISSIONS"))
        agent-tool-permissions (parse-keyword-csv (getenv "AGENT_AGENT_TOOL_PERMISSIONS"))
        chat-tool-permissions (parse-keyword-csv (getenv "AGENT_CHAT_TOOL_PERMISSIONS"))
        api-host (getenv "AGENT_API_HOST")
        api-key (getenv "AGENT_API_KEY")
        api-port (parse-long* (getenv "AGENT_API_PORT"))
        nrepl-enabled (parse-bool (getenv "AGENT_NREPL_ENABLED"))
        nrepl-bind (getenv "AGENT_NREPL_BIND")
        nrepl-port (parse-long* (getenv "AGENT_NREPL_PORT"))
        nrepl-port-file (getenv "AGENT_NREPL_PORT_FILE")
        memory-config (cond-> {}
                        memory-prompt-paths (assoc :prompt {:paths memory-prompt-paths})
                        (or (some? memory-search-limit) (some? memory-search-max-limit)
                            (some? memory-search-min-score))
                        (assoc :search (cond-> {}
                                         (some? memory-search-limit) (assoc :default-limit memory-search-limit)
                                         (some? memory-search-max-limit) (assoc :max-limit memory-search-max-limit)
                                         (some? memory-search-min-score) (assoc :min-score memory-search-min-score)))
                        (or memory-vault-paths (some? memory-vault-writable?))
                        (assoc :vault (cond-> {}
                                        memory-vault-paths (assoc :paths memory-vault-paths)
                                        (some? memory-vault-writable?) (assoc :writable? memory-vault-writable?)))
                        (or (some? fact-extractor-enabled) fact-extractor-provider fact-extractor-model
                            (some? fact-dedup-similarity-threshold))
                        (assoc :facts (cond-> {}
                                        (or (some? fact-extractor-enabled) fact-extractor-provider fact-extractor-model)
                                        (assoc :extractor
                                               (cond-> {}
                                                 (some? fact-extractor-enabled) (assoc :enabled fact-extractor-enabled)
                                                 fact-extractor-provider (assoc :provider fact-extractor-provider)
                                                 fact-extractor-model (assoc :model fact-extractor-model)))
                                        (some? fact-dedup-similarity-threshold)
                                        (assoc :dedup {:similarity-threshold fact-dedup-similarity-threshold})))
                        (or (some? memory-graph-enabled) memory-graph-path)
                        (assoc :graph (cond-> {}
                                        (some? memory-graph-enabled) (assoc :enabled memory-graph-enabled)
                                        memory-graph-path (assoc :datahike {:path memory-graph-path}))))
        telegram-allowlist (cond-> {}
                             (some? telegram-allow-all?) (assoc :allow-all? telegram-allow-all?)
                             telegram-user-ids (assoc :user-ids telegram-user-ids)
                             telegram-chat-ids (assoc :chat-ids telegram-chat-ids))
        telegram-config (cond-> {}
                          (some? telegram-enabled) (assoc :enabled telegram-enabled)
                          telegram-bot-token (assoc :bot-token telegram-bot-token)
                          (or telegram-user-ids telegram-chat-ids) (assoc :allowlist telegram-allowlist))
        channel-adapters-config (cond-> {}
                                  (or (some? telegram-enabled) telegram-bot-token (some? telegram-allow-all?)
                                      telegram-user-ids telegram-chat-ids)
                                  (assoc :telegram telegram-config))]
    {:llm (cond-> {}
            provider (assoc :provider provider)
            model (assoc :model model)
            (some? timeout-ms) (assoc :timeout-ms timeout-ms)
            (some? temperature) (assoc :temperature temperature)
            (some? max-tokens) (assoc :max-tokens max-tokens)
            (some? stream?) (assoc :stream? stream?)
            (some? prompt-cache?) (assoc :prompt-cache? prompt-cache?)
            (some? stream-structured-output?) (assoc :stream-structured-output? stream-structured-output?)
            (some? stream-content?) (assoc :stream-content? stream-content?)
            site-url (assoc :site-url site-url)
            app-name (assoc :app-name app-name)
            (or openrouter-base-url (getenv "OPENROUTER_API_KEY"))
            (assoc :openrouter (cond-> {}
                                 openrouter-base-url (assoc :base-url openrouter-base-url)
                                 (getenv "OPENROUTER_API_KEY")
                                 (assoc :api-key (getenv "OPENROUTER_API_KEY"))))
            (or ollama-base-url keep-alive embedding-model)
            (assoc :ollama (cond-> {}
                             ollama-base-url (assoc :base-url ollama-base-url)
                             keep-alive (assoc :keep-alive keep-alive)
                             embedding-model (assoc :embedding-model embedding-model)))
            (or openai-base-url (getenv "OPENAI_API_KEY"))
            (assoc :openai-compatible
                   (cond-> {}
                     openai-base-url (assoc :base-url openai-base-url)
                     (getenv "OPENAI_API_KEY")
                     (assoc :api-key (getenv "OPENAI_API_KEY")))))
     :storage (cond-> {}
                sqlite-path (assoc :sqlite {:path sqlite-path}))
     :chat (cond-> {}
             (some? chat-max-steps) (assoc :max-steps chat-max-steps))
     :nrepl (cond-> {}
              (some? nrepl-enabled) (assoc :enabled nrepl-enabled)
              nrepl-bind (assoc :bind nrepl-bind)
              (some? nrepl-port) (assoc :port nrepl-port)
              nrepl-port-file (assoc :port-file nrepl-port-file))
     :memory memory-config
     :channel-adapters channel-adapters-config
     :tools (cond-> {}
              (some? tools-yolo?) (assoc :yolo? tools-yolo?)
              (or tool-allowlist tool-blocklist)
              (assoc :policy
                     (cond-> {}
                       tool-allowlist (assoc :allowlist tool-allowlist)
                       tool-blocklist (assoc :blocklist tool-blocklist)))
              approval-ttl-seconds (assoc :approvals {:ttl-seconds approval-ttl-seconds})
              (or api-tool-permissions ui-tool-permissions agent-tool-permissions chat-tool-permissions)
              (assoc :permissions
                     (cond-> {}
                       api-tool-permissions (assoc :api api-tool-permissions)
                       ui-tool-permissions (assoc :ui ui-tool-permissions)
                       agent-tool-permissions (assoc :agent agent-tool-permissions)
                       chat-tool-permissions (assoc :chat chat-tool-permissions))))
     :telemetry (cond-> {}
                  (some? telemetry-enabled) (assoc :enabled telemetry-enabled)
                  (some? telemetry-max-latency-samples) (assoc :max-latency-samples telemetry-max-latency-samples))
     :observer (cond-> {}
                 (some? observer-enabled) (assoc :enabled observer-enabled)
                 (some? observer-best-effort?) (assoc :best-effort? observer-best-effort?)
                 observer-sinks (assoc :sinks observer-sinks))
     :trace (cond-> {}
              trace-mode (assoc :mode trace-mode)
              trace-path (assoc :path trace-path)
              (some? trace-rolling-max-entries) (assoc :rolling-max-entries trace-rolling-max-entries))
     :logging (cond-> {}
                (some? log-enabled) (assoc :enabled log-enabled)
                log-file (assoc :file {:path log-file})
                (or (some? otel-enabled) otel-url otel-send otel-publish-delay otel-max-items)
                (assoc :otel (cond-> {}
                               (some? otel-enabled) (assoc :enabled otel-enabled)
                               otel-url (assoc :url otel-url)
                               otel-send (assoc :send otel-send)
                               (some? otel-publish-delay) (assoc :publish-delay otel-publish-delay)
                               (some? otel-max-items) (assoc :max-items otel-max-items))))
     :api (cond-> {}
            api-host (assoc :host api-host)
            api-key (assoc :key api-key)
            (some? api-port) (assoc :port api-port))}))

(defn load-config
  ([] (load-config nil))
  ([path]
   (let [global-dir (bootstrap-global-config!)
         local-dir (local-config-dir)
         contexts (load-context-files global-dir local-dir)
         global-config (load-optional-edn (io/file global-dir config-file-name))
         local-config (load-optional-edn (io/file local-dir config-file-name))
         explicit-config (when path (load-edn-file path))]
     (let [file-config (deep-merge default-config
                                   (some-> global-config normalize-iris-namespaced-config normalize-config)
                                   (some-> local-config normalize-iris-namespaced-config normalize-config)
                                   (iris-runtime-config global-dir local-dir contexts)
                                   (some-> explicit-config normalize-iris-namespaced-config normalize-config))]
       (-> (deep-merge file-config (env-config))
           normalize-config
           (finalize-data-paths global-dir)
           (finalize-skill-dirs global-dir)
           validate-config!)))))

(defn llm-config
  [config]
  (:llm config))

(defn active-provider-key
  [llm-cfg]
  (:active-provider (normalize-llm-config llm-cfg)))

(defn active-provider-config
  [llm-cfg]
  (let [llm-cfg* (normalize-llm-config llm-cfg)
        provider (active-provider-key llm-cfg*)]
    (assoc (get-in llm-cfg* [:providers provider]) :provider provider)))

(defn active-model
  [llm-cfg]
  (:model (active-provider-config llm-cfg)))
