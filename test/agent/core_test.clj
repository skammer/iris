(ns agent.core-test
  (:require
   [agent.core :as core]
   [agent.config :as config]
   [agent.kernel]
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
        adapters (core/list-channel-adapters system)
        runner-keys (-> system :runner-registry keys set)]
    (is (= [:fs :http :shell] (mapv :name tools)))
    (is (= ["Discord" "Slack" "Telegram"] (mapv :display-name adapters)))
    (is (contains? runner-keys :local-process))
    (is (contains? runner-keys :bubblewrap))
    (is (contains? runner-keys :docker))
    (is (contains? runner-keys :podman))
    (is (contains? runner-keys :seatbelt))
    (is (empty? (core/list-skills system)))
    (is (= 3 (count (core/memory-surfaces system))))
    (is (= :local (get-in (core/health-check system) [:broker :backend])))
    (is (= 3 (get-in (core/health-check system) [:tools :count])))
    (is (= 0 (get-in (core/health-check system) [:runtime :run-count])))
    (is (= 3 (get-in (core/health-check system) [:channel-adapters :count])))
    (is (= 0 (get-in (core/health-check system) [:orchestrator :agent-count])))))

(deftest prepare-runner-options-adds-container-child-defaults
  (let [system {:config {:storage {:sqlite {:path "data/agent.db"}}
                         :runners {:docker {:image "clojure:temurin-21-alpine"
                                            :container-working-dir "/workspace"
                                            :container-data-dir "/agent-data"
                                            :container-home-dir "/root"
                                            :host-working-dir "."
                                            :share-network? false}}}}
        prepared (#'agent.core/prepare-runner-options
                  system
                  {:substrate "docker"
                   :runner-options {}})]
    (is (= "clojure:temurin-21-alpine" (:image prepared)))
    (is (= ["clojure" "-M" "-m" "agent.runtime.child"] (:command prepared)))
    (is (= "/workspace" (:container-working-dir prepared)))
    (is (= "/root" (:container-home-dir prepared)))
    (is (= "/agent-data/agent.db" (get (:env prepared) "AGENT_SQLITE_PATH")))
    (is (= "/root" (get (:env prepared) "HOME")))
    (is (<= 2 (count (:mounts prepared))))
    (is (every? #{:rw :ro} (map :mode (:mounts prepared))))
    (is (contains? (set (map :target (:mounts prepared))) "/workspace"))
    (is (contains? (set (map :target (:mounts prepared))) "/agent-data"))))

(deftest spawn-task-worker-produces-scoped-worker
  (let [system (core/create-system)
        worker (core/spawn-task-worker! system
                                        {:task {:id "task-1" :prompt "collect facts"}
                                         :name "Fact Worker"
                                         :capability-bundle {:capabilities ["research"]
                                                             :tool-access ["http" "fs"]}
                                         :memory-scopes ["session"]
                                         :budgets {:max_tokens 1000}})]
    (is (= "worker" (:kind worker)))
    (is (= ["research"] (:capabilities worker)))
    (is (= ["fs" "http"] (sort (:tool-access worker))))
    (is (= ["session"] (:memory-scopes worker)))
    (is (= {:max_tokens 1000} (:budgets worker)))))

(deftest execute-step-produces-receipts
  (let [system (core/create-system)
        orchestrator (core/spawn-agent! system {:name "Planner" :kind "orchestrator" :role "orchestrator"})
        step (agent.kernel/orchestrator-spawn-worker-step
              {:task {:id "task-2"}
               :worker-name "Exec Worker"
               :capability-bundle {:capabilities ["execute"]
                                   :tool-access ["http"]}})
        executed (core/execute-step! system (:id orchestrator) step)]
    (is (= 2 (count (:receipts executed))))
    (is (= :ok (get-in executed [:receipts 0 :status])))
    (is (= :deferred (get-in executed [:receipts 1 :status])))))
