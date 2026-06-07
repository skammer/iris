(ns agent.system.components
  "System component factories."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.federation.http :as federation-http]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runs.service :as runs-service]
   [agent.runtime.trace :as runtime-trace]
   [agent.skills :as skills]
   [agent.system.events :as events]
   [agent.system.health :as system-health]
   [agent.telegram :as telegram]
   [agent.telemetry :as telemetry]
   [agent.tools.service :as tool-service]))

(defn create-store
  [cfg]
  (sqlite/create-store (get cfg :sqlite)))

(defn create-telemetry
  [cfg]
  (telemetry/create-collector cfg))

(defn create-observer
  [telemetry-collector cfg]
  (telemetry/create-observer telemetry-collector cfg))

(defn create-trace
  [cfg]
  (runtime-trace/create-trace (:trace cfg) (get-in cfg [:iris :data-dir])))

(defn create-orchestrator
  ([_cfg event-sink]
   (create-orchestrator _cfg event-sink nil nil nil nil))
  ([_cfg event-sink telemetry-collector]
   (create-orchestrator _cfg event-sink telemetry-collector nil nil nil))
  ([cfg event-sink telemetry-collector store]
   (create-orchestrator cfg event-sink telemetry-collector store nil nil))
  ([cfg event-sink telemetry-collector store observer trace]
   (orchestrator/create-orchestrator {:event-sink event-sink
                                      :enabled? (true? (:enabled cfg))
                                      :telemetry telemetry-collector
                                      :observer observer
                                      :trace trace
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

(defn create-channel-adapter-registry
  ([cfg] (create-channel-adapter-registry cfg nil))
  ([_cfg telegram-service]
   (let [registry (channel-adapters/create-registry)
         registry* (if telegram-service
                     (channel-adapters/register-adapter registry telegram-service)
                     registry)]
     registry*)))

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
        recorded-event-sink (events/create-recorded-event-sink broker-instance telemetry-collector observer trace)
        runtime-service (system-health/with-component-health health-registry :runtime
                          #(runs-service/create-runtime-service store event-sink broker-instance recorded-event-sink))
        memory-service (system-health/with-component-health health-registry :memory
                         #(create-memory-service (:memory cfg) (:tools cfg) store))
        llm-registry (llm-registry/create-registry llm-cfg)
        llm-provider (system-health/with-component-health health-registry :llm-provider
                       #(llm-service/create-llm-provider llm-cfg))
        fact-llm-provider (system-health/with-component-health health-registry :llm-provider
                            #(llm-service/create-fact-llm-provider cfg))
        tool-registry (system-health/with-component-health health-registry :tools
                        #(tool-service/create-tool-registry (:tools cfg)
                                                            event-sink
                                                            store
                                                            telemetry-collector
                                                            memory-service
                                                            (:channel-adapters cfg)
                                                            system-control
                                                            observer
                                                            trace))
        chat-service (system-health/with-component-health health-registry :chat
                       #(chat/create-service))]
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
                       :fact-llm-provider fact-llm-provider
                       :store store
                       :telemetry telemetry-collector
                       :observer observer
                       :trace trace
                       :broker broker-instance
                       :event-sink event-sink
                       :recorded-event-sink recorded-event-sink
                       :tool-registry tool-registry
                       :chat-service chat-service
                       :skills-registry (create-skills-registry (:skills cfg))
                       :memory-service memory-service
                       :runtime-service runtime-service
                       :runner-registry (runs-service/create-runner-registry runtime-service)
                       :orchestrator (create-orchestrator (:orchestrator cfg)
                                                          event-sink
                                                          telemetry-collector
                                                          store
                                                          observer
                                                          trace)}
          telegram-service (telegram/create-service base-system)
          system* (assoc base-system
                         :telegram-service telegram-service
                         :channel-adapter-registry
                         (system-health/with-component-health health-registry :channel-adapters
                           #(create-channel-adapter-registry (:channel-adapters cfg)
                                                             telegram-service)))]
      system*)))
