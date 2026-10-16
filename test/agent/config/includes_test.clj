(ns ^{:clj-kondo/config '{:lint-as {agent.config.test-support/with-isolated-config clojure.core/let}
                          :linters {:unused-binding {:level :off}
                                    :unresolved-symbol {:exclude [root]}}}}
  agent.config.includes-test
  (:require
   [agent.config.test-support :refer [thrown-data with-isolated-config]]
   [agent.config :as config]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

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
