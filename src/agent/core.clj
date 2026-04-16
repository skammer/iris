(ns agent.core
  "Canonical composition root for the rewrite."
  (:gen-class)
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.api :as api]
   [agent.config :as config]
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as ollama]
   [agent.llm.providers.openai-compatible :as openai-compatible]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.skills :as skills]
   [agent.tools.common.http :as http-tool]
   [agent.tools.core :as tools]
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

(defn create-event-sink
  [store]
  (fn [event]
    (sqlite/log-event! store event)))

(defn create-tool-registry
  [cfg event-sink]
  (let [http-cfg (get cfg :http)
        registry (tools/create-registry
                  {:event-sink event-sink
                   :before-execute (fn [_] nil)
                   :after-execute (fn [_] nil)})]
    (cond-> registry
      (not= false (:enabled http-cfg))
      (tools/register-tool (http-tool/create-http-tool http-cfg)))))

(defn create-orchestrator
  [_cfg event-sink]
  (orchestrator/create-orchestrator {:event-sink event-sink}))

(defn create-skills-registry
  [cfg]
  (skills/create-registry cfg))

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
         event-sink (create-event-sink store)]
     {:config cfg
      :llm-provider (create-llm-provider llm-cfg)
      :store store
      :event-sink event-sink
      :tool-registry (create-tool-registry (:tools cfg) event-sink)
      :skills-registry (create-skills-registry (:skills cfg))
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
   :tools (tools/registry-health (:tool-registry system))
   :skills (skills/registry-health (:skills-registry system))
   :channel-adapters (channel-adapters/registry-health (:channel-adapter-registry system))
   :orchestrator (orchestrator/health-check (:orchestrator system))
   :provider (get-in system [:config :llm :provider])})

(defn log-event!
  [system event]
  ((:event-sink system) event))

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

(defn execute-tool
  ([system tool-name input]
   (execute-tool system tool-name input {}))
  ([system tool-name input context]
   (tools/execute-tool (:tool-registry system) tool-name input context)))

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
  (let [content (complete system messages opts)
        user-message (last (filter #(= "user" (:role %)) messages))]
    (when session-id
      (when-let [prompt (:content user-message)]
        (sqlite/append-message! (:store system) session-id "user" prompt))
      (sqlite/append-message! (:store system) session-id "assistant" content))
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
    {:content content}))

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
