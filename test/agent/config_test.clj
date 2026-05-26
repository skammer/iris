(ns agent.config-test
  (:require
   [agent.config :as config]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

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
      (is (= {:context-window 128000
              :max-output-tokens 16384}
             (get-in cfg [:llm :providers :openai-compatible :models "gpt-4o-mini"])))
      (is (true? (get-in cfg [:tools :http :enabled])))
      (is (= 6 (get-in cfg [:chat :max-steps])))
      (is (= {:max-iterations 10
              :plan-file "LOOP_PLAN.md"
              :summary-max-chars 1200
              :validation-max-chars 12000}
             (:loop cfg)))
      (is (= {:enabled? true
              :threshold 3
              :window-size 16
              :action :stop}
             (get-in cfg [:chat :guardrails :doom-loop])))
      (is (false? (get-in cfg [:tools :yolo?])))
      (is (= [:filesystem-read :filesystem-write :http-request :system-reload :todo-read :todo-write]
             (get-in cfg [:tools :permissions :api])))
      (is (= [:filesystem-read :http-request :memory-read :memory-write :system-reload :todo-read :todo-write]
             (get-in cfg [:tools :permissions :chat])))
      (is (= {:allowlist []
              :blocklist []
              :tool-scopes {}}
             (get-in cfg [:tools :policy])))
      (is (= 900 (get-in cfg [:tools :approvals :ttl-seconds])))
      (is (= {:enabled true
              :provider nil
              :model nil}
             (get-in cfg [:memory :facts :extractor])))
      (is (= {:default-limit 10
              :max-limit 10
              :min-score 0.3}
             (get-in cfg [:memory :search])))
      (is (= :session (get-in cfg [:memory :facts :default-scope])))
      (is (nil? (get-in cfg [:memory :facts :dedup :similarity-threshold])))
      (is (= {:enabled false
              :bot-token nil
              :poll-timeout-seconds 30
              :poll-limit 100
              :max-download-bytes 20971520
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

(deftest default-data-paths-use-global-data-dir-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)
          data-dir (str (io/file root "home" ".config" "iris" "data"))]
      (is (= data-dir (get-in cfg [:iris :data-dir])))
      (is (= (str (io/file data-dir "agent.db"))
             (get-in cfg [:storage :sqlite :path])))
      (is (= (str (io/file data-dir "memory-graph"))
             (get-in cfg [:memory :graph :datahike :path]))))))

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
             (get-in cfg [:storage :sqlite :path])))
      (is (= (str (io/file data-dir "memory-graph"))
             (get-in cfg [:memory :graph :datahike :path]))))))

(deftest explicit-data-paths-are-preserved-test
  (with-isolated-config [root {"AGENT_SQLITE_PATH" "~/db/agent.sqlite"
                               "AGENT_MEMORY_GRAPH_PATH" "/tmp/iris-graph"}]
    (let [cfg (config/load-config)]
      (is (= (str (io/file root "home" "db" "agent.sqlite"))
             (get-in cfg [:storage :sqlite :path])))
      (is (= "/tmp/iris-graph"
             (get-in cfg [:memory :graph :datahike :path]))))))

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

(deftest bootstrap-global-config-files-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config)
          dir (io/file (get-in cfg [:iris :config-dir]))]
      (doseq [name config/template-file-names]
        (is (.exists (io/file dir name))))
      (is (.exists (io/file dir "HEARTBEAT.md")))
      (is (re-find #"Durable prompt memory"
                   (slurp (io/file dir "MEMORY.md"))))
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
            "{:llm {:model \"global-model\"}\n :memory {:search {:default-limit 5}}}")
      (spit (io/file local-dir "config.edn")
            "{:llm {:temperature 0.7}\n :memory {:search {:default-limit 9}}}")
      (let [cfg (config/load-config)]
        (is (= "global-model" (config/active-model (:llm cfg))))
        (is (= 0.7 (get-in cfg [:llm :providers :ollama :temperature])))
        (is (= 9 (get-in cfg [:memory :search :default-limit])))))))

(deftest config-load-order-ignores-default-files-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          project-dir (io/file root "work" "config")]
      (.mkdirs global-dir)
      (.mkdirs project-dir)
      (spit (io/file global-dir "config.edn")
            "{:llm {:provider :openai-compatible\n       :providers {:openai-compatible {:type :openai-compatible\n                                         :base-url \"https://api.example.test/v1\"\n                                         :model \"global-model\"\n                                         :api-key \"test-key\"}}}\n :api {:port 1001}}")
      (spit (io/file project-dir "default.edn")
            "{:llm {:provider :ollama :model \"project-model\"}\n :api {:port 2002}}")
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
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Invalid iris config"
                                (config/load-config (.getAbsolutePath file)))))
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
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Invalid iris config"
                                (config/load-config (.getAbsolutePath file)))))
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
            "{:llm {:provider :openai-compatible :model \"global-model\"}}")
      (spit explicit-file
            "{:llm {:provider :openrouter :model \"explicit-model\"}}")
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
            "#:iris{:config-version 1\n        :api {:port 9090}\n        :llm {:model \"namespaced-model\"}}")
      (let [cfg (config/load-config)]
        (is (= 9090 (get-in cfg [:api :port])))
        (is (= "namespaced-model" (config/active-model (:llm cfg))))))))

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

(deftest missing-files-warning-test
  (with-isolated-config [root {}]
    (let [err (java.io.StringWriter.)]
      (binding [*err* err]
        (config/load-config))
      (is (re-find #"WARNING iris config file missing; writing default"
                   (str err)))
      (is (re-find #"HEARTBEAT.md" (str err))))))
