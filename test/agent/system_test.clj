(ns agent.system-test
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.api :as api]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.health :as health]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.memory.core :as memory]
   [agent.memory.idle :as memory-idle]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.persistence.sqlite :as sqlite]
   [agent.restart-handoff :as restart-handoff]
   [agent.skills :as skills]
   [agent.system :as system]
   [agent.system.components :as components]
   [agent.system.health :as system-health]
   [agent.telegram :as telegram]
   [agent.tools.service :as tool-service]
   [agent.cron.service :as cron]
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
                  {:active-provider :example-provider
                   :providers {:example-provider {:type :openai-compatible
                                            :model "example-model"
                                            :base-url "https://api.example.test/v1"
                                            :api-key "test-key"
                                            :max-tokens 6384
                                            :extra-body {:chat_template_kwargs
                                                         {:enable_thinking false}}}}})]
    (is (= "example-model" (:default-model provider)))
    (is (= 6384 (get-in provider [:config :max-tokens])))
    (is (= {:chat_template_kwargs {:enable_thinking false}}
           (get-in provider [:config :extra-body])))))

(deftest create-system-registers-default-tools
  (let [system (system/create-system)
        tools (tool-service/list-tools system)
        tool-names (set (map :name tools))
        adapters (channel-adapters/list-adapters (:channel-adapter-registry system))
        system-health (system-health/health-check system)]
	    (is (every? tool-names [:fs_read :fs_write :fs_create :fs_replace :fs_list :fs_search
	                            :fs_delete :fs_mkdir :http
	                            :memory_recall
	                            :vault_search
	                            :scratchpad_read :scratchpad_search
	                            :scratchpad_replace
	                            :memory_extract_session
	                            :message_search :message_get :skills_list :skills_read :shell
	                            :system_handoff :system_reload
	                            :todo_write :todo_get :todo_list :todo_search]))
    (is (= ["Telegram"] (mapv :display-name adapters)))
    (is (every? (set (mapv :name (skills/list-skills (:skills-registry system))))
                ["memory-vault" "dream" "distill" "iris-config"]))
	    (is (= 2 (count (memory/list-surfaces system))))
    (is (false? (get-in system-health [:logging :enabled])))
    (is (= :local (get-in system-health [:broker :backend])))
    (is (<= 7 (get-in system-health [:tools :count])))
    (is (= 1 (get-in system-health [:channel-adapters :count])))
    (is (= "ok" (get-in system-health [:health-snapshot :components "sqlite" :status])))
    (is (contains? (get-in system-health [:health-snapshot :components]) "runtime"))))

(deftest soft-reload-refreshes-llm-without-cancelling-chat-queue
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
                    :telegram-service ::old-telegram
                    :channel-adapter-registry ::old-channel-registry}]
    (reset! system-ref old-system)
    (with-redefs [config/load-config (fn [_] new-cfg)
                  llm-registry/create-registry (fn [llm-cfg] {:fresh? true :llm-cfg llm-cfg})
                  components/create-memory-service (fn [& _] ::memory)
                  components/create-observer (fn [& _] ::observer)
                  components/create-trace (fn [& _] ::trace)
                  components/create-skills-registry (fn [_] ::skills)
                  llm-service/create-llm-provider (fn [_] ::llm)
                  llm-service/create-note-llm-provider (fn [_] ::note-llm)
                  tool-service/create-tool-registry (fn [& _] ::tools)
                  telegram/create-service (fn [_] (throw (ex-info "Telegram must not restart" {})))
                  components/create-channel-adapter-registry (fn [& _] (throw (ex-info "Telegram registry must be reused" {})))
                  chat/create-service (fn [] (throw (ex-info "Chat queue must be reused" {})))
                  chat/stop! (fn [service] (swap! stopped conj service))]
      (let [result (system/reload! old-system {:mode :soft})
            new-system @system-ref]
        (is (= :reloaded (:status result)))
        (is (= {:fresh? true :llm-cfg (config/llm-config new-cfg)}
               (:llm-registry new-system)))
        (is (= ::old-telegram (:telegram-service new-system)))
        (is (= ::old-channel-registry (:channel-adapter-registry new-system)))
        (is (= ::old-chat (:chat-service new-system)))
        (is (empty? @stopped))
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

(deftest full-reload-validates-before-scheduling-test
  (let [system-ref (atom nil)
        reload-state (atom {:status :idle})
        health-registry (health/create-registry)
        system* {:config config/default-config
                 :config-path "broken.edn"
                 :system-ref system-ref
                 :reload-state reload-state
                 :health-registry health-registry}]
    (reset! system-ref system*)
    (with-redefs [config/validate-effective-config
                  (fn [_]
                    (throw (ex-info "invalid provider config"
                                    {:type :config-invalid})))
                  components/create-system-components
                  (fn [& _]
                    (throw (ex-info "must not build" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invalid provider config"
                            (system/reload! system* {:mode :full :source "test"})))
      (is (= :failed (:status @reload-state)))
      (is (= "invalid provider config" (:message @reload-state))))))

(deftest api-start-dispatches-pending-restart-handoffs-test
  (let [system-ref (atom nil)
        dispatched (promise)
        system* {:config (assoc config/default-config :api {:host "127.0.0.1" :port 0})
                 :system-ref system-ref
                 :health-registry (health/create-registry)
                 :telegram-service nil
                 :memory-idle-service ::memory-idle
                 :memory-magi-review-service ::memory-review
                 :cron-service ::cron}]
    (with-redefs [api/start-server! (fn [& _] ::api)
                  memory-idle/start! (constantly nil)
                  memory-magi-review/start! (constantly nil)
                  cron/start! (constantly nil)
                  restart-handoff/dispatch-pending! (fn [started-system]
                                                      (deliver dispatched started-system)
                                                      0)]
      (let [started (system/start-api! system*)]
        (is (= ::api (:api-server started)))
        (is (= started @system-ref))
        (is (= started (deref dispatched 1000 ::timeout)))))))

(deftest system-handoff-tool-persists-current-session-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "tool handoff")
        reload-calls (atom [])
        system-ref (atom nil)
        control {:system-ref system-ref
                 :reload! (fn [_ opts]
                            (swap! reload-calls conj opts)
                            {:status :scheduled :mode (:mode opts)})}
        registry (tool-service/create-tool-registry
                  {:cfg (:tools config/default-config)
                   :event-sink (constantly nil)
                   :store store
                   :system-control control})
        system* {:store store
                 :tool-registry registry
                 :config {:tools (assoc (:tools config/default-config) :yolo? true)}}
        context {:session-id (:id session)
                 :permission-profile :admin
                 :permissions #{:system-reload}
                 :yolo? true}]
    (reset! system-ref system*)
    (try
      (let [scheduled (tool-service/execute-tool system* :system_handoff
                                                 {:message "restart externally"}
                                                 context)]
        (is (= :scheduled (:status scheduled)))
        (is (= "restart externally"
               (:message (sqlite/get-session-restart-handoff store (:id session))))))
      (let [reloaded (tool-service/execute-tool system* :system_reload
                                                {:mode "full"
                                                 :resume_message "verify new runtime"}
                                                context)]
        (is (= :scheduled (:status reloaded)))
        (is (string? (:handoff-id reloaded)))
        (is (= "verify new runtime"
               (:message (sqlite/get-session-restart-handoff store (:id session)))))
        (is (= [{:mode :full :source "system"}] @reload-calls)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires mode=full"
                            (tool-service/execute-tool system* :system_reload
                                                       {:mode "soft"
                                                        :resume_message "later"}
                                                       context)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

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

(deftest filesystem-tools-include-vault-roots-test
  (let [path (temp-db-path)
        fs-root (.toFile (java.nio.file.Files/createTempDirectory
                          "iris-fs-root-"
                          (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-root (.toFile (java.nio.file.Files/createTempDirectory
                             "iris-vault-root-"
                             (make-array java.nio.file.attribute.FileAttribute 0)))
        vault-note (io/file vault-root "notes/example.md")
        store (sqlite/create-store {:path path})
        memory-service (memory/create-memory-service
                        {:search {:default-limit 10}
                         :vault {:paths [(.getAbsolutePath vault-root)]
                                 :writable? true}}
                        store)
        registry (tool-service/create-tool-registry
                  {:cfg (assoc-in (:tools config/default-config)
                                  [:fs :roots]
                                  [(.getAbsolutePath fs-root)])
                   :event-sink (constantly nil)
                   :store store
                   :memory-service memory-service})]
    (try
      (.mkdirs (.getParentFile vault-note))
      (spit vault-note "before")
      (is (= "before"
             (:content (tool-service/execute-tool {:tool-registry registry
                                                   :config {:tools (:tools config/default-config)}}
                                                  :fs_read
                                                  {:path (.getAbsolutePath vault-note)}
                                                  {:permissions #{:filesystem-read}}))))
      (is (:replaced (tool-service/execute-tool {:tool-registry registry
                                                 :config {:tools (assoc (:tools config/default-config) :yolo? true)}}
                                                :fs_replace
                                                {:path (.getAbsolutePath vault-note)
                                                 :old-string "before"
                                                 :new-string "after"}
                                                {:permissions #{:filesystem-write}})))
      (is (= "after" (slurp vault-note)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file fs-root true)
        (io/delete-file vault-root true)
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
