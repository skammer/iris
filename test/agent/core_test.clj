(ns agent.core-test
  (:require
   [agent.core :as core]
   [agent.config :as config]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]
   [clojure.test :refer :all]))

(deftest create-llm-provider-selects-ollama
  (let [provider (core/create-llm-provider (:llm (config/load-config)))]
    (is (instance? agent.llm.providers.ollama.OllamaProvider provider))))

(deftest create-llm-provider-selects-openrouter
  (let [provider (core/create-llm-provider
                  {:provider :openrouter
                   :model "openai/gpt-4o-mini"
                   :site-url "https://example.com"
                   :app-name "clj-agent-test"
                   :openrouter {:base-url "https://openrouter.ai/api/v1"
                                :api-key "or-key"}})]
    (is (instance? agent.llm.providers.openai_compatible.OpenAICompatibleProvider provider))))

(deftest create-system-registers-default-tools
  (let [system (core/create-system)
        tools (core/list-tools system)
        adapters (core/list-channel-adapters system)]
    (is (= [:fs :http :shell] (mapv :name tools)))
    (is (= ["Discord" "Slack" "Telegram"] (mapv :display-name adapters)))
    (is (empty? (core/list-skills system)))
    (is (= 3 (count (core/memory-surfaces system))))
    (is (= 3 (get-in (core/health-check system) [:tools :count])))
    (is (= 0 (get-in (core/health-check system) [:runtime :run-count])))
    (is (= 3 (get-in (core/health-check system) [:channel-adapters :count])))
    (is (= 0 (get-in (core/health-check system) [:orchestrator :agent-count])))))
