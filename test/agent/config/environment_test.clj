(ns ^{:clj-kondo/config '{:lint-as {agent.config.test-support/with-isolated-config clojure.core/let}
                          :linters {:unused-binding {:level :off}
                                    :unresolved-symbol {:exclude [root]}}}}
  agent.config.environment-test
  (:require
   [agent.config.test-support :refer [with-isolated-config]]
   [agent.config :as config]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(deftest telegram-rich-messages-env-override-test
  (with-isolated-config [_root {"AGENT_TELEGRAM_RICH_MESSAGES" "false"}]
    (let [cfg (config/load-config)]
      (is (false? (get-in cfg [:channel-adapters :telegram :rich-messages?]))))))

(deftest invalid-env-bool-fails-with-env-context-test
  (with-isolated-config [_root {"AGENT_TOOLS_YOLO" "maybe"}]
    (try
      (config/load-config)
      (is false "expected invalid env config")
      (catch clojure.lang.ExceptionInfo e
        (is (= :env-config-invalid (:type (ex-data e))))
        (is (= "AGENT_TOOLS_YOLO" (:env/name (ex-data e))))))))

(deftest sqlite-pool-env-overrides-test
  (with-isolated-config [_root {"AGENT_SQLITE_MAXIMUM_POOL_SIZE" "12"
                                "AGENT_SQLITE_MINIMUM_IDLE" "3"
                                "AGENT_SQLITE_CONNECTION_TIMEOUT_MS" "1500"}]
    (let [cfg (config/load-config)]
      (is (= 12 (get-in cfg [:storage :sqlite :maximum-pool-size])))
      (is (= 3 (get-in cfg [:storage :sqlite :minimum-idle])))
      (is (= 1500 (get-in cfg [:storage :sqlite :connection-timeout-ms]))))))

(deftest tools-max-parallelism-env-override-test
  (with-isolated-config [_root {"AGENT_TOOLS_MAX_PARALLELISM" "4"}]
    (let [cfg (config/load-config)]
      (is (= 4 (get-in cfg [:tools :max-parallelism]))))))

(deftest homeassistant-env-overrides-test
  (with-isolated-config [_root {"AGENT_HOMEASSISTANT_ENABLED" "true"
                                "AGENT_HOMEASSISTANT_BASE_URL" "http://ha.local:8123"
                                "AGENT_HOMEASSISTANT_TOKEN" "ha-token"
                                "AGENT_HOMEASSISTANT_TIMEOUT_MS" "7000"
                                "AGENT_HOMEASSISTANT_ALLOWED_DOMAINS" "light,switch"
                                "AGENT_HOMEASSISTANT_GLOBAL_SERVICES" "scene.reload"}]
    (let [cfg (config/load-config)]
      (is (= {:enabled true
              :base-url "http://ha.local:8123"
              :token "ha-token"
              :timeout-ms 7000
              :allowed-domains [:light :switch]
              :global-services ["scene.reload"]}
             (get-in cfg [:tools :homeassistant]))))))

(deftest wasm-env-overrides-test
  (with-isolated-config [_root {"AGENT_WASM_ENABLED" "true"
                                "AGENT_WASM_TIMEOUT_MS" "5000"
                                "AGENT_WASM_MAX_BYTES" "4096"
                                "AGENT_WASM_MAX_MEMORY_PAGES" "8"
                                "AGENT_WASM_NETWORK_ENABLED" "true"
                                "AGENT_WASM_NETWORK_ALLOWED_HOSTS" "example.com,api.example.com"
                                "AGENT_WASM_NETWORK_ALLOW_PRIVATE" "true"}]
    (let [cfg (config/load-config)]
      (is (= true (get-in cfg [:tools :wasm :enabled])))
      (is (= 5000 (get-in cfg [:tools :wasm :timeout-ms])))
      (is (= 4096 (get-in cfg [:tools :wasm :max-wasm-bytes])))
      (is (= 8 (get-in cfg [:tools :wasm :max-memory-pages])))
      (is (= {:enabled? true
              :allowed-hosts ["example.com" "api.example.com"]
              :allow-private? true
              :timeout-ms 10000
              :max-response-bytes 1048576}
             (get-in cfg [:tools :wasm :network]))))))

(deftest magi-env-overrides-test
  (with-isolated-config [_root {"AGENT_MAGI_ENABLED" "true"
                                "AGENT_MAGI_MODE" "auto-approve"
                                "AGENT_MAGI_FALLBACK" "deny"
                                "AGENT_MAGI_TOOL_CATEGORIES" "shell,fs"
                                "AGENT_MAGI_AGENT_MELCHIOR_PROVIDER" "ollama"
                                "AGENT_MAGI_AGENT_MELCHIOR_MODEL" "magi-model"}]
    (let [cfg (config/load-config)]
      (is (true? (get-in cfg [:magi :enabled?])))
      (is (= :auto-approve (get-in cfg [:magi :mode])))
      (is (= :deny (get-in cfg [:magi :fallback])))
      (is (= [:shell :fs] (get-in cfg [:magi :tool-categories])))
      (is (= {:provider :ollama :model "magi-model"}
             (get-in cfg [:magi :agents :melchior]))))))

(deftest data-dir-env-overrides-default-data-paths-test
  (with-isolated-config [root {"IRIS_DATA_DIR" "~/iris-data"}]
    (let [cfg (config/load-config)
          data-dir (str (io/file root "home" "iris-data"))]
	      (is (= data-dir (get-in cfg [:iris :data-dir])))
	      (is (= (str (io/file data-dir "agent.db"))
	             (get-in cfg [:storage :sqlite :path]))))))

(deftest env-overrides-explicit-config-test
  (with-isolated-config [root {"AGENT_LLM_PROVIDER" "ollama"
                               "AGENT_LLM_MODEL" "env-model"
                               "AGENT_CHAT_MAX_STEPS" "12"
                               "AGENT_LOOP_MAX_ITERATIONS" "4"
                               "AGENT_LOOP_PLAN_FILE" "WORK.md"
                               "AGENT_LOOP_SUMMARY_MAX_CHARS" "300"
                               "AGENT_LOOP_VALIDATION_MAX_CHARS" "900"
                               "AGENT_MEMORY_SEARCH_DEFAULT_LIMIT" "7"
                               "AGENT_MEMORY_SEARCH_MAX_LIMIT" "9"
                               "AGENT_MEMORY_SEARCH_MIN_SCORE" "0.45"}]
    (let [global-dir (io/file root "home" ".config" "iris")
          explicit-file (io/file root "explicit.edn")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            "{:llm {:active-provider :openai-compatible
                    :providers {:openai-compatible {:type :openai-compatible
                                                    :base-url \"https://api.example.test/v1\"
                                                    :api-key \"test-key\"
                                                    :model \"global-model\"}}}}")
      (spit explicit-file
            "{:llm {:active-provider :openrouter
                    :providers {:openrouter {:type :openrouter
                                             :base-url \"https://openrouter.ai/api/v1\"
                                             :api-key \"test-key\"
                                             :model \"explicit-model\"}}}}")
      (let [cfg (config/load-config (.getPath explicit-file))]
        (is (= :ollama (config/active-provider-key (:llm cfg))))
        (is (= "env-model" (config/active-model (:llm cfg))))
        (is (= 12 (get-in cfg [:chat :max-steps])))
        (is (= {:max-iterations 4
                :plan-file "WORK.md"
                :summary-max-chars 300
                :validation-max-chars 900}
               (:loop cfg)))
        (is (= {:default-limit 7
                :max-limit 9
                :min-score 0.45}
               (get-in cfg [:memory :search])))))))

(deftest env-overrides-active-provider-api-test
  (with-isolated-config [root {"AGENT_LLM_PROVIDER" "openai-compatible"
                               "OPENAI_API_KEY" "test-key"
                               "AGENT_LLM_API" "responses"}]
    (let [cfg (config/load-config)]
      (is (= :responses
             (get-in cfg [:llm :providers :openai-compatible :api]))))))

(deftest trace-env-config-test
  (with-isolated-config [root {"AGENT_TRACE_MODE" "rolling"
                               "AGENT_TRACE_PATH" "dev-trace.jsonl"
                               "AGENT_TRACE_ROLLING_MAX_ENTRIES" "25"
                               "AGENT_OBSERVER_BEST_EFFORT" "true"}]
    (let [cfg (config/load-config)]
      (is (= :rolling (get-in cfg [:trace :mode])))
      (is (= "dev-trace.jsonl" (get-in cfg [:trace :path])))
      (is (= 25 (get-in cfg [:trace :rolling-max-entries])))
      (is (true? (get-in cfg [:observer :best-effort?]))))))
