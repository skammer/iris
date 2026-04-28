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
      (is (= :ollama (get-in cfg [:llm :provider])))
      (is (= "llama3.2:3b" (get-in cfg [:llm :model])))
      (is (true? (get-in cfg [:llm :prompt-cache?])))
      (is (true? (get-in cfg [:llm :stream-structured-output?])))
      (is (= "http://localhost:11434" (get-in cfg [:llm :ollama :base-url])))
      (is (true? (get-in cfg [:tools :http :enabled])))
      (is (false? (get-in cfg [:tools :yolo?])))
      (is (= [:filesystem-read :filesystem-write :http-request]
             (get-in cfg [:tools :permissions :api])))
      (is (= [:filesystem-read :http-request :memory-read :memory-write]
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
      (is (= :session (get-in cfg [:memory :facts :default-scope])))
      (is (nil? (get-in cfg [:memory :facts :dedup :similarity-threshold])))
      (is (= {:enabled false
              :bot-token nil
              :poll-timeout-seconds 30
              :poll-limit 100
              :allowlist {:allow-all? false
                          :user-ids []
                          :chat-ids []}}
             (get-in cfg [:channel-adapters :telegram])))
      (is (false? (get-in cfg [:logging :enabled])))
      (is (= "logs/iris.log" (get-in cfg [:logging :file :path])))
      (is (= 10485760 (get-in cfg [:logging :file :max-bytes])))
      (is (= "65532:65532" (get-in cfg [:runners :docker :user]))))))

(deftest load-config-explicit-file-test
  (with-isolated-config [root {}]
    (let [cfg (config/load-config "config/default.edn")]
      (is (= :ollama (get-in cfg [:llm :provider])))
      (is (= "iris" (get-in cfg [:llm :app-name]))))))

(deftest load-config-explicit-file-overrides-default-provider-test
  (with-isolated-config [root {}]
    (let [file (java.io.File/createTempFile "iris-config-" ".edn")]
      (spit file "{:llm {:provider :openai-compatible\n       :model \"deepseek-chat\"\n       :openai-compatible {:base-url \"https://api.deepseek.com/v1\"\n                           :api-key \"test-key\"}}}")
      (try
        (let [cfg (config/load-config (.getAbsolutePath file))]
          (is (= :openai-compatible (get-in cfg [:llm :provider])))
          (is (= "deepseek-chat" (get-in cfg [:llm :model])))
          (is (= "https://api.deepseek.com/v1" (get-in cfg [:llm :openai-compatible :base-url]))))
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
      (doseq [name config/context-file-names]
        (is (.exists (io/file dir name))))
      (is (.exists (io/file dir "HEARTBEAT.md")))
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
        (is (= "global-model" (get-in cfg [:llm :model])))
        (is (= 0.7 (get-in cfg [:llm :temperature])))
        (is (= 9 (get-in cfg [:memory :search :default-limit])))))))

(deftest namespaced-map-config-normalization-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")]
      (.mkdirs global-dir)
      (spit (io/file global-dir "config.edn")
            "#:iris{:config-version 1\n        :api {:port 9090}\n        :llm {:model \"namespaced-model\"}}")
      (let [cfg (config/load-config)]
        (is (= 9090 (get-in cfg [:api :port])))
        (is (= "namespaced-model" (get-in cfg [:llm :model])))))))

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
