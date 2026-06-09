(ns agent.system
  "System lifecycle only."
  (:require
   [agent.api :as api]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.health :as health]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.components :as components]
   [agent.system.health :as system-health]
   [agent.telegram :as telegram]
   [agent.tools.service :as tool-service]
   [agent.util :as util]))

(declare reload! start-api! current-system)

(defn- system-control
  [system-ref]
  {:system-ref system-ref
   :current-system current-system
   :reload! reload!
   :health-check system-health/health-check})

(defn create-system
  ([] (create-system nil))
  ([config-path]
   (let [system-ref (atom nil)
         reload-state (atom {:status :idle})
         health-registry (health/create-registry)
         control (system-control system-ref)
         system* (components/create-system-components config-path
                                                       system-ref
                                                       reload-state
                                                       health-registry
                                                       control)]
     (reset! system-ref system*)
     system*)))

(defn current-system
  [system]
  (if-let [system-ref (:system-ref system)]
    (or @system-ref system)
    system))

(defn- provider-summary [cfg]
  (let [provider-cfg (config/active-provider-config (:llm cfg))]
    {:provider (:provider provider-cfg)
     :model (:model provider-cfg)}))

(defn- reload-result [mode old-cfg new-cfg status]
  {:mode mode
   :status status
   :previous (provider-summary old-cfg)
   :current (provider-summary new-cfg)})

(defn- running-adapter? [service]
  (true? (get-in (channel-adapters/adapter-health-check service) [:running])))

(defn- attach-telegram-service
  [system]
  (let [health-registry (:health-registry system)
        telegram-service (telegram/create-service system)]
    (assoc system
           :telegram-service telegram-service
           :channel-adapter-registry
           (system-health/with-component-health health-registry :channel-adapters
             #(components/create-channel-adapter-registry
               (get-in system [:config :channel-adapters])
               telegram-service)))))

(defn- replace-running-telegram!
  [old-system new-system]
  (let [health-registry (:health-registry old-system)
        old-service (:telegram-service old-system)
        running? (some-> old-service running-adapter?)]
    (if-not running?
      new-system
      (do
        (health/bump-restart! health-registry :channel-adapters)
        (try
          (channel-adapters/stop-adapter! old-service)
          (let [started (system-health/with-component-health health-registry :channel-adapters
                          #(channel-adapters/start-adapter! (:telegram-service new-system)))]
            (assoc new-system :telegram-service started))
          (catch Exception e
            (try
              (channel-adapters/start-adapter! old-service)
              (catch Exception restart-error
                (logging/log-error! :agent.system.lifecycle/telegram-restart-failed
                                    restart-error
                                    {})))
            (throw e)))))))

(defn- stop-api-server!
  [system]
  (when-let [server (:api-server system)]
    (logging/log! :agent.api/stopping {})
    (api/stop-server! server)))

(defn- stop-runtime-edges!
  [system]
  (some-> (:chat-service system) chat/stop!)
  (some-> (:telegram-service system) channel-adapters/stop-adapter!)
  (stop-api-server! system))

(defn- rebuild-hot-system [old-system new-cfg]
  (let [health-registry (:health-registry old-system)
        memory-service (system-health/with-component-health health-registry :memory
                         #(components/create-memory-service (:memory new-cfg)
                                                           (:tools new-cfg)
                                                           (:store old-system)))
        observer (components/create-observer (:telemetry old-system) (:observer new-cfg))
        trace (components/create-trace new-cfg)
        base (assoc old-system
                    :config new-cfg
                    :llm-registry (llm-registry/create-registry (config/llm-config new-cfg))
                    :chat-service (system-health/with-component-health health-registry :chat
                                    #(chat/create-service))
                    :llm-provider (system-health/with-component-health health-registry :llm-provider
                                    #(llm-service/create-llm-provider (:llm new-cfg)))
                    :fact-llm-provider (system-health/with-component-health health-registry :llm-provider
                                         #(llm-service/create-fact-llm-provider new-cfg))
                    :observer observer
                    :trace trace
                    :memory-service memory-service
                    :skills-registry (components/create-skills-registry (:skills new-cfg)))
        system-with-tools (assoc base
                                 :tool-registry
                                 (system-health/with-component-health health-registry :tools
                                   #(tool-service/create-tool-registry (:tools new-cfg)
                                                                      (:event-sink old-system)
                                                                      (:store old-system)
                                                                      (:telemetry old-system)
                                                                      memory-service
                                                                      (:channel-adapters new-cfg)
                                                                      (:system-control old-system)
                                                                      observer
                                                                      trace)))]
    (->> (attach-telegram-service system-with-tools)
         (replace-running-telegram! old-system))))

(defn- soft-reload! [system opts]
  (let [system* (current-system system)
        system-ref (:system-ref system*)
        old-cfg (:config system*)
        new-cfg (config/load-config (:config-path system*))
        new-system (rebuild-hot-system system* new-cfg)
        result (reload-result :soft old-cfg new-cfg :reloaded)]
    (chat/stop! (:chat-service system*))
    (logging/start! (:logging new-cfg))
    (reset! system-ref new-system)
    (reset! (:reload-state new-system)
            (assoc result
                   :source (:source opts)
                   :reloaded-at (util/now-str)))
    ((:event-sink new-system)
     {:event-type :system.config.reloaded
      :entity-type :system
      :entity-id "runtime"
      :payload result})
    result))

(defn close-system!
  [system]
  (try
    (some-> (:chat-service system) chat/stop!)
    (catch Exception e
      (logging/log-error! :agent.system.lifecycle/chat-stop-failed e {})))
  (try
    (some-> (:telegram-service system) channel-adapters/stop-adapter!)
    (catch Exception e
      (logging/log-error! :agent.system.lifecycle/telegram-stop-failed e {})))
  (try
    (some-> (:api-server system) api/stop-server!)
    (catch Exception e
      (logging/log-error! :agent.system.lifecycle/api-stop-failed e {})))
  (try
    (when-let [stop! (some-> system :orchestrator :federation-forwarder :stop!)]
      (stop!))
    (catch Exception e
      (logging/log-error! :agent.system.lifecycle/federation-stop-failed e {})))
  (try
    (some-> (:store system) sqlite/close-store!)
    (catch Exception e
      (logging/log-error! :agent.system.lifecycle/store-close-failed e {}))))

(defn- full-reload-now! [system opts]
  (let [old-system (current-system system)
        system-ref (:system-ref old-system)
        reload-state (:reload-state old-system)
        health-registry (:health-registry old-system)
        old-cfg (:config old-system)
        api-running? (some? (:api-server old-system))
        _ (health/bump-restart! health-registry :runtime)
        new-system* (try
                      (create-system (:config-path old-system))
                      (catch Exception e
                        (health/mark-error! health-registry :runtime e)
                        (throw e)))
        new-system** (assoc new-system*
                            :system-ref system-ref
                            :reload-state reload-state
                            :health-registry health-registry
                            :system-control (:system-control old-system))
        new-system*** (assoc new-system**
                             :tool-registry
                             (system-health/with-component-health health-registry :tools
                               #(tool-service/create-tool-registry (get-in new-system** [:config :tools])
                                                                  (:event-sink new-system**)
                                                                  (:store new-system**)
                                                                  (:telemetry new-system**)
                                                                  (:memory-service new-system**)
                                                                  (get-in new-system** [:config :channel-adapters])
                                                                  (:system-control new-system**)
                                                                  (:observer new-system**)
                                                                  (:trace new-system**))))
        new-system-ready (attach-telegram-service new-system***)
        result (reload-result :full old-cfg (:config new-system-ready) :reloaded)]
    (stop-runtime-edges! old-system)
    (let [new-system (if api-running?
                       (start-api! new-system-ready)
                       new-system-ready)]
      (doseq [component [:llm-provider :sqlite :broker :telemetry :runtime :chat
                         :tools :memory :channel-adapters]]
        (health/mark-ok! health-registry component))
      (reset! system-ref new-system)
      (reset! reload-state
              (assoc result
                     :source (:source opts)
                     :reloaded-at (util/now-str)))
      ((:event-sink new-system)
       {:event-type :system.config.reloaded
        :entity-type :system
        :entity-id "runtime"
        :payload result})
      (close-system! (assoc old-system
                            :chat-service nil
                            :telegram-service nil
                            :api-server nil))
      result)))

(defn- schedule-full-reload! [system opts]
  (let [system* (current-system system)
        reload-state (:reload-state system*)]
    (reset! reload-state
            {:mode :full
             :status :scheduled
             :source (:source opts)
             :scheduled-at (util/now-str)})
    (future
      (Thread/sleep (long (or (:delay-ms opts) 500)))
      (try
        (full-reload-now! system* opts)
        (catch Exception e
          (reset! reload-state
                  {:mode :full
                   :status :failed
                   :source (:source opts)
                   :message (.getMessage e)
                   :failed-at (util/now-str)})
          (logging/log-error! :agent.system.lifecycle/full-reload-failed e {}))))
    @reload-state))

(defn reload!
  ([system] (reload! system {}))
  ([system {:keys [mode] :as opts}]
   (try
     (case (or mode :soft)
       :soft (soft-reload! system opts)
       :full (schedule-full-reload! system opts)
       (throw (ex-info "Unsupported reload mode"
                       {:type :validation-failed
                        :mode mode})))
     (catch Exception e
       (health/mark-error! (:health-registry (current-system system)) :runtime e)
       (when-let [reload-state (:reload-state (current-system system))]
         (reset! reload-state
                 {:mode (or mode :soft)
                  :status :failed
                  :source (:source opts)
                  :message (.getMessage e)
                  :failed-at (util/now-str)}))
       (logging/log-error! :agent.system.lifecycle/reload-failed e {:mode mode})
       (throw e)))))

(defn start-api!
  [system]
  (let [{:keys [host port]} (:api (:config system))
        registry (:health-registry system)
        server (try
                 (api/start-server! system (:api (:config system)))
                 (catch Exception e
                   (health/mark-error! registry :api e)
                   (throw e)))
        telegram-service (try
                           (some-> (:telegram-service system)
                                   channel-adapters/start-adapter!)
                           (catch Exception e
                             (health/mark-error! registry :channel-adapters e)
                             (throw e)))]
    (health/mark-ok! registry :api)
    (health/mark-ok! registry :channel-adapters)
    (logging/log! :agent.api/started
                  {:host host
                   :port port})
    (let [system* (assoc system
                         :api-server server
                         :telegram-service telegram-service)]
      (when-let [system-ref (:system-ref system*)]
        (reset! system-ref system*))
      system*)))
