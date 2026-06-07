(ns agent.llm.registry-test
  (:require
   [agent.llm.registry :as registry]
   [clojure.test :refer [deftest is]]))

(def llm-cfg
  {:active-provider :openrouter
   :providers {:ollama {:type :ollama
                        :base-url "http://localhost:11434"
                        :model "llama3.2:3b"}
               :openrouter {:type :openrouter
                            :base-url "https://openrouter.ai/api/v1"
                            :model "openai/gpt-4o-mini"
                            :api-key "k"
                            :context-window 128000
                            :max-output-tokens 16384
                            :max-tokens 2048}
               :local-compatible {:type :openai-compatible
                                  :base-url "http://localhost:8081/v1"
                                  :model "custom-model"}}})

(deftest active-provider-resolution-test
  (let [reg (registry/create-registry llm-cfg)]
    (is (= :openrouter (:active-provider reg)))
    (is (= :openrouter (:key (registry/active-provider reg))))
    (is (= "k" (registry/resolve-api-key reg :openrouter)))))

(deftest unknown-provider-error-test
  (let [reg (registry/create-registry llm-cfg)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown LLM provider"
                          (registry/provider reg :missing)))))

(deftest model-capability-lookup-test
  (let [reg (registry/create-registry llm-cfg)
        caps (registry/model-capabilities reg :openrouter "openai/gpt-4o-mini")]
    (is (= :openrouter (:provider caps)))
    (is (= :openrouter (:api-kind caps)))
    (is (= "openai/gpt-4o-mini" (:model-id caps)))
    (is (= 128000 (:context-window caps)))
    (is (= 16384 (:max-output-tokens caps)))
    (is (contains? (:input-modalities caps) :text))
    (is (true? (get-in caps [:tool-support :native?])))))

(deftest option-normalization-test
  (let [reg (registry/create-registry llm-cfg)
        opts (registry/normalize-options reg
                                         :openrouter
                                         {:temperature "0.3"
                                          :max_tokens "4096"
                                          :reasoning "high"
                                          :prompt_cache_retention "ephemeral"
                                          :session_id "s1"
                                          :headers {"X-Test" "1"}
                                          :timeout_ms "1000"
                                          :max_retries "2"})]
    (is (= 0.3 (:temperature opts)))
    (is (= 4096 (:max-tokens opts)))
    (is (= :high (:reasoning opts)))
    (is (= "ephemeral" (:cache-retention opts)))
    (is (= "s1" (:session-id opts)))
    (is (not (contains? opts :headers)))
    (is (= 1000 (:timeout-ms opts)))
    (is (= 2 (:max-retries opts)))))

(deftest unknown-provider-type-error-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown LLM provider type"
                        (registry/create-registry
                         {:active-provider :bad
                          :providers {:bad {:type :not-real
                                            :model "m"}}}))))

(deftest dynamic-api-key-resolver-test
  (let [reg (registry/create-registry
             (assoc-in llm-cfg [:providers :openrouter :api-key] nil)
             {:api-key-resolver (fn [provider _cfg]
                                  (when (= :openrouter provider) "dynamic"))})]
    (is (= "dynamic" (registry/resolve-api-key reg :openrouter)))))
