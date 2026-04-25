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
         :timeout-ms 60000
         :site-url nil
         :app-name "clj-agent"
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
                  :default-headers {"User-Agent" "clj-agent/0.1"}}
           :yolo? false
           :permissions {:api [:filesystem-read :filesystem-write :http-request]
                         :ui [:filesystem-read :filesystem-write :http-request]
                         :agent [:http-request]
                         :chat [:filesystem-read :http-request]}
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
                   :allowed-commands ["printf" "pwd" "ls" "echo" "cat" "rg" "git"]
                   :blocked-commands []
                   :max-output-bytes 65536}}
   :skills {:dirs ["skills"]}
   :memory {:prompt {:paths ["MEMORY.md"]}
            :search {:default-limit 20}
            :facts {:extractor {:enabled true
                                :provider nil
                                :model nil}}
            :graph {:enabled false
                    :backend :datahike
                    :datahike {:path "data/memory-graph"
                               :keep-history? true}}}
   :channel-adapters {:telegram {:enabled false
                                  :bot-token nil
                                  :allowlist {:user-ids []
                                              :chat-ids []}}
                      :discord {:enabled false}
                      :slack {:enabled false}}
   :runners {:docker {:image "clojure:temurin-21-alpine"
                      :image-mode :mounted-dev
                      :pull-policy :missing
                      :container-working-dir "/workspace"
                      :container-data-dir "/tmp/clj-agent"
                      :container-home-dir "/tmp/clj-agent/home"
                      :user "65532:65532"
                      :host-working-dir "."
                      :share-network? true}
             :podman {:image "clojure:temurin-21-alpine"
                      :image-mode :mounted-dev
                      :pull-policy :missing
                      :container-working-dir "/workspace"
                      :container-data-dir "/tmp/clj-agent"
                      :container-home-dir "/tmp/clj-agent/home"
                      :user "65532:65532"
                      :host-working-dir "."
                      :share-network? true}}
   :orchestrator {:enabled true}
   :telemetry {:enabled true
               :max-latency-samples 1000}
   :logging {:enabled false
             :file {:path "logs/clj-agent.log"
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

(defn- keyword-env [name]
  (some-> (System/getenv name) str/lower-case not-empty keyword))

(defn env-config
  []
  (let [provider (keyword-env "AGENT_LLM_PROVIDER")
        model (or (System/getenv "AGENT_LLM_MODEL")
                  (System/getenv "OPENROUTER_MODEL")
                  (System/getenv "OLLAMA_MODEL"))
        timeout-ms (parse-long* (System/getenv "AGENT_LLM_TIMEOUT_MS"))
        temperature (some-> (System/getenv "AGENT_LLM_TEMPERATURE")
                            Double/parseDouble)
        max-tokens (parse-long* (System/getenv "AGENT_LLM_MAX_TOKENS"))
        stream? (parse-bool (System/getenv "AGENT_LLM_STREAM"))
        prompt-cache? (parse-bool (System/getenv "AGENT_LLM_PROMPT_CACHE"))
        stream-structured-output? (parse-bool (System/getenv "AGENT_LLM_STREAM_STRUCTURED_OUTPUT"))
        site-url (System/getenv "OPENROUTER_SITE_URL")
        app-name (or (System/getenv "OPENROUTER_APP_NAME")
                     (System/getenv "AGENT_APP_NAME"))
        openrouter-base-url (System/getenv "OPENROUTER_BASE_URL")
        ollama-base-url (System/getenv "OLLAMA_BASE_URL")
        keep-alive (System/getenv "OLLAMA_KEEP_ALIVE")
        embedding-model (System/getenv "OLLAMA_EMBEDDING_MODEL")
        openai-base-url (System/getenv "OPENAI_BASE_URL")
        sqlite-path (System/getenv "AGENT_SQLITE_PATH")
        memory-prompt-paths (parse-csv (System/getenv "AGENT_MEMORY_PROMPT_PATHS"))
        memory-search-limit (parse-long* (System/getenv "AGENT_MEMORY_SEARCH_DEFAULT_LIMIT"))
        fact-extractor-enabled (parse-bool (System/getenv "AGENT_FACT_EXTRACTOR_ENABLED"))
        fact-extractor-provider (keyword-env "AGENT_FACT_EXTRACTOR_PROVIDER")
        fact-extractor-model (System/getenv "AGENT_FACT_EXTRACTOR_MODEL")
        memory-graph-enabled (parse-bool (System/getenv "AGENT_MEMORY_GRAPH_ENABLED"))
        memory-graph-path (System/getenv "AGENT_MEMORY_GRAPH_PATH")
        telegram-enabled (parse-bool (System/getenv "AGENT_TELEGRAM_ENABLED"))
        telegram-bot-token (System/getenv "AGENT_TELEGRAM_BOT_TOKEN")
        telegram-user-ids (parse-csv (System/getenv "AGENT_TELEGRAM_ALLOWED_USER_IDS"))
        telegram-chat-ids (parse-csv (System/getenv "AGENT_TELEGRAM_ALLOWED_CHAT_IDS"))
        log-file (System/getenv "AGENT_LOG_FILE")
        log-enabled (parse-bool (System/getenv "AGENT_LOG_ENABLED"))
        telemetry-enabled (parse-bool (System/getenv "AGENT_TELEMETRY_ENABLED"))
        telemetry-max-latency-samples (parse-long* (System/getenv "AGENT_TELEMETRY_MAX_LATENCY_SAMPLES"))
        otel-enabled (parse-bool (or (System/getenv "AGENT_OTEL_ENABLED")
                                     (System/getenv "OTEL_ENABLED")))
        otel-url (or (System/getenv "AGENT_OTEL_URL")
                     (System/getenv "OTEL_EXPORTER_OTLP_ENDPOINT"))
        otel-send (some-> (System/getenv "AGENT_OTEL_SEND")
                          (str/split #",")
                          (->> (map str/trim)
                               (remove str/blank?)
                               (map keyword)
                               vec))
        otel-publish-delay (parse-long* (System/getenv "AGENT_OTEL_PUBLISH_DELAY_MS"))
        otel-max-items (parse-long* (System/getenv "AGENT_OTEL_MAX_ITEMS"))
        tools-yolo? (parse-bool (System/getenv "AGENT_TOOLS_YOLO"))
        tool-allowlist (parse-keyword-csv (System/getenv "AGENT_TOOL_ALLOWLIST"))
        tool-blocklist (parse-keyword-csv (System/getenv "AGENT_TOOL_BLOCKLIST"))
        approval-ttl-seconds (parse-long* (System/getenv "AGENT_TOOL_APPROVAL_TTL_SECONDS"))
        api-tool-permissions (parse-keyword-csv (System/getenv "AGENT_API_TOOL_PERMISSIONS"))
        ui-tool-permissions (parse-keyword-csv (System/getenv "AGENT_UI_TOOL_PERMISSIONS"))
        agent-tool-permissions (parse-keyword-csv (System/getenv "AGENT_AGENT_TOOL_PERMISSIONS"))
        chat-tool-permissions (parse-keyword-csv (System/getenv "AGENT_CHAT_TOOL_PERMISSIONS"))
        api-host (System/getenv "AGENT_API_HOST")
        api-key (System/getenv "AGENT_API_KEY")
        api-port (parse-long* (System/getenv "AGENT_API_PORT"))
        memory-config (cond-> {}
                        memory-prompt-paths (assoc :prompt {:paths memory-prompt-paths})
                        (some? memory-search-limit) (assoc :search {:default-limit memory-search-limit})
                        (or (some? fact-extractor-enabled) fact-extractor-provider fact-extractor-model)
                        (assoc :facts {:extractor (cond-> {}
                                                    (some? fact-extractor-enabled) (assoc :enabled fact-extractor-enabled)
                                                    fact-extractor-provider (assoc :provider fact-extractor-provider)
                                                    fact-extractor-model (assoc :model fact-extractor-model))})
                        (or (some? memory-graph-enabled) memory-graph-path)
                        (assoc :graph (cond-> {}
                                        (some? memory-graph-enabled) (assoc :enabled memory-graph-enabled)
                                        memory-graph-path (assoc :datahike {:path memory-graph-path}))))
        telegram-allowlist (cond-> {}
                             telegram-user-ids (assoc :user-ids telegram-user-ids)
                             telegram-chat-ids (assoc :chat-ids telegram-chat-ids))
        telegram-config (cond-> {}
                          (some? telegram-enabled) (assoc :enabled telegram-enabled)
                          telegram-bot-token (assoc :bot-token telegram-bot-token)
                          (or telegram-user-ids telegram-chat-ids) (assoc :allowlist telegram-allowlist))
        channel-adapters-config (cond-> {}
                                  (or (some? telegram-enabled) telegram-bot-token telegram-user-ids telegram-chat-ids)
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
            site-url (assoc :site-url site-url)
            app-name (assoc :app-name app-name)
            (or openrouter-base-url (System/getenv "OPENROUTER_API_KEY"))
            (assoc :openrouter (cond-> {}
                                 openrouter-base-url (assoc :base-url openrouter-base-url)
                                 (System/getenv "OPENROUTER_API_KEY")
                                 (assoc :api-key (System/getenv "OPENROUTER_API_KEY"))))
            (or ollama-base-url keep-alive embedding-model)
            (assoc :ollama (cond-> {}
                             ollama-base-url (assoc :base-url ollama-base-url)
                             keep-alive (assoc :keep-alive keep-alive)
                             embedding-model (assoc :embedding-model embedding-model)))
            (or openai-base-url (System/getenv "OPENAI_API_KEY"))
            (assoc :openai-compatible
                   (cond-> {}
                     openai-base-url (assoc :base-url openai-base-url)
                     (System/getenv "OPENAI_API_KEY")
                     (assoc :api-key (System/getenv "OPENAI_API_KEY")))))
     :storage (cond-> {}
                sqlite-path (assoc :sqlite {:path sqlite-path}))
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
   (let [resource-config (when-let [resource (io/resource "config/default.edn")]
                           (with-open [reader (java.io.PushbackReader. (io/reader resource))]
                             (edn/read reader)))
         project-config (load-edn-file "config/default.edn")
         explicit-config (when path (load-edn-file path))]
     (deep-merge default-config
                 resource-config
                 project-config
                 explicit-config
                 (env-config)))))

(defn llm-config
  [config]
  (:llm config))
