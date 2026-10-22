(ns agent.system.components
  "System component factories and dependency wiring. Creates config, storage,
   telemetry, event sink, broker, LLM providers, tools, skills, memory, chat,
   and channel adapters in the order they depend on each other."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.api.ui-state :as ui-state]
   [agent.config :as config]
   [agent.cron.service :as cron]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.magi.core :as magi]
   [agent.memory.core :as memory]
   [agent.memory.idle :as memory-idle]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.memory.user-profile :as user-profile]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.skills :as skills]
   [agent.streaming.metrics :as streaming-metrics]
   [agent.system.events :as events]
   [agent.system.health :as system-health]
   [agent.system.resources :as resources]
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

(defn create-user-profile-service
  [cfg store provider system-ref]
  (user-profile/create-service
   {:config (get-in cfg [:memory :user-profile])
    :config-dir (get-in cfg [:iris :config-dir])
    :model (or (get-in cfg [:memory :user-profile :model])
               (get-in cfg [:memory :notes :extractor :model])
               (config/active-model (:llm cfg)))
    :provider provider
    :store store
    :on-update #(swap! system-ref
                       (fn [system]
                         (if system
                           (update system :config config/refresh-contexts)
                           system)))}))

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
       :user-profile-service (:user-profile-service system)
       :skills-registry (:skills-registry system)
       :cron-service (:cron-service system)
       :telegram-reply-keyboards (:telegram-reply-keyboards system)
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

(defn rebuild-runtime-components
  "Shared startup/soft-reload wiring. Retains store, broker, telemetry and chat.
   Factories here must not start workers; start/rollback belongs to lifecycle."
  [{:keys [store broker telemetry system-ref health-registry] :as system} cfg]
  (let [llm-cfg (config/llm-config cfg)
        observer (create-observer telemetry (:observer cfg))
        trace (create-trace cfg)
        llm-provider (system-health/with-component-health health-registry :llm-provider
                       #(llm-service/create-llm-provider llm-cfg))
        note-provider (system-health/with-component-health health-registry :llm-provider
                        #(llm-service/create-note-llm-provider cfg))
        base (assoc system
                    :config cfg
                    :observer observer
                    :trace trace
                    :event-sink (events/create-event-sink store broker telemetry observer trace)
                    :llm-registry (llm-registry/create-registry llm-cfg)
                    :llm-provider llm-provider
                    :note-llm-provider note-provider
                    :magi-service (magi/create-service
                                   cfg {:default-provider llm-provider
                                        :tool-registry-fn #(some-> @system-ref :tool-registry)})
                    :memory-service (system-health/with-component-health health-registry :memory
                                      #(create-memory-service (:memory cfg) (:tools cfg)
                                                              store llm-cfg llm-provider))
                    :user-profile-service (create-user-profile-service
                                           cfg store (or note-provider llm-provider) system-ref)
                    :cron-service (cron/create-service system-ref store (:cron cfg))
                    :memory-idle-service (create-memory-idle-service system-ref)
                    :memory-magi-review-service (create-memory-magi-review-service system-ref)
                    :skills-registry (create-skills-registry-from-config cfg))]
    (assoc base :tool-registry (build-tool-registry base cfg))))

(defn create-system-components
  [config-path system-ref reload-state health-registry system-control]
  (resources/with-rollback
   (fn [own!]
     (let [cfg (config/load-config config-path)
           _ (logging/start! (:logging cfg))
           store (own! (system-health/with-component-health health-registry :sqlite
                         #(create-store (:storage cfg))) sqlite/close-store!)
           telemetry (system-health/with-component-health health-registry :telemetry
                       #(create-telemetry (:telemetry cfg)))
           broker (system-health/with-component-health health-registry :broker
                    #(events/create-broker store))
           chat (own! (system-health/with-component-health health-registry :chat
                        #(chat/create-service (:chat cfg))) chat/stop!)
           base {:config-path config-path
                 :system-ref system-ref
                 :reload-state reload-state
                 :system-control system-control
                 :health-registry health-registry
                 :store store
                 :telemetry telemetry
                 :broker broker
                 :chat-service chat
                 :ui-state (ui-state/create-state)
                 :sse-metrics (streaming-metrics/create-store)
                 :telegram-reply-keyboards (atom {})}]
       (-> base
           (rebuild-runtime-components cfg)
           attach-telegram-service)))))
