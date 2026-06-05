(ns agent.chat.service
  "Chat service state and session status events."
  (:require
   [agent.chat.util :as chat-util]
   [agent.config :as config]
   [agent.loop :as loop-support]
   [agent.runtime.loop :as runtime-loop])
  (:import
   (java.util UUID)
   (java.util.concurrent Executors ThreadFactory TimeUnit)))

(def stopped-content runtime-loop/stopped-content)

(defn request-id []
  (str (UUID/randomUUID)))

(defn create-service
  []
  {:stream-flush-scheduler (Executors/newSingleThreadScheduledExecutor
                            (reify ThreadFactory
                              (newThread [_ runnable]
                                (doto (Thread. runnable "iris-chat-stream-flush")
                                  (.setDaemon true)))))
   :streaming-state (atom {})
   :session-runtimes (atom {})
   :loop-workers (atom {})
   :manager-lock (Object.)})

(defn require-service [system]
  (or (:chat-service system)
      (throw (ex-info "chat-service missing from system"
                      {:type :chat-service-missing}))))

(defn stop!
  [service]
  (if service
    (do
      (doseq [worker (vals @(:loop-workers service))]
        (future-cancel worker))
      (when-let [scheduler (:stream-flush-scheduler service)]
        (.shutdown scheduler)
        (when-not (.awaitTermination scheduler 1 TimeUnit/SECONDS)
          (.shutdownNow scheduler)))
      (reset! (:loop-workers service) {})
      (reset! (:session-runtimes service) {})
      (reset! (:streaming-state service) {})
      {:stopped true})
    {:stopped false}))

(defn reload!
  [service]
  (stop! service)
  (create-service))

(defn health-check
  [service]
  (if service
    (let [runtimes @(:session-runtimes service)]
      {:healthy true
       :active-session-count (count (filter :active (vals runtimes)))
       :queued-count (reduce + (map #(count (:queue %)) (vals runtimes)))
       :loop-worker-count (count @(:loop-workers service))
       :streaming-session-count (count @(:streaming-state service))})
    {:healthy false
     :reason "chat-service missing"}))

(defn active-run
  [system session-id]
  (when-let [run (and session-id
                      (some-> system :chat-service :session-runtimes deref
                              (get-in [session-id :active])))]
    (select-keys run [:request-id :started-at])))

(defn active? [system session-id]
  (boolean (active-run system session-id)))

(defn active-llm
  [system]
  (config/active-provider-config (get-in system [:config :llm])))

(defn session-state
  [system session-id]
  (let [{:keys [active queue]} (some-> system :chat-service :session-runtimes deref
                                       (get session-id))
        llm (when system (active-llm system))
        loop-state (loop-support/active-state session-id)]
    (cond-> {:working? (boolean active)
             :queued-count (count queue)
             :active-provider (some-> (:provider llm) name)
             :active-model (:model llm)}
      active (assoc :active-request-id (:request-id active)
                    :active-started-at (:started-at active))
      loop-state (assoc :loop-active? true
                        :loop-label (loop-support/iteration-label loop-state)
                        :loop-plan (:plan-file loop-state)))))

(defn- state-event-payload [state reason]
  {:working (boolean (:working? state))
   :queued-count (:queued-count state 0)
   :active-provider (:active-provider state)
   :active-model (:active-model state)
   :active-request-id (:active-request-id state)
   :active-started-at (:active-started-at state)
   :reason (name reason)})

(defn emit-session-state! [system session-id reason]
  (when (and system session-id)
    (chat-util/emit! system {:event-type :session-state-changed
                             :entity-type :session
                             :entity-id session-id
                             :payload (state-event-payload (session-state system session-id)
                                                           reason)})))

(defn cancel-session!
  [system session-id]
  (if-let [service (:chat-service system)]
    (let [result (locking (:manager-lock service)
                   (if-let [{:keys [cancelled? request-id]}
                            (and session-id
                                 (get-in @(:session-runtimes service) [session-id :active]))]
                     (do
                       (reset! cancelled? true)
                       {:cancelled? true
                        :session-id session-id
                        :request-id request-id})
                     {:cancelled? false
                      :session-id session-id}))]
      (when (:cancelled? result)
        (emit-session-state! system session-id :cancel))
      result)
    {:cancelled? false
     :session-id session-id}))

(defn streaming-content
  "Returns in-progress assistant text accumulated for `session-id`, or nil."
  [system session-id]
  (when session-id
    (let [value (some-> system :chat-service :streaming-state deref (get session-id))]
      (if (map? value) (:content value) value))))

(defn streaming-thinking
  "Returns in-progress assistant thinking text accumulated for `session-id`, or nil."
  [system session-id]
  (when session-id
    (let [value (some-> system :chat-service :streaming-state deref (get session-id))]
      (when (map? value)
        (:thinking value)))))

(defn clear-streaming! [system session-id]
  (when session-id
    (when-let [state (some-> system :chat-service :streaming-state)]
      (swap! state dissoc session-id))))
