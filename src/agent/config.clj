(ns agent.config
  "Configuration loading for the rewritten runtime."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-config
  {:llm {:provider :ollama
         :model "llama3.2:3b"
         :temperature 0.2
         :max-tokens 1024
         :stream? false
         :prompt-cache? true
         :stream-structured-output? true
         :stream-content? true
         :timeout-ms 60000
         :site-url nil
         :app-name "iris"
         :openrouter {:base-url "https://openrouter.ai/api/v1"
                      :api-key nil}
         :ollama {:base-url "http://localhost:11434"
                  :keep-alive "5m"
                  :embedding-model "nomic-embed-text"}
         :openai-compatible {:base-url "https://api.openai.com/v1"
                             :api-key nil}}
   :storage {:sqlite {:path "data/agent.db"
                      :journal-mode "WAL"}}
   :tools {:http {:enabled true
                  :timeout-ms 30000
                  :max-timeout-ms 30000
                  :max-response-bytes 1048576
                  :allow-private? false
                  :max-redirects 3
                  :default-headers {"User-Agent" "iris/0.1"}}
           :yolo? false
           :permissions {:api [:filesystem-read :filesystem-write :http-request]
                         :ui [:filesystem-read :filesystem-write :http-request]
                         :agent [:http-request :memory-read :memory-write]
                         :chat [:filesystem-read :http-request :memory-read :memory-write]}
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
            :search {:default-limit 20}
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
(def context-file-names (into [config-file-name] markdown-file-names))
(def app-config-keys
  [:llm :storage :tools :skills :memory :channel-adapters :runners
   :orchestrator :telemetry :logging :api])

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

(defn- default-file-content [name]
  (if (= config-file-name name)
    (str (binding [*print-namespace-maps* false]
           (pr-str default-config-edn))
         "\n")
    (get default-markdown-content name "")))

(defn- warn!
  [message attrs]
  (binding [*out* *err*]
    (println (str "WARNING " message " " (pr-str attrs)))))

(defn bootstrap-global-config!
  []
  (let [dir (global-config-dir)]
    (.mkdirs dir)
    (doseq [name context-file-names
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
          :context-files markdown-file-names
          :contexts contexts
          :context (str/join "\n" (map contexts markdown-file-names))}})

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
        memory-prompt-paths (parse-csv (getenv "AGENT_MEMORY_PROMPT_PATHS"))
        memory-search-limit (parse-long* (getenv "AGENT_MEMORY_SEARCH_DEFAULT_LIMIT"))
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
                        (some? memory-search-limit) (assoc :search {:default-limit memory-search-limit})
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
         resource-config (when-let [resource (io/resource "config/default.edn")]
                           (with-open [reader (java.io.PushbackReader. (io/reader resource))]
                             (edn/read reader)))
         project-config (load-edn-file "config/default.edn")
         global-config (load-optional-edn (io/file global-dir config-file-name))
         local-config (load-optional-edn (io/file local-dir config-file-name))
         explicit-config (when path (load-edn-file path))]
     (deep-merge default-config
                 resource-config
                 project-config
                 (some-> global-config normalize-iris-namespaced-config)
                 (some-> local-config normalize-iris-namespaced-config)
                 (iris-runtime-config global-dir local-dir contexts)
                 (some-> explicit-config normalize-iris-namespaced-config)
                 (env-config)))))

(defn llm-config
  [config]
  (:llm config))
