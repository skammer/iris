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
   :storage {:sqlite {:path "data/agent.db"}}
   :tools {:http {:enabled true
                  :timeout-ms 30000
                  :default-headers {"User-Agent" "clj-agent/0.1"}}
           :fs {:enabled true
                :roots ["."]
                :max-read-bytes 1048576}
           :shell {:enabled true
                   :roots ["."]
                   :working-dir "."
                   :timeout-ms 30000
                   :deny-by-default? true
                   :allowed-commands ["printf" "pwd" "ls" "echo" "cat" "rg" "git"]
                   :max-output-bytes 65536}}
   :skills {:dirs ["skills"]}
   :memory {:prompt {:paths ["MEMORY.md"]}
            :search {:default-limit 20}
            :graph {:enabled false
                    :backend :datahike
                    :datahike {:path "data/memory-graph"
                               :keep-history? true}}}
   :channel-adapters {:telegram {:enabled false}
                      :discord {:enabled false}
                      :slack {:enabled false}}
   :runners {:docker {:image "clojure:temurin-21-alpine"
                      :container-working-dir "/workspace"
                      :container-data-dir "/agent-data"
                      :container-home-dir "/root"
                      :host-working-dir "."
                      :share-network? false}
             :podman {:image "clojure:temurin-21-alpine"
                      :container-working-dir "/workspace"
                      :container-data-dir "/agent-data"
                      :container-home-dir "/root"
                      :host-working-dir "."
                      :share-network? false}}
   :orchestrator {:enabled true}
   :logging {:enabled true
             :file {:path "logs/clj-agent.log"}}
   :api {:host "127.0.0.1"
         :port 8080}})

(defn- parse-bool [value]
  (when-not (nil? value)
    (contains? #{"1" "true" "yes" "on"} (str/lower-case (str value)))))

(defn- parse-long* [value]
  (when (some? value)
    (Long/parseLong (str value))))

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
        site-url (System/getenv "OPENROUTER_SITE_URL")
        app-name (or (System/getenv "OPENROUTER_APP_NAME")
                     (System/getenv "AGENT_APP_NAME"))
        openrouter-base-url (System/getenv "OPENROUTER_BASE_URL")
        ollama-base-url (System/getenv "OLLAMA_BASE_URL")
        keep-alive (System/getenv "OLLAMA_KEEP_ALIVE")
        embedding-model (System/getenv "OLLAMA_EMBEDDING_MODEL")
        openai-base-url (System/getenv "OPENAI_BASE_URL")
        sqlite-path (System/getenv "AGENT_SQLITE_PATH")
        log-file (System/getenv "AGENT_LOG_FILE")
        log-enabled (parse-bool (System/getenv "AGENT_LOG_ENABLED"))
        api-host (System/getenv "AGENT_API_HOST")
        api-port (parse-long* (System/getenv "AGENT_API_PORT"))]
    {:llm (cond-> {}
            provider (assoc :provider provider)
            model (assoc :model model)
            (some? timeout-ms) (assoc :timeout-ms timeout-ms)
            (some? temperature) (assoc :temperature temperature)
            (some? max-tokens) (assoc :max-tokens max-tokens)
            (some? stream?) (assoc :stream? stream?)
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
     :logging (cond-> {}
                (some? log-enabled) (assoc :enabled log-enabled)
                log-file (assoc :file {:path log-file}))
     :api (cond-> {}
            api-host (assoc :host api-host)
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
