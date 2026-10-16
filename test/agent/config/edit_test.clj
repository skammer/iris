(ns ^{:clj-kondo/config '{:lint-as {agent.config.test-support/with-isolated-config clojure.core/let}
                          :linters {:unused-binding {:level :off}
                                    :unresolved-symbol {:exclude [root]}}}}
  agent.config.edit-test
  (:require
   [agent.config.test-support :refer [with-isolated-config]]
   [agent.config :as config]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

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
