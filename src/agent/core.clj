(ns agent.core
  "Canonical composition root for the rewrite."
  (:gen-class)
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local-broker]
   [agent.channels.core :as channel-adapters]
   [agent.api :as api]
   [agent.config :as config]
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.bubblewrap :as bubblewrap]
   [agent.runners.docker-podman :as docker-podman]
   [agent.runners.core :as runners]
   [agent.runners.local-process :as local-process]
   [agent.runners.seatbelt :as seatbelt]
   [agent.runtime.child :as runtime-child]
   [agent.runtime.core :as runtime]
   [agent.skills :as skills]
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.common.http :as http-tool]
   [agent.tools.common.shell :as shell-tool]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn create-llm-provider
  [cfg]
  (let [{:keys [provider model site-url app-name openrouter ollama openai-compatible]} cfg]
    (case provider
      :ollama
      (ollama/create-ollama-provider
       {:base-url (get ollama :base-url)
        :default-model model
        :embedding-model (get ollama :embedding-model)
        :keep-alive (get ollama :keep-alive)})

      :openrouter
      (openai-compatible/create-openrouter-provider
       {:api-key (get openrouter :api-key)
        :base-url (get openrouter :base-url)
        :model model
        :site-url site-url
        :app-name app-name})

      :openai-compatible
      (openai-compatible/create-openai-compatible-provider
       {:api-key (get openai-compatible :api-key)
        :base-url (get openai-compatible :base-url)
        :default-model model
        :site-url site-url
        :app-name app-name})

      (throw (ex-info (str "Unsupported provider: " provider)
                      {:provider provider})))))

(defn create-store
  [cfg]
  (sqlite/create-store (get cfg :sqlite)))

(defn create-broker
  []
  (local-broker/create-broker))

(defn create-event-bus
  []
  (create-broker))

(defn create-event-sink
  [store broker-instance]
  (fn [event]
    (let [recorded (sqlite/log-event! store event)]
      (doseq [message (broker/event->messages recorded)]
        (broker/publish! broker-instance message))
      recorded)))

(defn subscribe-events
  ([system] (subscribe-events system (broker/all-events-subject)))
  ([system pattern]
   (broker/subscribe! (:broker system) pattern)))

(defn unsubscribe-events
  [system subscription]
  (broker/unsubscribe! (:broker system) subscription))

(defn create-tool-registry
  [cfg event-sink store]
  (let [http-cfg (get cfg :http)
        fs-cfg (get cfg :fs)
        shell-cfg (get cfg :shell)
        registry (tools/create-registry
                  {:event-sink event-sink
                   :before-execute (tool-approvals/create-policy-hook store)
                   :after-execute (fn [_] nil)})]
    (cond-> registry
      (not= false (:enabled http-cfg))
      (tools/register-tool (http-tool/create-http-tool http-cfg))

      (not= false (:enabled fs-cfg))
      (tools/register-tool (fs-tool/create-fs-tool fs-cfg))

      (not= false (:enabled shell-cfg))
      (tools/register-tool (shell-tool/create-shell-tool shell-cfg)))))

(defn create-orchestrator
  [_cfg event-sink]
  (orchestrator/create-orchestrator {:event-sink event-sink}))

(defn create-skills-registry
  [cfg]
  (skills/create-registry cfg))

(defn create-memory-service
  [cfg store]
  (memory/create-memory-service cfg store))

(defn create-runtime-service
  [store event-sink]
  (runtime/create-runtime-service {:store store
                                   :event-sink event-sink}))

(defn- runner-exit-status [run exit-code]
  (cond
    (contains? #{"cancelled" "completed" "failed"} (:status run)) nil
    (zero? exit-code) :completed
    :else :failed))

(defn- create-exit-aware-local-runner
  [runtime-service]
  (local-process/create-local-process-runner
   {:on-exit (fn [run-id {:keys [exit-code]}]
               (when-let [run (runtime/get-run runtime-service run-id)]
                 (when-let [status (runner-exit-status run exit-code)]
                   (runtime/transition-run! runtime-service
                                            run-id
                                            status
                                            {:last-error (when-not (zero? exit-code)
                                                            (str "Process exited with code " exit-code))
                                             :runner-metadata (assoc (:runner-metadata run)
                                                                     :exit-code exit-code)}))))
    :on-output (fn [run-id {:keys [stream line captured-at]}]
                 (runtime/log-run-output! runtime-service
                                          run-id
                                          {:stream stream
                                           :line line
                                           :captured-at captured-at}))}))

(defn create-runner-registry
  [runtime-service]
  {:local-process (create-exit-aware-local-runner runtime-service)
   :bubblewrap (bubblewrap/create-bubblewrap-runner
                {:delegate (create-exit-aware-local-runner runtime-service)})
   :docker (docker-podman/create-docker-podman-runner
            {:delegate (create-exit-aware-local-runner runtime-service)
             :engine-binary "docker"})
   :podman (docker-podman/create-docker-podman-runner
            {:delegate (create-exit-aware-local-runner runtime-service)
             :engine-binary "podman"})
   :seatbelt (seatbelt/create-seatbelt-runner
              {:delegate (create-exit-aware-local-runner runtime-service)})})

(defn create-channel-adapter-registry
  [cfg]
  (let [registry (channel-adapters/create-registry)
        specs [{:key :telegram
                :display-name "Telegram"
                :inbound-mode :polling
                :capabilities #{:supports-outbound :supports-streaming :supports-voice-ingest :supports-reactions :supports-location :supports-otp}}
               {:key :discord
                :display-name "Discord"
                :inbound-mode :gateway
                :capabilities #{:supports-outbound :supports-streaming :supports-interactive :supports-threads :supports-voice-ingest :supports-reactions}}
               {:key :slack
                :display-name "Slack"
                :inbound-mode :socket-mode
                :capabilities #{:supports-outbound :supports-streaming :supports-interactive :supports-threads :supports-reactions}}]]
    (reduce
     (fn [acc {:keys [key display-name inbound-mode capabilities]}]
       (channel-adapters/register-adapter
        acc
        (channel-adapters/create-adapter
         {:description
          (channel-adapters/create-adapter-description
           key
           display-name
           inbound-mode
           capabilities
           :public-url-required? false
           :config-schema {:enabled :boolean})
          :health-fn (fn []
                       {:healthy true
                        :enabled (true? (get-in cfg [key :enabled]))})})))
     registry
     specs)))

(defn create-system
  ([] (create-system nil))
  ([config-path]
   (let [cfg (config/load-config config-path)
         llm-cfg (config/llm-config cfg)
         store (create-store (:storage cfg))
         broker-instance (create-broker)
         event-sink (create-event-sink store broker-instance)
         runtime-service (create-runtime-service store event-sink)]
     {:config cfg
      :llm-provider (create-llm-provider llm-cfg)
      :store store
      :broker broker-instance
      :event-sink event-sink
      :tool-registry (create-tool-registry (:tools cfg) event-sink store)
      :skills-registry (create-skills-registry (:skills cfg))
      :memory-service (create-memory-service (:memory cfg) store)
      :runtime-service runtime-service
      :runner-registry (create-runner-registry runtime-service)
      :channel-adapter-registry (create-channel-adapter-registry (:channel-adapters cfg))
      :orchestrator (create-orchestrator (:orchestrator cfg) event-sink)})))

(defn complete
  ([system prompt]
   (complete system [{:role "user" :content prompt}] {}))
  ([system messages opts]
   (llm-core/complete (:llm-provider system) messages opts)))

(defn stream
  ([system prompt]
   (stream system [{:role "user" :content prompt}] {}))
  ([system messages opts]
   (llm-core/stream (:llm-provider system) messages opts)))

(defn embed
  [system text opts]
  (llm-core/embed (:llm-provider system) text opts))

(defn health-check
  [system]
  {:llm (llm-core/health-check (:llm-provider system))
   :storage (sqlite/health-check (:store system))
   :broker (broker/health-check (:broker system))
   :tools (tools/registry-health (:tool-registry system))
   :skills (skills/registry-health (:skills-registry system))
   :memory (memory/health-check (:memory-service system))
   :runtime (runtime/runtime-health (:runtime-service system))
   :channel-adapters (channel-adapters/registry-health (:channel-adapter-registry system))
   :orchestrator (orchestrator/health-check (:orchestrator system))
   :provider (get-in system [:config :llm :provider])})

(defn log-event!
  [system event]
  ((:event-sink system) event))

(defn- append-session-message!
  [system session-id role content]
  (let [message (sqlite/append-message! (:store system) session-id role content)]
    (log-event! system
                {:event-type :message.appended
                 :entity-type :session
                 :entity-id session-id
                 :payload {:role role
                           :content content}})
    message))

(defn- default-child-env
  [system]
  {"AGENT_SQLITE_PATH" (-> system :config :storage :sqlite :path io/file .getAbsolutePath)})

(defn- absolute-path [path]
  (.getAbsolutePath (io/file path)))

(defn- ensure-mount [mounts source target mode]
  (if (some #(and (= source (:source %)) (= target (:target %))) mounts)
    mounts
    (conj (vec mounts) {:source source :target target :mode mode})))

(defn- ensure-mount-if-exists [mounts source target mode]
  (if (.exists (io/file source))
    (ensure-mount mounts source target mode)
    mounts))

(defn- prepare-container-runner-options
  [system substrate runner-options]
  (let [runner-cfg (get-in system [:config :runners substrate] {})
        host-working-dir (absolute-path (or (:host-working-dir runner-options)
                                            (:working-dir runner-options)
                                            (:host-working-dir runner-cfg)
                                            "."))
        container-working-dir (or (:container-working-dir runner-options)
                                  (:container-working-dir runner-cfg)
                                  "/workspace")
        sqlite-host-path (-> system :config :storage :sqlite :path absolute-path)
        sqlite-file (io/file sqlite-host-path)
        sqlite-host-dir (.getAbsolutePath (.getParentFile sqlite-file))
        container-data-dir (or (:container-data-dir runner-options)
                               (:container-data-dir runner-cfg)
                               "/agent-data")
        container-home-dir (or (:container-home-dir runner-options)
                               (:container-home-dir runner-cfg)
                               "/root")
        host-m2-dir (absolute-path (str (System/getProperty "user.home") "/.m2"))
        container-sqlite-path (str container-data-dir "/" (.getName sqlite-file))
        mounts* (-> (vec (or (:mounts runner-options) []))
                    (ensure-mount host-working-dir container-working-dir :rw)
                    (ensure-mount sqlite-host-dir container-data-dir :rw)
                    (ensure-mount-if-exists host-m2-dir (str container-home-dir "/.m2") :rw))
        env* (merge {"AGENT_SQLITE_PATH" container-sqlite-path
                     "HOME" container-home-dir}
                    (or (:env runner-options) {}))]
    (cond-> (assoc runner-options
                   :image (or (:image runner-options)
                              (:image runner-cfg))
                   :mounts mounts*
                   :env env*
                   :host-working-dir host-working-dir
                   :container-working-dir container-working-dir
                   :container-home-dir container-home-dir
                   :container-data-dir container-data-dir
                   :share-network? (boolean (or (:share-network? runner-options)
                                                (:share-network? runner-cfg))))
      (not (seq (:command runner-options)))
      (assoc :command (runtime-child/current-container-child-command)))))

(defn- prepare-runner-options
  [system run]
  (let [runner-options (or (:runner-options run) {})]
    (cond-> runner-options
      (= "local-process" (:substrate run))
      ((fn [opts]
         (let [env* (merge (default-child-env system) (or (:env opts) {}))]
           (cond-> (assoc opts :env env*)
             (not (seq (:command opts)))
             (assoc :command (runtime-child/current-child-command)
                    :working-dir (or (:working-dir opts) "."))))))

      (#{"docker" "podman"} (:substrate run))
      ((fn [opts]
         (prepare-container-runner-options system (keyword (:substrate run)) opts))))))

(defn create-session!
  ([system] (create-session! system nil))
  ([system title]
   (let [session (sqlite/create-session! (:store system) title)]
     (log-event! system
                 {:event-type :session.created
                  :entity-type :session
                  :entity-id (:id session)
                  :payload {:title title}})
     session)))

(defn list-sessions
  [system]
  (sqlite/list-sessions (:store system)))

(defn session-exists?
  [system session-id]
  (sqlite/session-exists? (:store system) session-id))

(defn list-messages
  [system session-id]
  (sqlite/list-messages (:store system) session-id))

(defn list-tools
  [system]
  (tools/list-tools (:tool-registry system)))

(defn list-skills
  [system]
  (skills/list-skills (:skills-registry system)))

(defn list-channel-adapters
  [system]
  (channel-adapters/list-adapters (:channel-adapter-registry system)))

(defn list-events
  ([system] (list-events system {}))
  ([system opts]
   (sqlite/list-events (:store system) opts)))

(defn memory-surfaces
  [system]
  (memory/list-surfaces (:memory-service system)))

(defn read-prompt-memory
  [system]
  (memory/read-prompt-memory (:memory-service system)))

(defn search-memory
  ([system query] (search-memory system query {}))
  ([system query opts]
   (memory/search-memory (:memory-service system) query opts)))

(defn save-graph-fact!
  [system fact]
  (memory/save-graph-fact! (:memory-service system) fact))

(defn query-graph-memory
  ([system query] (query-graph-memory system query {}))
  ([system query opts]
   (memory/query-graph-memory (:memory-service system) query opts)))

(defn execute-tool
  ([system tool-name input]
   (execute-tool system tool-name input {}))
  ([system tool-name input context]
   (tools/execute-tool (:tool-registry system) tool-name input context)))

(defn request-run!
  [system request]
  (runtime/request-run! (:runtime-service system) request))

(defn list-runs
  ([system] (list-runs system {}))
  ([system opts]
   (runtime/list-runs (:runtime-service system) opts)))

(defn get-run
  [system run-id]
  (runtime/get-run (:runtime-service system) run-id))

(defn register-run!
  [system run-id registration]
  (runtime/register-run! (:runtime-service system) run-id registration))

(defn heartbeat-run!
  [system run-id heartbeat]
  (runtime/heartbeat! (:runtime-service system) run-id heartbeat))

(defn checkpoint-run!
  [system run-id checkpoint]
  (runtime/checkpoint! (:runtime-service system) run-id checkpoint))

(defn enqueue-run-command!
  [system run-id command]
  (runtime/enqueue-command! (:runtime-service system) run-id command))

(defn pending-run-commands
  [system run-id]
  (runtime/pending-commands (:runtime-service system) run-id))

(defn list-run-commands
  ([system run-id] (list-run-commands system run-id {}))
  ([system run-id opts]
   (runtime/list-commands (:runtime-service system) run-id opts)))

(defn list-run-heartbeats
  ([system run-id] (list-run-heartbeats system run-id {}))
  ([system run-id opts]
   (runtime/list-heartbeats (:runtime-service system) run-id opts)))

(defn list-run-checkpoints
  ([system run-id] (list-run-checkpoints system run-id {}))
  ([system run-id opts]
   (runtime/list-checkpoints (:runtime-service system) run-id opts)))

(defn acknowledge-run-command!
  [system run-id command-id]
  (runtime/acknowledge-command! (:runtime-service system) run-id command-id))

(defn complete-run-command!
  [system run-id command-id status error]
  (runtime/complete-command! (:runtime-service system) run-id command-id status error))

(defn transition-run!
  [system run-id status & [opts]]
  (runtime/transition-run! (:runtime-service system) run-id status opts))

(defn runner-status
  [system run-id]
  (when-let [run (get-run system run-id)]
    (when-let [runner (get (:runner-registry system) (keyword (:substrate run)))]
      (runners/status runner run-id))))

(defn launch-run!
  [system run-id]
  (let [run (or (get-run system run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (ex-info "No runner for substrate"
                                   {:type :runner-not-found
                                    :substrate (:substrate run)})))
        launch-result (runners/launch runner
                                      (runners/create-run-spec
                                       {:run-id (:id run)
                                        :agent-id (:agent-id run)
                                        :parent-run-id (:parent-run-id run)
                                        :lease-id (:lease-id run)
                                        :name (:name run)
                                        :substrate (keyword (:substrate run))
                                        :capabilities (:capabilities run)
                                        :network-identity (:network-identity run)
                                        :bootstrap-token (:bootstrap-token run)
                                        :bootstrap-spec (:bootstrap-spec run)
                                        :requested-by (:requested-by run)
                                        :runner-options (prepare-runner-options system run)}))]
    (transition-run! system run-id :launched {:runner-metadata launch-result})
    (get-run system run-id)))

(defn signal-run!
  [system run-id command]
  (let [run (or (get-run system run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (ex-info "No runner for substrate"
                                   {:type :runner-not-found
                                    :substrate (:substrate run)})))
        signal-result (runners/signal runner run-id command)
        command-type (cond
                       (keyword? command) command
                       (map? command) (keyword (:command-type command))
                       (string? command) (keyword command)
                       :else nil)]
    (when (contains? #{:cancel :terminate :kill} command-type)
      (transition-run! system run-id :cancelled {:runner-metadata (merge (:runner-metadata run)
                                                                         signal-result)}))
    signal-result))

(defn spawn-agent!
  [system spec]
  (orchestrator/spawn-agent! (:orchestrator system) spec))

(defn list-agents
  [system]
  (orchestrator/list-agents (:orchestrator system)))

(defn list-agent-messages
  [system agent-id]
  (orchestrator/list-agent-messages (:orchestrator system) agent-id))

(defn send-agent-message!
  [system agent-id message]
  (orchestrator/send-agent-message! (:orchestrator system) (:llm-provider system) agent-id message))

(defn create-channel!
  [system spec]
  (orchestrator/create-channel! (:orchestrator system) spec))

(defn list-channels
  [system]
  (orchestrator/list-channels (:orchestrator system)))

(defn list-channel-messages
  [system channel-id]
  (orchestrator/list-channel-messages (:orchestrator system) channel-id))

(defn post-channel-message!
  [system channel-id message]
  (orchestrator/post-channel-message! (:orchestrator system) channel-id message))

(defn consume-agent-inbox!
  [system agent-id]
  (orchestrator/consume-agent-inbox! (:orchestrator system) (:llm-provider system) agent-id))

(defn complete!
  [system messages {:keys [session-id] :as opts}]
  (let [user-message (last (filter #(= "user" (:role %)) messages))]
    (when session-id
      (when-let [prompt (:content user-message)]
        (append-session-message! system session-id "user" prompt)))
    (let [content (complete system messages opts)]
    (when session-id
      (append-session-message! system session-id "assistant" content))
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider (get-in system [:config :llm :provider])
                             :model (get-in system [:config :llm :model])
                             :prompt (:content user-message)
                             :response content})
    (log-event! system
                {:event-type :completion.completed
                 :entity-type :session
                 :entity-id session-id
                 :payload {:provider (name (get-in system [:config :llm :provider]))
                           :model (get-in system [:config :llm :model])}})
    {:content content})))

(defn start-api!
  [system]
  (let [server (api/start-server! system (:api (:config system)))]
    (assoc system :api-server server)))

(defn stop-api!
  [system]
  (when-let [server (:api-server system)]
    (api/stop-server! server))
  (dissoc system :api-server))

(defn- usage []
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m agent.core \"prompt text\""
    "  clojure -M -m agent.core serve"
    "  clojure -M -m agent.core --config path/to/config.edn \"prompt text\""]))

(defn -main
  [& args]
  (let [[config-path rest-args] (if (= "--config" (first args))
                                  [(second args) (drop 2 args)]
                                  [nil args])
        command (first rest-args)
        prompt (str/join " " rest-args)]
    (cond
      (= "serve" command)
      (let [system (start-api! (create-system config-path))
            {:keys [host port]} (:api (:config system))]
        (println (str "API listening on http://" host ":" port))
        @(promise))

      (str/blank? prompt)
      (do
        (binding [*out* *err*]
          (println (usage)))
        (System/exit 1))

      :else
      (let [system (create-system config-path)
            response (complete system prompt)]
        (println response)))))
