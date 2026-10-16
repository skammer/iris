(ns ^{:clj-kondo/config '{:lint-as {agent.config.test-support/with-isolated-config clojure.core/let}
                          :linters {:unused-binding {:level :off}
                                    :unresolved-symbol {:exclude [root]}}}}
  agent.config.validation-test
  (:require
   [agent.config.test-support :refer [thrown-data thrown-message with-isolated-config]]
   [agent.config :as config]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(deftest telegram-session-reset-policy-validation-test
  (with-isolated-config [root {}]
    (let [global-dir (io/file root "home" ".config" "iris")
          config-file (io/file global-dir "config.edn")]
      (.mkdirs global-dir)
      (spit config-file
            (pr-str {:channel-adapters
                     {:telegram {:session-reset {:mode :idle
                                                 :idle-minutes 0}}}}))
      (let [data (thrown-data #(config/load-config))]
        (is (= :config-invalid (:type data)))
        (is (= [:channel-adapters :telegram :session-reset :idle-minutes]
               (-> data :errors first :path)))))))

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
