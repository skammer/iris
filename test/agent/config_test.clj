(ns ^{:clj-kondo/config '{:lint-as {agent.config-test/with-isolated-config clojure.core/let}
                          :linters {:unused-binding {:level :off}
                                    :unresolved-symbol {:exclude [root]}}}}
  agent.config-test
  (:require
   [agent.config :as config]
   [agent.defaults :as defaults]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn- thrown-message
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (.getMessage e))))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-config-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defmacro with-isolated-config
  [[root env] & body]
  `(let [~root (temp-dir)]
     (try
       (.mkdirs (io/file ~root "home"))
       (.mkdirs (io/file ~root "work"))
       (binding [config/*env* (fn [k#] (get ~env k#))
                 config/*user-home* (fn [] (.getPath (io/file ~root "home")))
                 config/*cwd* (fn [] (.getPath (io/file ~root "work")))
                 *err* (java.io.StringWriter.)]
         ~@body)
       (finally
         (io/delete-file ~root true)))))

(deftest load-config-defaults-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)]
      (is (= :ollama (config/active-provider-key (:llm cfg))))
      (is (= "llama3.2:3b" (config/active-model (:llm cfg))))
      (is (nil? (get-in cfg [:llm :provider])))
      (is (nil? (get-in cfg [:llm :model])))
      (is (true? (get-in cfg [:llm :providers :ollama :prompt-cache?])))
      (is (true? (get-in cfg [:llm :providers :ollama :stream-structured-output?])))
      (is (= "http://localhost:11434" (get-in cfg [:llm :providers :ollama :base-url])))
      (is (= {:path (get-in cfg [:storage :sqlite :path])
              :journal-mode "WAL"
              :maximum-pool-size 8
              :minimum-idle 2
              :connection-timeout-ms 30000
              :destructive-reset-on-drift? false}
             (:sqlite (:storage cfg))))
      (is (= {:context-window 128000
              :max-output-tokens 16384
              :supports-streaming true
              :supports-tools true
              :supports-vision true}
             (get-in cfg [:llm :providers :openai-compatible :models "gpt-4o-mini"])))
      (is (true? (get-in cfg [:tools :http :enabled])))
      (is (= defaults/chat-max-steps (get-in cfg [:chat :max-steps])))
      (is (= {:max-iterations 10
              :plan-file "LOOP_PLAN.md"
              :summary-max-chars 1200
              :validation-max-chars 12000}
             (:loop cfg)))
	      (is (= {:enabled? true
	              :threshold 3
	              :window-size 16}
	             (get-in cfg [:chat :guardrails :doom-loop])))
	      (is (empty? (select-keys (:guardrails (:chat cfg))
	                                [:enabled? :max-retries :respond-tool? :force-tool-choice? :tool-routing?])))
	      (is (not (contains? (get-in cfg [:chat :guardrails :doom-loop]) :action)))
      (is (false? (get-in cfg [:tools :yolo?])))
      (is (= 6 (get-in cfg [:tools :max-parallelism])))
      (is (= [:filesystem-read :filesystem-write :http-request :system-reload :todo-read :todo-write :magi-evaluate :homeassistant :wasm-execute]
             (get-in cfg [:tools :permissions :api])))
      (is (= [:filesystem-read :http-request :memory-read :memory-write :system-reload :todo-read :todo-write :shell-exec :magi-evaluate :homeassistant :wasm-execute]
             (get-in cfg [:tools :permissions :chat])))
      (is (= {:allowlist []
              :blocklist []
              :tool-scopes {}}
             (get-in cfg [:tools :policy])))
      (is (= 900 (get-in cfg [:tools :approvals :ttl-seconds])))
      (is (= {:enabled false
              :base-url nil
              :token nil
              :timeout-ms 10000
              :allowed-domains #{:light :switch :scene :script}
              :global-services #{}}
             (get-in cfg [:tools :homeassistant])))
      (is (= {:enabled false
              :timeout-ms 30000
              :max-wasm-bytes 1048576
              :max-stdout-bytes 1048576
              :max-stderr-bytes 1048576
              :max-memory-pages 64
              :wasi {:args []
                     :env {}
                     :stdin ""
                     :fs {:mounts []
                          :allowed-roots []
                          :max-copy-bytes 10485760}}
              :network {:enabled? false
                        :allowed-hosts []
                        :allow-private? false
                        :timeout-ms 10000
                        :max-response-bytes 1048576}}
             (get-in cfg [:tools :wasm])))
      (is (= {:enabled? true
              :install-dir (str (io/file root "home" ".config" "iris" "bundles" "installed"))
              :package-dir (str (io/file root "home" ".config" "iris" "bundles" "packages"))
              :dev-roots []
              :enabled []
              :settings {}
              :timeout-ms 30000
              :max-stdout-bytes 1048576
              :max-stderr-bytes 1048576
              :max-memory-pages 64
              :http {:timeout-ms 10000
                     :max-timeout-ms 30000
                     :max-response-bytes 1048576}}
             (get-in cfg [:tools :wasm-bundles])))
      (is (= {:enabled? false
              :mode :assistive
              :fallback :human
              :apply-to #{:tool-approvals}
              :tool-categories #{:all}
              :memory-promotion {:mode :manual
                                 :scopes #{:all}
                                 :poll-interval-seconds 60
                                 :failure-cooldown-minutes 15
                                 :max-candidates 10}
              :tool {:enabled true}
              :execution :parallel
              :allow-critical? false
              :timeout-ms 30000
              :max-context-chars 12000
              :file-review {:enabled? true
                            :max-tool-calls 8
                            :max-tool-rounds 4
                            :timeout-ms 90000
                            :max-evidence-chars 32000
                            :max-tool-result-chars 12000}
              :filter {:provider nil :model nil}
              :judge {:provider nil :model nil}
              :agents {:melchior {:provider nil :model nil}
                       :balthasar {:provider nil :model nil}
                       :casper {:provider nil :model nil}}}
             (:magi cfg)))
      (is (= {:enabled true
              :provider nil
              :model nil
              :format :json-schema}
             (get-in cfg [:memory :notes :extractor])))
      (is (= {:enabled true
              :idle-timeout-minutes 45
              :poll-interval-seconds 60
              :failure-cooldown-minutes 15
              :max-sessions 20
              :max-messages 80
              :max-events 40
              :min-confidence 0.85
              :include-events? true}
             (get-in cfg [:memory :notes :idle-extraction])))
      (is (= {:default-limit 10
              :max-limit 10
              :min-score 0.3}
             (get-in cfg [:memory :search])))
      (is (= {:enabled? false
              :surfaces [:vault-notes :vault-chunks]
              :batch-size 16
              :candidate-limit 1000
              :rebuild-mode :replace}
             (get-in cfg [:memory :embeddings])))
      (is (= {:low-confidence-threshold 0.6
              :stale-days 180}
             (get-in cfg [:memory :quality])))
      (is (= :session (get-in cfg [:memory :notes :default-scope])))
      (is (= {:enabled false
              :bot-token nil
              :rich-messages? true
              :poll-timeout-seconds 30
              :poll-limit 100
              :max-download-bytes 20971520
              :document-roots ["."]
              :max-document-bytes 20971520
              :allowlist {:allow-all? false
                          :user-ids []
                          :chat-ids []}}
             (get-in cfg [:channel-adapters :telegram])))
      (is (false? (get-in cfg [:logging :enabled])))
      (is (= "logs/iris.log" (get-in cfg [:logging :file :path])))
      (is (= 10485760 (get-in cfg [:logging :file :max-bytes])))
      (is (= {:enabled true
              :best-effort? true
              :sinks [:telemetry :logging]}
             (:observer cfg)))
      (is (= {:mode :none
              :path "runtime-trace.jsonl"
              :rolling-max-entries 1000}
             (:trace cfg)))
      (is (= {:enabled true
              :bind "127.0.0.1"
              :port 0
              :port-file ".nrepl-port"}
             (:nrepl cfg)))
      (is (= "65532:65532" (get-in cfg [:runners :docker :user]))))))

(deftest default-config-template-matches-code-defaults-test
  (let [template (edn/read-string (slurp (io/resource "config/default.edn")))]
    (doseq [provider [:ollama :openrouter :openai-compatible]]
      (is (= defaults/llm-temperature
             (get-in template [:llm :providers provider :temperature])))
      (is (= defaults/llm-max-tokens
             (get-in template [:llm :providers provider :max-tokens]))))
    (is (= defaults/chat-max-steps (get-in template [:chat :max-steps])))))

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

(deftest default-data-paths-use-global-data-dir-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)
          data-dir (str (io/file root "home" ".config" "iris" "data"))]
	      (is (= data-dir (get-in cfg [:iris :data-dir])))
	      (is (= (str (io/file data-dir "agent.db"))
	             (get-in cfg [:storage :sqlite :path]))))))

(deftest relative-skill-dirs-resolve-config-dir-then-cwd-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)]
      (is (= [(str (io/file root "home" ".config" "iris" "skills"))
              (str (io/file root "work" "skills"))]
             (get-in cfg [:skills :dirs]))))))

(deftest absolute-and-home-skill-dirs-are-not-cwd-expanded-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            (pr-str {:skills {:dirs ["/tmp/iris-skills" "~/iris-skills"]}}))
      (let [cfg (config/load-config)]
        (is (= ["/tmp/iris-skills"
                (str (io/file root "home" "iris-skills"))]
               (get-in cfg [:skills :dirs])))))))

(deftest data-dir-env-overrides-default-data-paths-test
  (with-isolated-config [root {"IRIS_DATA_DIR" "~/iris-data"}]
    (let [cfg (config/load-config)
          data-dir (str (io/file root "home" "iris-data"))]
	      (is (= data-dir (get-in cfg [:iris :data-dir])))
	      (is (= (str (io/file data-dir "agent.db"))
	             (get-in cfg [:storage :sqlite :path]))))))

(deftest explicit-data-paths-are-preserved-test
  (with-isolated-config [root {"AGENT_SQLITE_PATH" "~/db/agent.sqlite"}]
    (let [cfg (config/load-config)]
      (is (= (str (io/file root "home" "db" "agent.sqlite"))
             (get-in cfg [:storage :sqlite :path]))))))

(deftest load-config-explicit-file-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config "config/default.edn")]
      (is (= :ollama (config/active-provider-key (:llm cfg))))
      (is (= "iris" (get-in cfg [:llm :providers :ollama :app-name]))))))

(deftest load-config-explicit-file-overrides-default-provider-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")]
      (spit file "{:llm {:active-provider :deepseek\n       :providers {:deepseek {:type :openai-compatible\n                              :base-url \"https://api.deepseek.com/v1\"\n                              :api-key \"test-key\"\n                              :model \"deepseek-chat\"}}}}")
      (try
        (let [cfg (config/load-config (.getAbsolutePath file))]
          (is (= :deepseek (config/active-provider-key (:llm cfg))))
          (is (= "deepseek-chat" (config/active-model (:llm cfg))))
          (is (= "https://api.deepseek.com/v1" (get-in cfg [:llm :providers :deepseek :base-url]))))
        (finally
          (io/delete-file file true))))))

(deftest load-config-explicit-edn-otel-config-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")]
      (spit file
            "{:logging {:otel {:enabled true
                               :url \"http://collector:4318/\"
                               :send [:traces]
                               :max-items 100
                               :publish-delay 250
                               :http-opts {:conn-timeout 500
                                           :socket-timeout 750}}}}")
      (try
        (let [cfg (config/load-config (.getAbsolutePath file))]
          (is (true? (get-in cfg [:logging :otel :enabled])))
          (is (= "http://collector:4318/" (get-in cfg [:logging :otel :url])))
          (is (= [:traces] (get-in cfg [:logging :otel :send])))
          (is (= 100 (get-in cfg [:logging :otel :max-items])))
          (is (= 250 (get-in cfg [:logging :otel :publish-delay])))
          (is (= {:conn-timeout 500
                  :socket-timeout 750}
                 (get-in cfg [:logging :otel :http-opts]))))
        (finally
          (io/delete-file file true))))))

(deftest deepseek-provider-type-loads-with-json-object-structured-output-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")]
      (spit file "{:llm {:active-provider :deepseek\n       :providers {:deepseek {:type :deepseek\n                              :api-key \"test-key\"\n                              :model \"deepseek-chat\"}}}}")
      (try
        (let [cfg (config/load-config (.getAbsolutePath file))]
          (is (= :deepseek (config/active-provider-key (:llm cfg))))
          (is (= :deepseek (get-in cfg [:llm :providers :deepseek :type])))
          (is (= "deepseek-chat" (config/active-model (:llm cfg)))))
        (finally
          (io/delete-file file true))))))

(deftest per-model-chat-profile-beats-named-and-default-profile-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")]
      (spit file
            "{:llm {:active-provider :neuraldeep
                    :providers {:neuraldeep {:type :openai-compatible
                                             :base-url \"https://api.example.test/v1\"
                                             :api-key \"test-key\"
                                             :model \"qwen3.6-35b-a3b\"
                                             :models {\"qwen3.6-35b-a3b\"
                                                      {:chat-profile {:small-model? true
                                                                      :max-nudges 3}}}}}}
              :chat {:active-profile :small-local
                     :profiles {:default {:small-model? false :max-nudges 0}
                                :small-local {:provider :neuraldeep
                                              :model \"qwen3.6-35b-a3b\"
                                              :small-model? false
                                              :max-nudges 1}}}}")
      (try
        (let [cfg (config/load-config (.getAbsolutePath file))
              profile (config/chat-profile cfg)]
          (is (true? (:small-model? profile)))
          (is (= 3 (:max-nudges profile))))
        (finally
          (io/delete-file file true))))))

(deftest config-dir-resolution-test
  (with-isolated-config [root {"IRIS_CONFIG_DIR" (str (io/file root "custom"))}]
    (let [cfg (config/load-config)]
      (is (= (str (io/file root "custom")) (get-in cfg [:iris :config-dir])))))
  (with-isolated-config [root {"XDG_CONFIG_HOME" (str (io/file root "xdg"))}]
    (let [cfg (config/load-config)]
      (is (= (str (io/file root "xdg" "iris")) (get-in cfg [:iris :config-dir])))))
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)]
      (is (= (str (io/file root "home" ".config" "iris"))
             (get-in cfg [:iris :config-dir]))))))

(deftest load-config-does-not-bootstrap-global-files-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)
          dir (io/file (get-in cfg [:iris :config-dir]))]
      (doseq [name config/template-file-names]
        (is (not (.exists (io/file dir name)))))
      (is (re-find #"Agent-specific instructions"
                   (get-in cfg [:iris :contexts "AGENTS.md"])))
      (is (re-find #"Runtime operating rules for Iris agents"
                   (get-in cfg [:iris :contexts "BOOT.md"])))
      (is (re-find #"Tool-use policy for Iris agents"
                   (get-in cfg [:iris :contexts "TOOLS.md"]))))))

(deftest init-config-files-test
  (with-isolated-config [root {}]
    (let [dir (config/init-config!)]
      (doseq [name config/template-file-names]
        (is (.exists (io/file dir name))))
      (is (.exists (io/file dir "HEARTBEAT.md")))
      (is (not (.exists (io/file dir "MEMORY.md"))))
      (is (not (.exists (io/file dir "HEARBEAT.md"))))
      (is (re-find #":iris/config-version"
                   (slurp (io/file dir "config.edn"))))
      (is (not (re-find #"^#:iris"
                        (slurp (io/file dir "config.edn"))))))))

(deftest global-local-config-merge-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          local-dir (io/file root "work" ".iris")]
      (.mkdirs global-dir)
      (.mkdirs local-dir)
      (spit (io/file global-dir "config.edn")
            "{:llm {:providers {:ollama {:model \"global-model\"}}}\n :memory {:search {:default-limit 5}}}")
      (spit (io/file local-dir "config.edn")
            "{:llm {:providers {:ollama {:temperature 0.7}}}\n :memory {:search {:default-limit 9}}}")
      (let [cfg (config/load-config)]
        (is (= "global-model" (config/active-model (:llm cfg))))
        (is (= 0.7 (get-in cfg [:llm :providers :ollama :temperature])))
        (is (= 9 (get-in cfg [:memory :search :default-limit])))))))

(deftest global-config-includes-fragment-and-parent-wins-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          providers-dir (io/file global-dir "providers")]
      (.mkdirs providers-dir)
      (spit (io/file providers-dir "ollama.edn")
            (pr-str {:llm {:providers {:ollama {:model "fragment-model"
                                                :temperature 0.2}}}
                     :memory {:search {:default-limit 5}}}))
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["providers/ollama.edn"]
                     :llm {:providers {:ollama {:model "global-model"}}}}))
      (let [cfg (config/load-config)]
        (is (= "global-model" (config/active-model (:llm cfg))))
        (is (= 0.2 (get-in cfg [:llm :providers :ollama :temperature])))
        (is (= 5 (get-in cfg [:memory :search :default-limit])))))))

(deftest local-config-overrides-global-included-config-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          local-dir (io/file root "work" ".iris")]
      (.mkdirs global-dir)
      (.mkdirs local-dir)
      (spit (io/file global-dir "provider.edn")
            (pr-str {:llm {:providers {:ollama {:model "global-fragment-model"}}}}))
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["provider.edn"]}))
      (spit (io/file local-dir "config.edn")
            (pr-str {:llm {:providers {:ollama {:model "local-model"}}}}))
      (let [cfg (config/load-config)]
        (is (= "local-model" (config/active-model (:llm cfg))))))))

(deftest explicit-config-includes-relative-fragment-and-beats-global-local-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          local-dir (io/file root "work" ".iris")
          explicit-dir (io/file root "explicit")
          fragment-dir (io/file explicit-dir "fragments")
          explicit-file (io/file explicit-dir "config.edn")]
      (.mkdirs global-dir)
      (.mkdirs local-dir)
      (.mkdirs fragment-dir)
      (spit (io/file global-dir "config.edn")
            (pr-str {:llm {:providers {:ollama {:model "global-model"}}}}))
      (spit (io/file local-dir "config.edn")
            (pr-str {:llm {:providers {:ollama {:model "local-model"}}}}))
      (spit (io/file fragment-dir "ollama.edn")
            (pr-str {:llm {:providers {:ollama {:model "explicit-fragment-model"}}}}))
      (spit explicit-file
            (pr-str {:config/includes ["fragments/ollama.edn"]
                     :llm {:providers {:ollama {:temperature 0.6}}}}))
      (let [cfg (config/load-config (.getPath explicit-file))]
        (is (= "explicit-fragment-model" (config/active-model (:llm cfg))))
        (is (= 0.6 (get-in cfg [:llm :providers :ollama :temperature])))))))

(deftest config-includes-merge-left-to-right-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "a.edn")
            (pr-str {:llm {:providers {:ollama {:model "a-model"}}}}))
      (spit (io/file global-dir "b.edn")
            (pr-str {:llm {:providers {:ollama {:model "b-model"}}}}))
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["a.edn" "b.edn"]}))
      (let [cfg (config/load-config)]
        (is (= "b-model" (config/active-model (:llm cfg))))))))

(deftest nested-config-includes-resolve-from-declaring-file-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          fragments-dir (io/file global-dir "fragments")
          nested-dir (io/file fragments-dir "nested")]
      (.mkdirs nested-dir)
      (spit (io/file nested-dir "inner.edn")
            (pr-str {:memory {:search {:default-limit 3}}}))
      (spit (io/file fragments-dir "outer.edn")
            (pr-str {:config/includes ["nested/inner.edn"]
                     :llm {:providers {:ollama {:model "outer-model"}}}}))
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["fragments/outer.edn"]}))
      (let [cfg (config/load-config)]
        (is (= "outer-model" (config/active-model (:llm cfg))))
        (is (= 3 (get-in cfg [:memory :search :default-limit])))))))

(deftest missing-config-include-errors-with-path-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["missing.edn"]}))
      (let [data (thrown-data #(config/load-config))]
        (is (= :config-include-not-found (:type data)))
        (is (re-find #"missing\.edn" (:path data)))))))

(deftest config-include-cycle-errors-with-stack-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["a.edn"]}))
      (spit (io/file global-dir "a.edn")
            (pr-str {:config/includes ["b.edn"]}))
      (spit (io/file global-dir "b.edn")
            (pr-str {:config/includes ["a.edn"]}))
      (let [data (thrown-data #(config/load-config))]
        (is (= :config-include-cycle (:type data)))
        (is (<= 3 (count (:stack data))))
        (is (re-find #"a\.edn" (last (:stack data))))))))

(deftest config-include-invalid-shape-errors-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes "fragment.edn"}))
      (let [data (thrown-data #(config/load-config))]
        (is (= :config-include-invalid (:type data)))
        (is (= "fragment.edn" (:value data)))))))

(deftest loader-config-is-stripped-from-final-config-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "fragment.edn")
            (pr-str {:llm {:providers {:ollama {:model "fragment-model"}}}}))
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["fragment.edn"]
                     :config {:source :loader}}))
      (let [cfg (config/load-config)]
        (is (= "fragment-model" (config/active-model (:llm cfg))))
        (is (not (contains? cfg :config)))
        (is (not (contains? cfg :config/includes)))))))

(deftest config-set-updates-included-source-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          fragment (io/file global-dir "telegram.edn")
          root-config (io/file global-dir "config.edn")]
      (.mkdirs global-dir)
      (spit fragment
            (pr-str {:channel-adapters
                     {:telegram {:rich-messages? false}}}))
      (spit root-config
            (pr-str {:config/includes ["telegram.edn"]}))
      (let [result (config/set-config-value!
                    "channel-adapters.telegram.rich_messages"
                    "true")]
        (is (= (.getCanonicalPath fragment) (:file result)))
        (is (= [:channel-adapters :telegram :rich-messages?] (:path result)))
        (is (false? (:created? result)))
        (is (true? (get-in (edn/read-string (slurp fragment))
                           [:channel-adapters :telegram :rich-messages?])))
        (is (= {:config/includes ["telegram.edn"]}
               (edn/read-string (slurp root-config))))
        (is (true? (get-in (config/load-config)
                           [:channel-adapters :telegram :rich-messages?])))))))

(deftest config-set-creates-global-config-for-new-value-test
  (with-isolated-config [root {}]
    (let [global-file (io/file root "home" ".config" "iris" "config.edn")
          result (config/set-config-value!
                  "tools.homeassistant.enabled"
                  "true")]
      (is (= (.getPath global-file) (:file result)))
      (is (= [:tools :homeassistant :enabled] (:path result)))
      (is (true? (:created? result)))
      (is (true? (get-in (edn/read-string (slurp global-file))
                         [:tools :homeassistant :enabled]))))))

(deftest config-set-prefers-existing-local-config-for-new-value-test
  (with-isolated-config [root {}]
    (let [local-dir (io/file root "work" ".iris")
          local-file (io/file local-dir "config.edn")]
      (.mkdirs local-dir)
      (spit local-file (pr-str {:api {:host "127.0.0.1"}}))
      (let [result (config/set-config-value! "api.port" "9090")]
        (is (= (.getPath local-file) (:file result)))
        (is (= [:api :port] (:path result)))
        (is (false? (:created? result)))
        (is (= 9090 (get-in (edn/read-string (slurp local-file))
                            [:api :port])))))))

(deftest config-set-writes-bare-words-as-strings-test
  (with-isolated-config [root {}]
    (let [explicit-file (io/file root "deepseek.edn")
          result (config/set-config-value!
                  "llm.providers.deepseek.model"
                  "deepseek-chat"
                  {:explicit-path (.getPath explicit-file)})]
      (is (= (.getPath explicit-file) (:file result)))
      (is (= "deepseek-chat"
             (get-in (edn/read-string (slurp explicit-file))
                     [:llm :providers :deepseek :model]))))))

(deftest env-overrides-config-includes-test
  (with-isolated-config [root {"AGENT_LLM_MODEL" "env-model"}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "provider.edn")
            (pr-str {:llm {:providers {:ollama {:model "included-model"}}}}))
      (spit (io/file global-dir "config.edn")
            (pr-str {:config/includes ["provider.edn"]}))
      (let [cfg (config/load-config)]
        (is (= "env-model" (config/active-model (:llm cfg))))))))

(deftest config-load-order-ignores-default-files-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          project-dir (io/file root "work" "config")]
      (.mkdirs global-dir)
      (.mkdirs project-dir)
      (spit (io/file global-dir "config.edn")
            "{:llm {:active-provider :openai-compatible\n       :providers {:openai-compatible {:type :openai-compatible\n                                         :base-url \"https://api.example.test/v1\"\n                                         :model \"global-model\"\n                                         :api-key \"test-key\"}}}\n :api {:port 1001}}")
      (spit (io/file project-dir "default.edn")
            "{:llm {:active-provider :ollama :providers {:ollama {:model \"project-model\"}}}\n :api {:port 2002}}")
      (let [cfg (config/load-config)]
        (is (= :openai-compatible (config/active-provider-key (:llm cfg))))
        (is (= "global-model" (config/active-model (:llm cfg))))
        (is (= 1001 (get-in cfg [:api :port])))))))

(deftest active-provider-requires-model-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")
          err (java.io.StringWriter.)]
      (spit file "{:llm {:active-provider :deepseek\n       :providers {:deepseek {:type :openai-compatible\n                              :base-url \"https://api.deepseek.com/v1\"\n                              :api-key \"test-key\"}}}}")
      (try
        (binding [*err* err]
          (is (re-find #"Invalid iris config"
                       (thrown-message #(config/load-config (.getAbsolutePath file))))))
        (is (re-find #"ERROR iris config invalid" (str err)))
        (is (re-find #":model" (str err)))
        (finally
          (io/delete-file file true))))))

(deftest active-provider-requires-api-key-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")
          err (java.io.StringWriter.)]
      (spit file "{:llm {:active-provider :openrouter\n       :providers {:openrouter {:type :openrouter\n                                :base-url \"https://openrouter.ai/api/v1\"\n                                :model \"openai/gpt-4o-mini\"}}}}")
      (try
        (binding [*err* err]
          (is (re-find #"Invalid iris config"
                       (thrown-message #(config/load-config (.getAbsolutePath file))))))
        (is (re-find #"ERROR iris config invalid" (str err)))
        (is (re-find #":api-key" (str err)))
        (finally
          (io/delete-file file true))))))

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

(deftest active-provider-rejects-unsupported-api-test
  (with-isolated-config [root {"AGENT_LLM_PROVIDER" "openai-compatible"
                               "OPENAI_API_KEY" "test-key"
                               "AGENT_LLM_API" "missing"}]
    (try
      (config/load-config)
      (is false "expected invalid config")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"unsupported :api"
                     (-> e ex-data :errors first :message)))))))

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

(deftest namespaced-map-config-normalization-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            "#:iris{:config-version 1\n        :api {:port 9090}\n        :llm {:providers {:ollama {:model \"namespaced-model\"}}}}")
      (let [cfg (config/load-config)]
        (is (= 9090 (get-in cfg [:api :port])))
        (is (= "namespaced-model" (config/active-model (:llm cfg))))))))

(deftest legacy-llm-config-is-rejected-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            "{:llm {:provider :ollama :model \"legacy-model\"}}")
      (is (re-find #"Invalid iris config"
                   (thrown-message #(config/load-config)))))))

(deftest migrate-legacy-config-file-test
  (with-isolated-config [root {}]
    (let [file (io/file root "legacy.edn")]
      (spit file
            "{:llm {:provider :openrouter
                    :model \"openai/gpt-4o-mini\"
                    :temperature 0.4
                    :openrouter {:base-url \"https://openrouter.ai/api/v1\"
                                 :api-key \"test-key\"}}}")
      (let [cfg (config/migrate-config-file (.getPath file))]
        (is (= :openrouter (get-in cfg [:llm :active-provider])))
        (is (= {:type :openrouter
                :base-url "https://openrouter.ai/api/v1"
                :api-key "test-key"
                :model "openai/gpt-4o-mini"
                :temperature 0.4}
               (get-in cfg [:llm :providers :openrouter])))
        (is (nil? (get-in cfg [:llm :provider])))
        (is (nil? (get-in cfg [:llm :model])))))))

(deftest markdown-context-concat-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          local-dir (io/file root "work" ".iris")]
      (.mkdirs global-dir)
      (.mkdirs local-dir)
      (spit (io/file global-dir "AGENTS.md") "global agents\n")
      (spit (io/file local-dir "AGENTS.md") "local agents\n")
      (let [cfg (config/load-config)]
        (is (= "global agents\nlocal agents\n"
               (get-in cfg [:iris :contexts "AGENTS.md"])))
        (is (re-find #"global agents\nlocal agents"
                     (get-in cfg [:iris :context])))))))

(deftest init-config-warning-test
  (with-isolated-config [root {}]
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (config/init-config!))
      (is (re-find #"WARNING iris config file missing; writing default"
                   (str err)))
      (is (re-find #"HEARTBEAT.md" (str err))))))
