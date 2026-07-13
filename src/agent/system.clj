(ns agent.system
  "Top-level Iris lifecycle. Builds the component graph, keeps the live system
   reference used by reloads, starts/stops API and channel adapters, and swaps
   runtime config without forcing callers to know component wiring."
  (:require
   [agent.api :as api]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.health :as health]
   [agent.llm.registry :as llm-registry]
   [agent.llm.service :as llm-service]
   [agent.logging :as logging]
   [agent.magi.core :as magi]
   [agent.memory.idle :as memory-idle]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.persistence.sqlite :as sqlite]
   [agent.system.components :as components]
   [agent.system.health :as system-health]
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

(defn- safe-stop!
  [label f]
  (try
    (f)
    (catch Exception e
      (logging/log-error! label e {}))))

(defn- stop-api-server!
  [system]
  (when-let [server (:api-server system)]
    (logging/log! :agent.api/stopping {})
    (api/stop-server! server)))

(defn- stop-runtime-edges!
  [system]
  (safe-stop! :agent.system.lifecycle/memory-magi-review-stop-failed
              #(some-> (:memory-magi-review-service system) memory-magi-review/stop!))
  (safe-stop! :agent.system.lifecycle/memory-idle-stop-failed
              #(some-> (:memory-idle-service system) memory-idle/stop!))
  (safe-stop! :agent.system.lifecycle/chat-stop-failed
              #(some-> (:chat-service system) chat/stop!))
  (safe-stop! :agent.system.lifecycle/telegram-stop-failed
              #(some-> (:telegram-service system) channel-adapters/stop-adapter!))
  (safe-stop! :agent.system.lifecycle/api-stop-failed
              #(stop-api-server! system)))

(defn- rebuild-hot-system [old-system new-cfg]
  (let [health-registry (:health-registry old-system)
        llm-cfg (config/llm-config new-cfg)
        llm-provider (system-health/with-component-health health-registry :llm-provider
                       #(llm-service/create-llm-provider llm-cfg))
        magi-service (magi/create-service new-cfg {:default-provider llm-provider})
        memory-service (system-health/with-component-health health-registry :memory
                         #(components/create-memory-service (:memory new-cfg)
                                                           (:tools new-cfg)
                                                           (:store old-system)
                                                           llm-cfg
                                                           llm-provider))
        observer (components/create-observer (:telemetry old-system) (:observer new-cfg))
        trace (components/create-trace new-cfg)
        base (assoc old-system
                    :config new-cfg
                    :llm-registry (llm-registry/create-registry llm-cfg)
                    :chat-service (system-health/with-component-health health-registry :chat
                                    #(chat/create-service))
                    :llm-provider llm-provider
                    :magi-service magi-service
                    :note-llm-provider (system-health/with-component-health health-registry :llm-provider
                                         #(llm-service/create-note-llm-provider new-cfg))
                    :observer observer
                    :trace trace
                    :memory-service memory-service
                    :memory-idle-service (components/create-memory-idle-service
                                          (:system-ref old-system))
                    :memory-magi-review-service (components/create-memory-magi-review-service
                                                 (:system-ref old-system))
                    :skills-registry (components/create-skills-registry (:skills new-cfg)))]
    (->> (components/attach-telegram-service
          (assoc base :tool-registry (components/build-tool-registry base new-cfg)))
         (replace-running-telegram! old-system))))

(defn- soft-reload! [system opts]
  (let [system* (current-system system)
        system-ref (:system-ref system*)
        old-cfg (:config system*)
        new-cfg (config/load-config (:config-path system*))
        idle-running? (memory-idle/running? (:memory-idle-service system*))
        new-system (rebuild-hot-system system* new-cfg)
        result (reload-result :soft old-cfg new-cfg :reloaded)]
    (chat/stop! (:chat-service system*))
    (memory-idle/stop! (:memory-idle-service system*))
    (memory-magi-review/stop! (:memory-magi-review-service system*))
    (logging/start! (:logging new-cfg))
    (reset! system-ref new-system)
    (when idle-running?
      (memory-idle/start! (:memory-idle-service new-system)))
    (memory-magi-review/start! (:memory-magi-review-service new-system))
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
  (stop-runtime-edges! system)
  (safe-stop! :agent.system.lifecycle/store-close-failed
              #(some-> (:store system) sqlite/close-store!)))

(defn- full-reload-now! [system opts]
  (let [old-system (current-system system)
        system-ref (:system-ref old-system)
        reload-state (:reload-state old-system)
        health-registry (:health-registry old-system)
        old-cfg (:config old-system)
        api-running? (some? (:api-server old-system))
        _ (health/bump-restart! health-registry :runtime)
        ;; Build the replacement against the live refs so component health
        ;; marks land on the real registry and the tool registry captures the
        ;; real control — no post-hoc rebuild needed.
        new-system-ready (try
                           (components/create-system-components
                            (:config-path old-system)
                            system-ref
                            reload-state
                            health-registry
                            (:system-control old-system))
                           (catch Exception e
                             (health/mark-error! health-registry :runtime e)
                             (throw e)))
        result (reload-result :full old-cfg (:config new-system-ready) :reloaded)]
    (stop-runtime-edges! old-system)
    (let [new-system (if api-running?
                       (start-api! new-system-ready)
                       new-system-ready)]
      (health/mark-ok! health-registry :runtime)
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
      (memory-idle/start! (:memory-idle-service system*))
      (memory-magi-review/start! (:memory-magi-review-service system*))
      system*)))
