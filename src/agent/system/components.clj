(ns agent.system.components
  "System component factories and dependency wiring. Creates config, storage,
   telemetry, event sink, broker, LLM providers, tools, skills, memory, chat,
   and channel adapters in the order they depend on each other."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.cron.service :as cron]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.magi.core :as magi]
   [agent.memory.core :as memory]
   [agent.memory.idle :as memory-idle]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.skills :as skills]
   [agent.streaming.metrics :as streaming-metrics]
   [agent.system.events :as events]
   [agent.system.health :as system-health]
   [agent.telegram :as telegram]
   [agent.telemetry :as telemetry]
   [agent.telemetry.observer :as telemetry-observer]
   [agent.tools.service :as tool-service]
   [agent.wasm.bundles :as wasm-bundles]))

(defn create-store
  [cfg]
  (sqlite/create-store (get cfg :sqlite)))

(defn create-telemetry
  [cfg]
  (telemetry/create-collector cfg))

(defn create-observer
  [telemetry-collector cfg]
  (telemetry-observer/create-observer telemetry-collector cfg))

(defn create-trace
  [cfg]
  (runtime-trace/create-trace (:trace cfg) (get-in cfg [:iris :data-dir])))

(defn create-skills-registry
  [cfg]
  (skills/create-registry cfg))

(defn create-skills-registry-from-config
  [cfg]
  (create-skills-registry
   (assoc (:skills cfg)
          :bundle-dirs (wasm-bundles/bundle-skill-dirs
                        (get-in cfg [:tools :wasm-bundles])))))

(defn create-memory-service
  ([cfg store]
   (memory/create-memory-service cfg store))
  ([cfg tools-cfg store]
   (memory/create-memory-service (assoc cfg :fs-roots (get-in tools-cfg [:fs :roots]))
                                 store))
  ([cfg tools-cfg store llm-cfg llm-provider]
   (let [provider-cfg (config/active-provider-config llm-cfg)]
     (memory/create-memory-service
      (assoc cfg :fs-roots (get-in tools-cfg [:fs :roots]))
      store
      {:embedding-provider llm-provider
       :embedding-model (:embedding-model provider-cfg)}))))

(defn create-memory-idle-service
  [system-ref]
  (memory-idle/create-service system-ref))

(defn create-memory-magi-review-service
  [system-ref]
  (memory-magi-review/create-service system-ref))

(defn create-channel-adapter-registry
  ([cfg] (create-channel-adapter-registry cfg nil))
  ([_cfg telegram-service]
   (let [registry (channel-adapters/create-registry)
         registry* (if telegram-service
                     (channel-adapters/register-adapter registry telegram-service)
                     registry)]
     registry*)))

(defn build-tool-registry
  "The one place that knows the tool registry's dependency list. `system`
   must already carry event-sink/store/telemetry/memory-service/
   system-control/observer/trace; `cfg` is the full config map."
  [{:keys [health-registry] :as system} cfg]
  (system-health/with-component-health health-registry :tools
    #(tool-service/create-tool-registry
      {:cfg (:tools cfg)
       :event-sink (:event-sink system)
       :store (:store system)
       :telemetry (:telemetry system)
       :memory-service (:memory-service system)
       :skills-registry (:skills-registry system)
       :cron-service (:cron-service system)
       :channel-adapters-cfg (:channel-adapters cfg)
       :system-control (:system-control system)
       :observer (:observer system)
       :trace (:trace system)
       :magi-service (:magi-service system)
       :llm-provider (:llm-provider system)
       :note-llm-provider (:note-llm-provider system)})))

(defn attach-telegram-service
  "Create the Telegram adapter and channel-adapter registry for `system`.
   The only copy of this wiring; used by construction and both reload paths."
  [{:keys [health-registry config] :as system}]
  (let [telegram-service (telegram/create-service system)]
    (assoc system
           :telegram-service telegram-service
           :channel-adapter-registry
           (system-health/with-component-health health-registry :channel-adapters
             #(create-channel-adapter-registry (:channel-adapters config)
                                               telegram-service)))))

(defn create-system-components
  [config-path system-ref reload-state health-registry system-control]
  (let [cfg (config/load-config config-path)
        _ (logging/start! (:logging cfg))
        llm-cfg (config/llm-config cfg)
        store (system-health/with-component-health health-registry :sqlite
                #(create-store (:storage cfg)))
        telemetry-collector (system-health/with-component-health health-registry :telemetry
                              #(create-telemetry (:telemetry cfg)))
        observer (create-observer telemetry-collector (:observer cfg))
        trace (create-trace cfg)
        broker-instance (system-health/with-component-health health-registry :broker
                          #(events/create-broker store))
        event-sink (events/create-event-sink store broker-instance telemetry-collector observer trace)
        llm-registry (llm-registry/create-registry llm-cfg)
        llm-provider (system-health/with-component-health health-registry :llm-provider
                       #(llm-service/create-llm-provider llm-cfg))
        magi-service (magi/create-service
                      cfg
                      {:default-provider llm-provider
                       :tool-registry-fn #(some-> @system-ref :tool-registry)})
        note-llm-provider (system-health/with-component-health health-registry :llm-provider
                            #(llm-service/create-note-llm-provider cfg))
        memory-service (system-health/with-component-health health-registry :memory
                         #(create-memory-service (:memory cfg)
                                                 (:tools cfg)
                                                 store
                                                 llm-cfg
                                                 llm-provider))
        chat-service (system-health/with-component-health health-registry :chat
                       #(chat/create-service))
        cron-service (cron/create-service system-ref store (:cron cfg))]
    (logging/log! :agent.system.lifecycle/created
                  {:config-path config-path
                   :provider (name (config/active-provider-key (:llm cfg)))
                   :sqlite-path (get-in cfg [:storage :sqlite :path])
                   :log-path (get-in cfg [:logging :file :path])})
    (let [base-system {:config cfg
                       :config-path config-path
                       :system-ref system-ref
                       :reload-state reload-state
                       :system-control system-control
                       :health-registry health-registry
                       :llm-registry llm-registry
                       :llm-provider llm-provider
                       :magi-service magi-service
                       :note-llm-provider note-llm-provider
                       :store store
                       :telemetry telemetry-collector
                       :sse-metrics (streaming-metrics/create-store)
                       :observer observer
                       :trace trace
                       :broker broker-instance
                       :event-sink event-sink
                       :chat-service chat-service
                       :cron-service cron-service
                       :memory-idle-service (create-memory-idle-service system-ref)
                       :memory-magi-review-service (create-memory-magi-review-service system-ref)
                       :skills-registry (create-skills-registry-from-config cfg)
                       :memory-service memory-service}]
      (-> base-system
          (assoc :tool-registry (build-tool-registry base-system cfg))
          attach-telegram-service))))
