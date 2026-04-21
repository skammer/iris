(ns agent.config-test
  (:require
   [agent.config :as config]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(deftest load-config-defaults-test
  (let [cfg (config/load-config)]
    (is (= :ollama (get-in cfg [:llm :provider])))
    (is (= "llama3.2:3b" (get-in cfg [:llm :model])))
    (is (= "http://localhost:11434" (get-in cfg [:llm :ollama :base-url])))
    (is (true? (get-in cfg [:tools :http :enabled])))
    (is (false? (get-in cfg [:logging :enabled])))
    (is (= "logs/clj-agent.log" (get-in cfg [:logging :file :path])))
    (is (= 10485760 (get-in cfg [:logging :file :max-bytes])))
    (is (= "65532:65532" (get-in cfg [:runners :docker :user])))))

(deftest load-config-explicit-file-test
  (let [cfg (config/load-config "config/default.edn")]
    (is (= :ollama (get-in cfg [:llm :provider])))
    (is (= "clj-agent" (get-in cfg [:llm :app-name])))))

(deftest load-config-explicit-file-overrides-default-provider-test
  (let [file (java.io.File/createTempFile "clj-agent-config-" ".edn")]
    (spit file "{:llm {:provider :openai-compatible\n       :model \"deepseek-chat\"\n       :openai-compatible {:base-url \"https://api.deepseek.com/v1\"\n                           :api-key \"test-key\"}}}")
    (try
      (let [cfg (config/load-config (.getAbsolutePath file))]
        (is (= :openai-compatible (get-in cfg [:llm :provider])))
        (is (= "deepseek-chat" (get-in cfg [:llm :model])))
        (is (= "https://api.deepseek.com/v1" (get-in cfg [:llm :openai-compatible :base-url]))))
      (finally
        (io/delete-file file true)))))
