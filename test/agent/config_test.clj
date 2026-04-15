(ns agent.config-test
  (:require
   [agent.config :as config]
   [clojure.test :refer :all]))

(deftest load-config-defaults-test
  (let [cfg (config/load-config)]
    (is (= :ollama (get-in cfg [:llm :provider])))
    (is (= "llama3.2:3b" (get-in cfg [:llm :model])))
    (is (= "http://localhost:11434" (get-in cfg [:llm :ollama :base-url])))
    (is (true? (get-in cfg [:tools :http :enabled])))))

(deftest load-config-explicit-file-test
  (let [cfg (config/load-config "config/default.edn")]
    (is (= :ollama (get-in cfg [:llm :provider])))
    (is (= "clj-agent" (get-in cfg [:llm :app-name])))))
