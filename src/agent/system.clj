(ns agent.system
  "Top-level Iris lifecycle. Builds the component graph, keeps the live system
   reference used by reloads, starts/stops API and channel adapters, and swaps
   runtime config without forcing callers to know component wiring."
  (:require
   [agent.api :as api]
   [agent.api.ui-state :as ui-state]
   [agent.channels.core :as channel-adapters]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.cron.service :as cron]
   [agent.health :as health]
   [agent.logging :as logging]
   [agent.memory.idle :as memory-idle]
   [agent.memory.magi-review :as memory-magi-review]
   [agent.persistence.sqlite :as sqlite]
   [agent.restart-handoff :as restart-handoff]
   [agent.system.components :as components]
   [agent.system.health :as system-health]
   [agent.system.resources :as resources]
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

(defn- telegram-config [system]
  (get-in system [:config :channel-adapters :telegram]))

(defn- refresh-telegram [old-system new-system]
  (if (= (telegram-config old-system) (telegram-config new-system))
    (assoc new-system
           :telegram-service (:telegram-service old-system)
           :channel-adapter-registry (:channel-adapter-registry old-system))
    (components/attach-telegram-service new-system)))

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
  (safe-stop! :agent.system.lifecycle/ui-stop-failed #(ui-state/close! (:ui-state system)))
  (safe-stop! :agent.system.lifecycle/cron-stop-failed
              #(some-> (:cron-service system) cron/stop!))
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
  (refresh-telegram old-system (components/rebuild-runtime-components old-system new-cfg)))

(defn- background-running [system]
  {:cron (cron/running? (:cron-service system))
   :idle (memory-idle/running? (:memory-idle-service system))
   :review (boolean (some-> system :memory-magi-review-service :running? deref))})

(defn- stop-background! [system]
  (cron/stop! (:cron-service system))
  (memory-idle/stop! (:memory-idle-service system))
  (memory-magi-review/stop! (:memory-magi-review-service system)))

(defn- start-background! [system flags own!]
  (doseq [[flag key start! stop!] [[:idle :memory-idle-service memory-idle/start! memory-idle/stop!]
                                  [:review :memory-magi-review-service memory-magi-review/start! memory-magi-review/stop!]
                                  [:cron :cron-service cron/start! cron/stop!]]
          :when (get flags flag)]
    (start! (own! (get system key) stop!))))

(defn- soft-reload! [system opts]
  (let [old (current-system system)
        system-ref (:system-ref old)
        new-cfg (config/load-config (:config-path old))
        flags (background-running old)
        new (rebuild-hot-system old new-cfg)
        adapter-changed? (not (identical? (:telegram-service old) (:telegram-service new)))
        adapter-running? (and adapter-changed? (some-> (:telegram-service old) running-adapter?))
        result (reload-result :soft (:config old) new-cfg :reloaded)]
    (resources/with-rollback
     (fn [own!]
       (own! old (fn [previous]
                   (reset! system-ref previous)
                   (logging/start! (get-in previous [:config :logging]))
                   (when adapter-running?
                     (channel-adapters/start-adapter! (:telegram-service previous)))
                   (start-background! previous flags (fn [value _] value))))
       (stop-background! old)
       (when adapter-running?
         (channel-adapters/stop-adapter! (:telegram-service old)))
       (logging/start! (:logging new-cfg))
       (reset! system-ref new)
       (when adapter-running?
         (let [started (channel-adapters/start-adapter!
                        (own! (:telegram-service new) channel-adapters/stop-adapter!))]
           (swap! system-ref assoc :telegram-service started)))
       (start-background! @system-ref flags own!)
       ((:event-sink @system-ref)
        {:event-type :system.config.reloaded :entity-type :system
         :entity-id "runtime" :payload result})
       (reset! (:reload-state old)
               (assoc result :source (:source opts) :reloaded-at (util/now-str)))
       result))))

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
    ;; Keep the old queue alive until the replacement starts successfully.
    ;; A stopped chat executor cannot be restarted during rollback.
    (stop-runtime-edges! (assoc old-system :chat-service nil))
    (let [new-system
          (try
            (if api-running? (start-api! new-system-ready) new-system-ready)
            (catch Throwable error
              (close-system! new-system-ready)
              (reset! system-ref old-system)
              (try
                (logging/start! (:logging old-cfg))
                (when api-running?
                  (reset! system-ref (start-api! (dissoc old-system :api-server))))
                (catch Throwable recovery-error
                  (.addSuppressed error recovery-error)
                  ;; No live replacement remains. Do not leak the old resources.
                  (close-system! old-system)))
              (health/mark-error! health-registry :runtime error)
              (throw error)))]
      (reset! system-ref new-system)
      (close-system! (assoc old-system :telegram-service nil :api-server nil
                            :cron-service nil :memory-idle-service nil
                            :memory-magi-review-service nil))
      (health/mark-ok! health-registry :runtime)
      (reset! reload-state
              (assoc result :source (:source opts) :reloaded-at (util/now-str)))
      ((:event-sink new-system)
       {:event-type :system.config.reloaded :entity-type :system
        :entity-id "runtime" :payload result})
      result)))

(defn- schedule-full-reload! [system opts]
  (let [system* (current-system system)
        reload-state (:reload-state system*)
        validation (config/validate-effective-config (:config-path system*))]
    (reset! reload-state
            {:mode :full
             :status :scheduled
             :source (:source opts)
             :validation validation
             :scheduled-at (util/now-str)})
    (future
      (Thread/sleep (long (or (:delay-ms opts) 500)))
      (try
        (locking (:system-ref system*)
          (full-reload-now! system* opts))
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
       :soft (locking (:system-ref system) (soft-reload! system opts))
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
  (resources/with-rollback
   (fn [own!]
     (let [registry (:health-registry system)
           system-ref (:system-ref system)
           previous (some-> system-ref deref)
           _ (when system-ref (own! previous #(reset! system-ref %)))
           server (own! (system-health/with-component-health registry :api
                          #(api/start-server! system (:api (:config system))))
                        api/stop-server!)
           telegram (own! (:telegram-service system)
                          #(when % (channel-adapters/stop-adapter! %)))
           started-telegram (system-health/with-component-health registry :channel-adapters
                              #(some-> telegram channel-adapters/start-adapter!))
           started (assoc system :api-server server :telegram-service started-telegram)]
       (when system-ref (reset! system-ref started))
       (start-background! started {:idle true :review true :cron true} own!)
       (restart-handoff/dispatch-pending! started)
       started))))
