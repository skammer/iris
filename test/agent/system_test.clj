(ns agent.system-test
  (:require
   [agent.system :as system]
   [agent.config :as config]
   [agent.health :as health]
   [agent.kernel]
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.core :as runtime]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-system-" ".db")))

(deftest create-llm-provider-selects-ollama
  (let [provider (system/create-llm-provider (:llm config/default-config))]
    (is (instance? agent.llm.providers.ollama.OllamaProvider provider))))

(deftest create-llm-provider-selects-openrouter
  (let [provider (system/create-llm-provider
                  {:active-provider :openrouter
                   :providers {:openrouter {:type :openrouter
                                            :model "openai/gpt-4o-mini"
                                            :site-url "https://example.com"
                                            :app-name "iris-test"
                                            :base-url "https://openrouter.ai/api/v1"
                                            :max-tokens 2048
                                            :api-key "or-key"}}})]
    (is (instance? agent.llm.providers.openai_compatible.OpenAICompatibleProvider provider))
    (is (= 2048 (get-in (llm-core/get-config provider) [:config :max-tokens])))))

(deftest create-llm-provider-keeps-openai-compatible-request-defaults
  (let [provider (system/create-llm-provider
                  {:active-provider :neuraldeep
                   :providers {:neuraldeep {:type :openai-compatible
                                            :model "qwen3.6-35b-a3b"
                                            :base-url "https://api.neuraldeep.ru/v1"
                                            :api-key "nd-key"
                                            :max-tokens 6384
                                            :extra-body {:chat_template_kwargs
                                                         {:enable_thinking false}}}}})
        provider-config (llm-core/get-config provider)]
    (is (= "qwen3.6-35b-a3b" (:default-model provider-config)))
    (is (= 6384 (get-in provider-config [:config :max-tokens])))
    (is (= {:chat_template_kwargs {:enable_thinking false}}
           (get-in provider-config [:config :extra-body])))))

(deftest create-system-registers-default-tools
  (let [system (system/create-system)
        tools (system/list-tools system)
        tool-names (set (map :name tools))
        adapters (system/list-channel-adapters system)
        runner-keys (-> system :runner-registry keys set)
        system-health (system/health-check system)]
    (is (every? tool-names [:fs :http :memory :message_search :shell :system_reload]))
    (is (= ["Discord" "Slack" "Telegram"] (mapv :display-name adapters)))
    (is (contains? runner-keys :local-unsandboxed))
    (is (contains? runner-keys :bubblewrap))
    (is (contains? runner-keys :docker))
    (is (contains? runner-keys :podman))
    (is (contains? runner-keys :seatbelt))
    (is (empty? (system/list-skills system)))
    (is (= 5 (count (system/memory-surfaces system))))
    (is (false? (get-in system-health [:logging :enabled])))
    (is (= :local (get-in system-health [:broker :backend])))
    (is (<= 6 (get-in system-health [:tools :count])))
    (is (integer? (get-in system-health [:runtime :run-count])))
    (is (= 3 (get-in system-health [:channel-adapters :count])))
    (is (= 0 (get-in system-health [:orchestrator :agent-count])))
    (is (= "ok" (get-in system-health [:health-snapshot :components "sqlite" :status])))
    (is (= "ok" (get-in system-health [:health-snapshot :components "runtime" :status])))))

(deftest retry-run-bumps-runtime-restart-count-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        registry (health/create-registry)
        service (system/create-runtime-service store (fn [_] nil))
        system {:runtime-service service
                :health-registry registry}
        run (system/request-run! system
                                 (runtime/create-run-request
                                  {:name "restart-count-test"}))]
    (try
      (system/retry-run! system (:id run))
      (is (= 1 (get-in (health/snapshot registry)
                       [:components "runtime" :restart-count])))
      (is (= "ok" (get-in (health/snapshot registry)
                          [:components "runtime" :status])))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest tool-policy-blocks-and-yolo-skips-approval-only
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        blocked-registry (system/create-tool-registry
                          (assoc-in (:tools config/default-config) [:policy :blocklist] [:fs])
                          #(swap! events conj %)
                          store)
        yolo-registry (system/create-tool-registry
                       (assoc (:tools config/default-config) :yolo? true)
                       #(swap! events conj %)
                       store)
        yolo-blocked-registry (system/create-tool-registry
                               (-> (:tools config/default-config)
                                   (assoc :yolo? true)
                                   (assoc-in [:policy :blocklist] [:shell]))
                               #(swap! events conj %)
                               store)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"startup policy"
                            (system/execute-tool {:tool-registry blocked-registry
                                                  :config {:tools (:tools config/default-config)}}
                                                 :fs
                                                 {:action "list" :path "."}
                                                 {:permissions #{:filesystem-read}})))
      (is (= "hi"
             (:stdout (system/execute-tool {:tool-registry yolo-registry
                                            :config {:tools (assoc (:tools config/default-config) :yolo? true)}}
                                           :shell
                                           {:argv ["printf" "hi"]}
                                           {:permissions #{:shell-exec}}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"startup policy"
                            (system/execute-tool {:tool-registry yolo-blocked-registry
                                                  :config {:tools (-> (:tools config/default-config)
                                                                     (assoc :yolo? true)
                                                                     (assoc-in [:policy :blocklist] [:shell]))}}
                                                 :shell
                                                 {:argv ["printf" "hi"]}
                                                 {:permissions #{:shell-exec}})))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest tool-policy-enforces-scope-and-approval-test
  (let [path (temp-db-path)
        target "tool-policy-scope-test.txt"
        store (sqlite/create-store {:path path})
        scoped-tools (assoc-in (:tools config/default-config)
                               [:policy :tool-scopes]
                               {:fs [:workspace]})
        scoped-registry (system/create-tool-registry scoped-tools (constantly nil) store)
        write-registry (system/create-tool-registry (:tools config/default-config)
                                                   (constantly nil)
                                                   store)]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tool scope missing"
                            (system/execute-tool {:tool-registry scoped-registry
                                                  :config {:tools scoped-tools}}
                                                 :fs
                                                 {:action "list" :path "."}
                                                 {:permissions #{:filesystem-read}})))
      (is (vector?
           (:entries (system/execute-tool {:tool-registry scoped-registry
                                           :config {:tools scoped-tools}}
                                          :fs
                                          {:action "list" :path "."}
                                          {:permissions #{:filesystem-read}
                                           :tool-scopes [:workspace]}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"approved request"
                            (system/execute-tool {:tool-registry write-registry
                                                  :config {:tools (:tools config/default-config)}}
                                                 :fs
                                                 {:action "write"
                                                  :path target
                                                  :content "blocked"}
                                                 {:permissions #{:filesystem-write}})))
      (is (:written (system/execute-tool {:tool-registry write-registry
                                          :config {:tools (assoc (:tools config/default-config) :yolo? true)}}
                                         :fs
                                         {:action "write"
                                          :path target
                                          :content "allowed"}
                                         {:permissions #{:filesystem-write}})))
      (is (= "allowed" (slurp target)))
      (finally
        (io/delete-file target true)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest prepare-runner-options-adds-container-child-defaults
  (let [system {:config {:storage {:sqlite {:path "data/agent.db"}}
                         :api {:port 8689}
                         :runners {:docker {:image "clojure:temurin-21-alpine"
                                            :container-working-dir "/workspace"
                                            :container-data-dir "/agent-data"
                                            :container-home-dir "/root"
                                            :host-working-dir "."
                                            :share-network? true}}}}
        prepared (#'agent.system/prepare-runner-options
                  system
                  {:substrate "docker"
                   :runner-options {}})]
    (is (= "clojure:temurin-21-alpine" (:image prepared)))
    (is (= ["clojure" "-M" "-m" "agent.runtime.child"] (:command prepared)))
    (is (= "/workspace" (:container-working-dir prepared)))
    (is (= "/tmp/iris/home" (:container-home-dir prepared)))
    (is (= "65532:65532" (:user prepared)))
    (is (= "http://host.docker.internal:8689" (get (:env prepared) "AGENT_CONTROL_URL")))
    (is (= "/agent-data/child.db" (get (:env prepared) "AGENT_CHILD_SQLITE_PATH")))
    (is (= "/tmp/iris/home" (get (:env prepared) "HOME")))
    (is (<= 1 (count (:mounts prepared))))
    (is (every? #{:rw :ro} (map :mode (:mounts prepared))))
    (is (contains? (set (map :target (:mounts prepared))) "/workspace"))
    (is (not (contains? (set (map :target (:mounts prepared))) "/agent-data")))))

(deftest spawn-task-worker-produces-scoped-worker
  (let [system (system/create-system)
        worker (system/spawn-task-worker! system
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
  (let [system (system/create-system)
        orchestrator (system/spawn-agent! system {:name "Planner" :kind "orchestrator" :role "orchestrator"})
        step (agent.kernel/orchestrator-spawn-worker-step
              {:task {:id "task-2"}
               :worker-name "Exec Worker"
               :capability-bundle {:capabilities ["execute"]
                                   :tool-access ["http"]}})
        executed (system/execute-step! system (:id orchestrator) step)]
    (is (= 2 (count (:receipts executed))))
    (is (= :ok (get-in executed [:receipts 0 :status])))
    (is (= :deferred (get-in executed [:receipts 1 :status])))))
