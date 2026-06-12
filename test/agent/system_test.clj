(ns agent.system-test
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.api :as api]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.health :as health]
   [agent.kernel]
   [agent.kernel.service :as kernel-service]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.skills :as skills]
   [agent.system :as system]
   [agent.system.components :as components]
   [agent.system.health :as system-health]
   [agent.telegram :as telegram]
   [agent.tools.service :as tool-service]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-system-" ".db")))

(deftest create-llm-provider-selects-ollama
  (let [provider (llm-service/create-llm-provider (:llm config/default-config))]
    (is (instance? agent.llm.providers.ollama.OllamaProvider provider))))

(deftest create-llm-provider-selects-openrouter
  (let [provider (llm-service/create-llm-provider
                  {:active-provider :openrouter
                   :providers {:openrouter {:type :openrouter
                                            :model "openai/gpt-4o-mini"
                                            :site-url "https://example.com"
                                            :app-name "iris-test"
                                            :base-url "https://openrouter.ai/api/v1"
                                            :max-tokens 2048
                                            :api-key "or-key"}}})]
    (is (instance? agent.llm.providers.openai_compatible.OpenAICompatibleProvider provider))
    (is (= 2048 (get-in provider [:config :max-tokens])))))

(deftest create-llm-provider-keeps-openai-compatible-request-defaults
  (let [provider (llm-service/create-llm-provider
                  {:active-provider :neuraldeep
                   :providers {:neuraldeep {:type :openai-compatible
                                            :model "qwen3.6-35b-a3b"
                                            :base-url "https://api.example.invalid/v1"
                                            :api-key "nd-key"
                                            :max-tokens 6384
                                            :extra-body {:chat_template_kwargs
                                                         {:enable_thinking false}}}}})]
    (is (= "qwen3.6-35b-a3b" (:default-model provider)))
    (is (= 6384 (get-in provider [:config :max-tokens])))
    (is (= {:chat_template_kwargs {:enable_thinking false}}
           (get-in provider [:config :extra-body])))))

(deftest create-system-registers-default-tools
  (let [system (system/create-system)
        tools (tool-service/list-tools system)
        tool-names (set (map :name tools))
        adapters (channel-adapters/list-adapters (:channel-adapter-registry system))
        system-health (system-health/health-check system)]
    (is (every? tool-names [:fs_read :fs_write :fs_create :fs_replace :fs_list
	                            :fs_delete :fs_mkdir :http
	                            :memory_search :memory_save_fact :memory_remove_fact
	                            :memory_read_vault :memory_write_vault
	                            :message_search :shell :system_reload
                            :todo_write :todo_get :todo_list :todo_search]))
    (is (= ["Telegram"] (mapv :display-name adapters)))
    (is (empty? (skills/list-skills system)))
	    (is (= 4 (count (memory/list-surfaces system))))
    (is (false? (get-in system-health [:logging :enabled])))
    (is (= :local (get-in system-health [:broker :backend])))
    (is (<= 7 (get-in system-health [:tools :count])))
    (is (= 1 (get-in system-health [:channel-adapters :count])))
    (is (= 0 (get-in system-health [:orchestrator :agent-count])))
    (is (= "ok" (get-in system-health [:health-snapshot :components "sqlite" :status])))
    (is (contains? (get-in system-health [:health-snapshot :components]) "runtime"))))

(deftest soft-reload-refreshes-llm-registry-and-telegram-system
  (let [events (atom [])
        stopped (atom [])
        health-registry (health/create-registry)
        old-cfg config/default-config
        new-cfg (assoc-in old-cfg [:llm :providers :ollama :model] "fresh-model")
        system-ref (atom nil)
        reload-state (atom {:status :idle})
        old-system {:config old-cfg
                    :config-path "test-config.edn"
                    :system-ref system-ref
                    :reload-state reload-state
                    :system-control {:reload! :reload}
                    :health-registry health-registry
                    :store ::store
                    :telemetry ::telemetry
                    :event-sink #(swap! events conj %)
                    :chat-service ::old-chat
                    :telegram-service nil}]
    (reset! system-ref old-system)
    (with-redefs [config/load-config (fn [_] new-cfg)
                  llm-registry/create-registry (fn [llm-cfg] {:fresh? true :llm-cfg llm-cfg})
                  components/create-memory-service (fn [& _] ::memory)
                  components/create-observer (fn [& _] ::observer)
                  components/create-trace (fn [& _] ::trace)
                  components/create-skills-registry (fn [_] ::skills)
                  llm-service/create-llm-provider (fn [_] ::llm)
                  llm-service/create-fact-llm-provider (fn [_] ::fact-llm)
                  tool-service/create-tool-registry (fn [& _] ::tools)
                  telegram/create-service (fn [system] {:telegram-system system})
                  components/create-channel-adapter-registry (fn [_ service] {:service service})
                  chat/create-service (fn [] ::new-chat)
                  chat/stop! (fn [service] (swap! stopped conj service))]
      (let [result (system/reload! old-system {:mode :soft})
            new-system @system-ref]
        (is (= :reloaded (:status result)))
        (is (= {:fresh? true :llm-cfg (config/llm-config new-cfg)}
               (:llm-registry new-system)))
        (is (= new-cfg (:config (get-in new-system [:telegram-service :telegram-system]))))
        (is (= ::tools (get-in new-system [:telegram-service :telegram-system :tool-registry])))
        (is (= [::old-chat] @stopped))
        (is (= :system.config.reloaded (:event-type (last @events))))))))

(deftest full-reload-stops-old-api-before-starting-new-api
  (let [order (atom [])
        events (atom [])
        health-registry (health/create-registry)
        system-ref (atom nil)
        reload-state (atom {:status :idle})
        old-system {:config config/default-config
                    :config-path "test-config.edn"
                    :system-ref system-ref
                    :reload-state reload-state
                    :system-control {:reload! :reload}
                    :health-registry health-registry
                    :store ::old-store
                    :telemetry ::old-telemetry
                    :event-sink #(swap! events conj %)
                    :chat-service ::old-chat
                    :api-server ::old-api
                    :telegram-service nil}
        new-base {:config config/default-config
                  :config-path "test-config.edn"
                  :store ::new-store
                  :telemetry ::new-telemetry
                  :event-sink #(swap! events conj %)
                  :memory-service ::new-memory
                  :observer ::new-observer
                  :trace ::new-trace
                  :chat-service ::new-chat}]
    (reset! system-ref old-system)
    (with-redefs [components/create-system-components
                  (fn [_config-path sys-ref rel-state h-reg control]
                    (components/attach-telegram-service
                     (-> new-base
                         (assoc :system-ref sys-ref
                                :reload-state rel-state
                                :health-registry h-reg
                                :system-control control
                                :tool-registry ::new-tools))))
                  system/start-api! (fn [new-system]
                                      (swap! order conj :start-new-api)
                                      (assoc new-system :api-server ::new-api))
                  api/stop-server! (fn [_] (swap! order conj :stop-old-api))
                  chat/stop! (fn [_] (swap! order conj :stop-old-chat))
                  sqlite/close-store! (fn [_] (swap! order conj :close-old-store))
                  telegram/create-service (fn [system] {:telegram-system system})
                  components/create-channel-adapter-registry (fn [_ service] {:service service})]
      (let [result (#'system/full-reload-now! old-system {:source "test"})
            new-system @system-ref]
        (is (= :reloaded (:status result)))
        (is (= [:stop-old-chat :stop-old-api :start-new-api :close-old-store] @order))
        (is (= ::new-api (:api-server new-system)))
        (is (= ::new-tools (:tool-registry new-system)))
        (is (= system-ref (:system-ref new-system)))
        (is (= ::new-tools (get-in new-system [:telegram-service :telegram-system :tool-registry])))
        (is (= :system.config.reloaded (:event-type (last @events))))))))

(deftest tool-policy-blocks-and-yolo-skips-approval-only
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        events (atom [])
        blocked-registry (tool-service/create-tool-registry
                          {:cfg (assoc-in (:tools config/default-config) [:policy :blocklist] [:fs])
                           :event-sink #(swap! events conj %)
                           :store store})
        yolo-registry (tool-service/create-tool-registry
                       {:cfg (assoc (:tools config/default-config) :yolo? true)
                        :event-sink #(swap! events conj %)
                        :store store})
        yolo-blocked-registry (tool-service/create-tool-registry
                               {:cfg (-> (:tools config/default-config)
                                         (assoc :yolo? true)
                                         (assoc-in [:policy :blocklist] [:shell]))
                                :event-sink #(swap! events conj %)
                                :store store})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"startup policy"
                            (tool-service/execute-tool {:tool-registry blocked-registry
                                                  :config {:tools (:tools config/default-config)}}
                                                 :fs_list
                                                 {:path "."}
                                                 {:permissions #{:filesystem-read}})))
      (is (= "hi"
             (:stdout (tool-service/execute-tool {:tool-registry yolo-registry
                                            :config {:tools (assoc (:tools config/default-config) :yolo? true)}}
                                           :shell
                                           {:argv ["printf" "hi"]}
                                           {:permissions #{:shell-exec}}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"startup policy"
                            (tool-service/execute-tool {:tool-registry yolo-blocked-registry
                                                  :config {:tools (-> (:tools config/default-config)
                                                                     (assoc :yolo? true)
                                                                     (assoc-in [:policy :blocklist] [:shell]))}}
                                                 :shell
                                                 {:argv ["printf" "hi"]}
                                                 {:permissions #{:shell-exec}})))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest agent-tool-context-ignores-caller-security-overrides
  (let [path (temp-db-path)
        target "agent-tool-context-security-test.txt"
        store (sqlite/create-store {:path path})
        system (-> (system/create-system)
                   (assoc :store store
                          :config config/default-config
                          :tool-registry (tool-service/create-tool-registry
                                          {:cfg (:tools config/default-config)
                                           :event-sink (constantly nil)
                                           :store store}))
                   (assoc-in [:orchestrator :enabled?] true))
        agent (orchestrator/spawn-agent! (:orchestrator system)
                                         {:name "Worker"
                                          :kind "worker"
                                          :role "worker"
                                          :tool-access ["fs"]})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"approved request"
                            (tool-service/execute-agent-tool!
                             system
                             (:id agent)
                             :fs_write
                             {:path target
                              :content "blocked"}
                             {:permissions #{:filesystem-write}
                              :yolo? true})))
      (is (not (.exists (io/file target))))
      (finally
        (io/delete-file target true)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest tool-policy-enforces-scope-and-approval-test
  (let [path (temp-db-path)
        target "tool-policy-scope-test.txt"
        store (sqlite/create-store {:path path})
        scoped-tools (assoc-in (:tools config/default-config)
                               [:policy :tool-scopes]
                               {:fs [:workspace]})
        scoped-registry (tool-service/create-tool-registry
                         {:cfg scoped-tools :event-sink (constantly nil) :store store})
        write-registry (tool-service/create-tool-registry
                        {:cfg (:tools config/default-config)
                         :event-sink (constantly nil)
                         :store store})]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Tool scope missing"
                            (tool-service/execute-tool {:tool-registry scoped-registry
                                                  :config {:tools scoped-tools}}
                                                 :fs_list
                                                 {:path "."}
                                                 {:permissions #{:filesystem-read}})))
      (is (vector?
           (:entries (tool-service/execute-tool {:tool-registry scoped-registry
                                           :config {:tools scoped-tools}}
                                          :fs_list
                                          {:path "."}
                                          {:permissions #{:filesystem-read}
                                           :tool-scopes [:workspace]}))))
      (doseq [[tool-name input] [[:fs_write {:path target :content "blocked"}]
                                 [:fs_create {:path target :content "blocked"}]
                                 [:fs_replace {:path target :old-string "old" :new-string "new"}]]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"approved request"
                              (tool-service/execute-tool {:tool-registry write-registry
                                                    :config {:tools (:tools config/default-config)}}
                                                   tool-name
                                                   input
                                                   {:permissions #{:filesystem-write}}))))
      (is (:written (tool-service/execute-tool {:tool-registry write-registry
                                          :config {:tools (assoc (:tools config/default-config) :yolo? true)}}
                                         :fs_write
                                         {:path target
                                          :content "allowed"}
                                         {:permissions #{:filesystem-write}})))
      (is (= "allowed" (slurp target)))
      (finally
        (io/delete-file target true)
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest spawn-task-worker-produces-scoped-worker
  (let [system (assoc-in (system/create-system) [:orchestrator :enabled?] true)
        worker (kernel-service/spawn-task-worker! system
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
  (let [system (assoc-in (system/create-system) [:orchestrator :enabled?] true)
        orchestrator (orchestrator/spawn-agent! (:orchestrator system)
                                                {:name "Planner"
                                                 :kind "orchestrator"
                                                 :role "orchestrator"})
        step (agent.kernel/orchestrator-spawn-worker-step
              {:task {:id "task-2"}
               :worker-name "Exec Worker"
               :capability-bundle {:capabilities ["execute"]
                                   :tool-access ["http"]}})
        executed (kernel-service/execute-step! system (:id orchestrator) step)]
    (is (= 2 (count (:receipts executed))))
    (is (= :ok (get-in executed [:receipts 0 :status])))
    (is (= :deferred (get-in executed [:receipts 1 :status])))))

(deftest disabled-orchestrator-kernel-mutators-return-unsupported
  (let [system (system/create-system)
        spawn (kernel-service/execute-directive!
               system
               "agent-disabled"
               (agent.kernel/directive :spawn-worker {:task {:id "blocked"}}))
        complete (kernel-service/execute-directive!
                  system
                  "agent-disabled"
                  (agent.kernel/directive :complete {:result {:ok true}}))]
    (is (= :unsupported (:status spawn)))
    (is (= :completed (:status complete)))))
