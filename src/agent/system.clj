(ns agent.system
  "System construction and runtime facade."
  (:require
   [agent.broker.core :as broker]
   [agent.broker.local :as local-broker]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.api :as api]
   [agent.config :as config]
   [agent.federation.http :as federation-http]
   [agent.kernel :as kernel]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]
   [agent.logging :as logging]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.bubblewrap :as bubblewrap]
   [agent.runners.docker-podman :as docker-podman]
   [agent.runners.core :as runners]
   [agent.runners.local-unsandboxed :as local-unsandboxed]
   [agent.runners.options :as runner-options]
   [agent.runners.seatbelt :as seatbelt]
   [agent.runtime.core :as runtime]
   [agent.skills :as skills]
   [agent.telemetry :as telemetry]
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.common.http :as http-tool]
   [agent.tools.common.memory :as memory-tool]
   [agent.tools.common.shell :as shell-tool]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [clojure.set :as set]))

(declare spawn-task-worker! send-agent-message! execute-agent-tool!)

(defn create-llm-provider
  [cfg]
  (let [{:keys [provider model site-url app-name openrouter ollama openai-compatible
                prompt-cache? stream-structured-output?]} cfg]
    (case provider
      :ollama
      (ollama/create-ollama-provider
       {:base-url (get ollama :base-url)
        :default-model model
        :embedding-model (get ollama :embedding-model)
        :keep-alive (get ollama :keep-alive)
        :stream-structured-output? stream-structured-output?})

      :openrouter
      (openai-compatible/create-openrouter-provider
       {:api-key (get openrouter :api-key)
        :base-url (get openrouter :base-url)
        :model model
        :site-url site-url
        :app-name app-name
        :prompt-cache? prompt-cache?
        :stream-structured-output? stream-structured-output?})

      :openai-compatible
      (openai-compatible/create-openai-compatible-provider
       {:api-key (get openai-compatible :api-key)
        :base-url (get openai-compatible :base-url)
        :default-model model
        :site-url site-url
        :app-name app-name
        :prompt-cache? prompt-cache?
        :stream-structured-output? stream-structured-output?})

      (throw (ex-info (str "Unsupported provider: " provider)
                      {:provider provider})))))

(defn create-fact-llm-provider
  [cfg]
  (let [extractor (get-in cfg [:memory :facts :extractor])
        provider (:provider extractor)
        model (:model extractor)]
    (when (or provider model)
      (create-llm-provider (cond-> (:llm cfg)
                             provider (assoc :provider provider)
                             model (assoc :model model))))))

(defn create-store
  [cfg]
  (sqlite/create-store (get cfg :sqlite)))

(defn- replay-broker-messages
  [store pattern {:keys [limit after-id since-sequence request-id]
                  :or {limit 100}}]
  (let [pattern* (str pattern)]
    (cond
      (nil? store) []

      (= pattern* (broker/all-events-subject))
      (mapv (fn [event] {:subject "events.all" :payload event})
            (reverse (sqlite/list-events store {:limit limit
                                               :after-id after-id})))

      (= pattern* (broker/all-runs-subject))
      (mapcat broker/event->messages
              (reverse (sqlite/list-events store {:entity-type :agent_run
                                                  :limit limit
                                                  :after-id after-id})))

      (re-matches #"runs\.([^\.]+)\.events" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.events" pattern*)]
        (mapv (fn [event] {:subject (broker/run-events-subject run-id)
                           :payload event})
              (reverse (sqlite/list-events store {:entity-type :agent_run
                                                  :entity-id run-id
                                                  :limit limit
                                                  :after-id after-id}))))

      (re-matches #"runs\.([^\.]+)\.commands" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.commands" pattern*)]
        (mapv broker/command->message
              (sqlite/list-agent-run-commands store run-id {:limit limit
                                                            :request-id request-id})))

      (re-matches #"runs\.([^\.]+)\.heartbeats" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.heartbeats" pattern*)]
        (mapv broker/heartbeat->message
              (sqlite/list-agent-run-heartbeats store run-id {:limit limit
                                                              :since-sequence since-sequence})))

      (re-matches #"runs\.([^\.]+)\.checkpoints" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.checkpoints" pattern*)]
        (mapv broker/checkpoint->message
              (sqlite/list-agent-run-checkpoints store run-id {:limit limit
                                                               :since-sequence since-sequence})))

      (re-matches #"runs\.([^\.]+)\.output" pattern*)
      (let [[_ run-id] (re-matches #"runs\.([^\.]+)\.output" pattern*)]
        (mapv (fn [event] {:subject (broker/run-output-subject run-id)
                           :payload event})
              (reverse (sqlite/list-events store {:entity-type :agent_run
                                                  :entity-id run-id
                                                  :event-type "agent.run.output"
                                                  :limit limit
                                                  :after-id after-id}))))

      :else [])))

(defn create-broker
  [store]
  (local-broker/create-broker {:replay-fn #(replay-broker-messages store %1 %2)}))

(defn create-event-bus
  []
  (create-broker nil))

(defn create-telemetry
  [cfg]
  (telemetry/create-collector cfg))

(defn create-event-sink
  ([store broker-instance]
   (create-event-sink store broker-instance nil))
  ([store broker-instance telemetry-collector]
  (fn [event]
    (let [recorded (sqlite/log-event! store event)]
      (logging/log-system-event! recorded)
      (telemetry/record-system-event! telemetry-collector recorded)
      (doseq [message (broker/event->messages recorded)]
        (broker/publish! broker-instance message))
      recorded))))

(defn create-recorded-event-sink
  ([broker-instance]
   (create-recorded-event-sink broker-instance nil))
  ([broker-instance telemetry-collector]
   (fn [recorded]
     (logging/log-system-event! recorded)
     (telemetry/record-system-event! telemetry-collector recorded)
     (doseq [message (broker/event->messages recorded)]
       (broker/publish! broker-instance message))
     recorded)))

(defn- normalize-tool-name [tool]
  (cond
    (keyword? tool) tool
    (string? tool) (keyword tool)
    :else tool))

(defn- tool-name-set [tools]
  (set (map normalize-tool-name tools)))

(defn create-tool-policy-hook
  [cfg]
  (let [policy (:policy cfg)
        allowlist (tool-name-set (:allowlist policy))
        blocklist (tool-name-set (:blocklist policy))
        tool-scopes (into {}
                          (map (fn [[tool scopes]]
                                 [(normalize-tool-name tool) (set (map keyword scopes))]))
                          (:tool-scopes policy))]
    (fn [{:keys [tool context]}]
      (let [tool-name (:name tool)
            context-scopes (set (map keyword (or (:tool-scopes context) (:scopes context) [])))
            required-scopes (get tool-scopes tool-name)]
        (cond
          (or (contains? blocklist :*) (contains? blocklist tool-name))
          {:block true
           :reason "Tool blocked by startup policy"}

          (and (seq allowlist)
               (not (or (contains? allowlist :*) (contains? allowlist tool-name))))
          {:block true
           :reason "Tool not in startup allowlist"}

          (and (seq required-scopes)
               (empty? (set/intersection required-scopes context-scopes)))
          {:block true
           :reason "Tool scope missing"}

          :else nil)))))

(defn subscribe-events
  ([system] (subscribe-events system (broker/all-events-subject)))
  ([system pattern]
   (broker/subscribe! (:broker system) pattern)))

(defn unsubscribe-events
  [system subscription]
  (broker/unsubscribe! (:broker system) subscription))

(defn create-tool-registry
  ([cfg event-sink store]
   (create-tool-registry cfg event-sink store nil nil))
  ([cfg event-sink store telemetry-collector]
   (create-tool-registry cfg event-sink store telemetry-collector nil))
  ([cfg event-sink store telemetry-collector memory-service]
   (let [http-cfg (get cfg :http)
         fs-cfg (get cfg :fs)
         shell-cfg (get cfg :shell)
         policy-hook (create-tool-policy-hook cfg)
         registry (tools/create-registry
                   {:event-sink event-sink
                    :approval-check (tool-approvals/create-policy-hook store)
                    :before-execute policy-hook
                    :activity-executor (when store
                                         (fn [activity f]
                                           (:result (runtime/execute-activity!
                                                     (runtime/create-runtime-service {:store store})
                                                     activity
                                                     f))))
                    :after-execute (fn [{:keys [tool context duration-ms is-error error] :as hook}]
                                     (telemetry/record-tool! telemetry-collector
                                                             {:tool-name (:name tool)
                                                              :duration-ms duration-ms
                                                              :success? (not is-error)
                                                              :error error
                                                              :user (:user context)})
                                     hook)})]
     (cond-> registry
       (not= false (:enabled http-cfg))
       (tools/register-tool (http-tool/create-http-tool http-cfg))

       (not= false (:enabled fs-cfg))
       (tools/register-tool (fs-tool/create-fs-tool fs-cfg))

       memory-service
       (tools/register-tool (memory-tool/create-memory-tool memory-service))

       (not= false (:enabled shell-cfg))
       (tools/register-tool (shell-tool/create-shell-tool shell-cfg))))))

(defn create-orchestrator
  ([_cfg event-sink]
   (create-orchestrator _cfg event-sink nil nil))
  ([_cfg event-sink telemetry-collector]
   (create-orchestrator _cfg event-sink telemetry-collector nil))
  ([cfg event-sink telemetry-collector store]
  (orchestrator/create-orchestrator {:event-sink event-sink
                                     :telemetry telemetry-collector
                                     :federation-deliver (federation-http/create-forwarder
                                                          (assoc (:federation cfg)
                                                                 :store store
                                                                 :telemetry telemetry-collector))})))

(defn create-skills-registry
  [cfg]
  (skills/create-registry cfg))

(defn create-memory-service
  ([cfg store]
   (memory/create-memory-service cfg store))
  ([cfg tools-cfg store]
   (memory/create-memory-service (assoc cfg :fs-roots (get-in tools-cfg [:fs :roots]))
                                 store)))

(defn create-runtime-service
  ([store event-sink]
   (create-runtime-service store event-sink nil))
  ([store event-sink broker-instance]
   (runtime/create-runtime-service {:store store
                                     :broker broker-instance
                                     :event-sink event-sink}))
  ([store event-sink broker-instance recorded-event-sink]
   (runtime/create-runtime-service {:store store
                                    :broker broker-instance
                                    :event-sink event-sink
                                    :recorded-event-sink recorded-event-sink})))

(defn- runner-exit-status [run exit-code]
  (cond
    (contains? #{"cancelled" "completed" "failed"} (:status run)) nil
    (zero? exit-code) :completed
    :else :failed))

(defn- create-exit-aware-local-unsandboxed-runner
  [runtime-service]
  (local-unsandboxed/create-local-unsandboxed-runner
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
  {:local-unsandboxed (create-exit-aware-local-unsandboxed-runner runtime-service)
   :bubblewrap (bubblewrap/create-bubblewrap-runner
                {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)})
   :docker (docker-podman/create-docker-podman-runner
            {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)
             :engine-binary "docker"})
   :podman (docker-podman/create-docker-podman-runner
            {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)
             :engine-binary "podman"})
   :seatbelt (seatbelt/create-seatbelt-runner
              {:delegate (create-exit-aware-local-unsandboxed-runner runtime-service)})})

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
         _ (logging/start! (:logging cfg))
         llm-cfg (config/llm-config cfg)
         store (create-store (:storage cfg))
         telemetry-collector (create-telemetry (:telemetry cfg))
         broker-instance (create-broker store)
         event-sink (create-event-sink store broker-instance telemetry-collector)
         recorded-event-sink (create-recorded-event-sink broker-instance telemetry-collector)
         runtime-service (create-runtime-service store event-sink broker-instance recorded-event-sink)
         memory-service (create-memory-service (:memory cfg) (:tools cfg) store)]
     (logging/log! :agent.system/created
                   {:config-path config-path
                    :provider (name (get-in cfg [:llm :provider]))
                    :sqlite-path (get-in cfg [:storage :sqlite :path])
                    :log-path (get-in cfg [:logging :file :path])})
     {:config cfg
      :llm-provider (create-llm-provider llm-cfg)
      :fact-llm-provider (create-fact-llm-provider cfg)
      :store store
      :telemetry telemetry-collector
      :broker broker-instance
      :event-sink event-sink
      :recorded-event-sink recorded-event-sink
      :tool-registry (create-tool-registry (:tools cfg) event-sink store telemetry-collector memory-service)
      :skills-registry (create-skills-registry (:skills cfg))
      :memory-service memory-service
      :runtime-service runtime-service
      :runner-registry (create-runner-registry runtime-service)
      :channel-adapter-registry (create-channel-adapter-registry (:channel-adapters cfg))
      :orchestrator (create-orchestrator (:orchestrator cfg) event-sink telemetry-collector store)})))

(defn complete
  ([system prompt]
   (complete system [{:role "user" :content prompt}] {}))
  ([system messages opts]
   (telemetry/complete-with-telemetry! (:telemetry system)
                                       (:llm-provider system)
                                       messages
                                       opts
                                       {:agent-id "system"
                                        :model (or (:model opts)
                                                   (get-in system [:config :llm :model]))})))

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
   :logging (logging/health-check)
   :broker (broker/health-check (:broker system))
   :tools (tools/registry-health (:tool-registry system))
   :skills (skills/registry-health (:skills-registry system))
   :memory (memory/health-check (:memory-service system))
   :telemetry (telemetry/health-check (:telemetry system))
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

(defn- prepare-runner-options
  [system run]
  (runner-options/prepare-runner-options system run))

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

(defn telemetry-snapshot
  [system]
  (telemetry/snapshot (:telemetry system)))

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

(defn save-memory-fact!
  [system fact opts]
  (memory/save-memory-fact! (:memory-service system) fact opts))

(defn search-memory-facts
  ([system query] (search-memory-facts system query {}))
  ([system query opts]
   (memory/search-facts (:memory-service system) query opts)))

(defn save-graph-fact!
  [system fact]
  (memory/save-graph-fact! (:memory-service system) fact))

(defn query-graph-memory
  ([system query] (query-graph-memory system query {}))
  ([system query opts]
   (memory/query-graph-memory (:memory-service system) query opts)))

(defn tool-permissions
  [system profile]
  (set (get-in system [:config :tools :permissions profile] #{})))

(defn execute-tool
  ([system tool-name input]
   (execute-tool system tool-name input {}))
  ([system tool-name input context]
   (tools/execute-tool (:tool-registry system)
                       tool-name
                       input
                       (assoc context :yolo? (true? (get-in system [:config :tools :yolo?]))))))

(defn get-agent
  [system agent-id]
  (orchestrator/get-agent (:orchestrator system) agent-id))

(defn execute-agent-tool!
  ([system agent-id tool-name input]
   (execute-agent-tool! system agent-id tool-name input {}))
  ([system agent-id tool-name input context]
   (let [agent (or (get-agent system agent-id)
                   (throw (ex-info "Agent not found"
                                   {:type :agent-not-found
                                    :agent-id agent-id})))]
     (execute-tool system tool-name input
                   (merge context
                          {:user (or (:user context) agent-id)
                           :permissions (tool-permissions system :agent)
                           :yolo? (true? (get-in system [:config :tools :yolo?]))
                           :allowed-tools (set (:tool-access agent))})))))

(defrecord SystemKernelOps [system]
  kernel-ops/KernelOps
  (spawn-task-worker! [_ spec]
    (spawn-task-worker! system spec))
  (execute-agent-tool! [_ agent-id tool-name input context]
    (execute-agent-tool! system agent-id tool-name input context))
  (send-agent-message! [_ agent-id message]
    (send-agent-message! system agent-id message))
  (patch-agent-state! [_ agent-id patch]
    (orchestrator/patch-agent-state! (:orchestrator system) agent-id patch))
  (set-agent-status! [_ agent-id status]
    (orchestrator/set-agent-status! (:orchestrator system) agent-id status))
  (emit-kernel-event! [_ event]
    ((:event-sink system) event)))

(defn- kernel-ops [system]
  (->SystemKernelOps system))

(defn execute-directive!
  ([system parent-agent-id directive]
   (execute-directive! system parent-agent-id directive {}))
  ([system parent-agent-id directive opts]
   (kernel-runtime/execute-directive!
    (kernel-ops system)
    parent-agent-id
    directive
    (merge {:yolo? (true? (get-in system [:config :tools :yolo?]))} opts))))

(defn execute-step!
  ([system parent-agent-id step]
   (execute-step! system parent-agent-id step {}))
  ([system parent-agent-id step opts]
   (kernel-runtime/execute-step!
    (kernel-ops system)
    parent-agent-id
    step
    (merge {:yolo? (true? (get-in system [:config :tools :yolo?]))} opts))))

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

(defn recovery-plan
  [system run-id]
  (runtime/recovery-plan (:runtime-service system) run-id))

(defn wait-for-run!
  ([system run-id] (wait-for-run! system run-id {}))
  ([system run-id opts]
   (runtime/wait-for-run! (:runtime-service system) run-id opts)))

(defn reclaim-stale-runs!
  [system]
  (runtime/reclaim-stale-runs! (:runtime-service system)))

(defn retry-run!
  [system run-id]
  (runtime/retry-run! (:runtime-service system) run-id))

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

(defn container-image-contract
  [system run-id]
  (when-let [run (get-run system run-id)]
    (when (#{"docker" "podman"} (:substrate run))
      (docker-podman/image-contract (prepare-runner-options system run)))))

(defn launch-run!
  [system run-id]
  (let [run (or (get-run system run-id)
                (throw (ex-info "Run not found" {:type :run-not-found
                                                 :run-id run-id})))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (ex-info "No runner for substrate"
                                   {:type :runner-not-found
                                    :substrate (:substrate run)})))
        checkpoint-seq (or (get-in run [:checkpoint :sequence-no]) 0)
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
                                        :bootstrap-spec (assoc (:bootstrap-spec run)
                                                              :checkpoint-seq checkpoint-seq)
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

(defn spawn-task-worker!
  [system {:keys [task name role capability-bundle memory-scopes budgets system-prompt parent-id]
           :or {name "Task Worker"
                role "worker"
                capability-bundle {}
                memory-scopes []
                budgets {}}}]
  (let [step (kernel/orchestrator-spawn-worker-step {:task task
                                                     :worker-name name
                                                     :worker-role role
                                                     :capability-bundle capability-bundle
                                                     :memory-scopes memory-scopes
                                                     :budgets budgets
                                                     :system-prompt system-prompt})
        spawn (-> step :directives first :payload)]
    (spawn-agent! system {:name (:name spawn)
                          :kind "worker"
                          :role (:role spawn)
                          :parent-id parent-id
                          :system-prompt (:system-prompt spawn)
                          :capabilities (vec (or (:capabilities capability-bundle) []))
                          :tool-access (vec (or (:tool-access capability-bundle) []))
                          :memory-scopes (vec memory-scopes)
                          :budgets budgets
                          :task task})))

(defn orchestrator-spawn-worker!
  [system orchestrator-agent-id worker-spec]
  (let [agent (or (get-agent system orchestrator-agent-id)
                  (throw (ex-info "Agent not found"
                                  {:type :agent-not-found
                                   :agent-id orchestrator-agent-id})))]
    (when-not (= "orchestrator" (:kind agent))
      (throw (ex-info "Agent is not an orchestrator"
                      {:type :validation-failed
                       :agent-id orchestrator-agent-id})))
    (let [step (kernel/orchestrator-spawn-worker-step
                {:task (:task worker-spec)
                 :worker-name (or (:name worker-spec) "Task Worker")
                 :worker-role (or (:role worker-spec) "worker")
                 :capability-bundle {:capabilities (or (:capabilities worker-spec) [])
                                     :tool-access (or (:tool-access worker-spec) [])}
                 :memory-scopes (or (:memory-scopes worker-spec) [])
                 :budgets (or (:budgets worker-spec) {})
                 :system-prompt (:system-prompt worker-spec)})]
      (execute-step! system orchestrator-agent-id step))))

(defn list-agents
  [system]
  (orchestrator/list-agents (:orchestrator system)))

(defn list-agent-messages
  [system agent-id]
  (orchestrator/list-agent-messages (:orchestrator system) agent-id))

(defn send-agent-message!
  [system agent-id message]
  (orchestrator/send-agent-message! (:orchestrator system) (:llm-provider system) agent-id message))

(defn describe-agent-interop
  [system agent-ref]
  (orchestrator/describe-agent-interop (:orchestrator system) agent-ref))

(defn register-agent-capabilities!
  [system agent-ref spec]
  (orchestrator/register-agent-capabilities! (:orchestrator system) agent-ref spec))

(defn register-federated-peer!
  [system spec]
  (orchestrator/register-federated-peer! (:orchestrator system) spec))

(defn list-federated-peers
  [system]
  (orchestrator/list-federated-peers (:orchestrator system)))

(defn send-interop-message!
  [system from-agent-ref to-agent-ref message]
  (orchestrator/send-interop-message! (:orchestrator system) from-agent-ref to-agent-ref message))

(defn list-interop-messages
  ([system agent-ref]
   (orchestrator/list-interop-messages (:orchestrator system) agent-ref))
  ([system agent-ref opts]
   (orchestrator/list-interop-messages (:orchestrator system) agent-ref opts)))

(defn acknowledge-interop-message!
  [system agent-ref message-id opts]
  (orchestrator/acknowledge-interop-message! (:orchestrator system) agent-ref message-id opts))

(defn retry-interop-message!
  [system agent-ref message-id]
  (orchestrator/retry-interop-message! (:orchestrator system) agent-ref message-id))

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
  (chat/run! system (merge opts
                           {:messages messages
                            :session-id session-id})))

(defn start-api!
  [system]
  (let [{:keys [host port]} (:api (:config system))
        server (api/start-server! system (:api (:config system)))]
    (logging/log! :agent.api/started
                  {:host host
                   :port port})
    (assoc system :api-server server)))

(defn stop-api!
  [system]
  (when-let [server (:api-server system)]
    (logging/log! :agent.api/stopping {})
    (api/stop-server! server))
  (dissoc system :api-server))
