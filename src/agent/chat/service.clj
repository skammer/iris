(ns agent.chat.service
  "Chat service state. Holds active tasks, session status, streaming buffers,
   cancellation tokens, and helpers that emit state changes for UI/API clients."
  (:require
   [agent.chat.util :as chat-util]
   [agent.chat.queue-state :as queue-state]
   [agent.config :as config]
   [agent.logging :as logging]
   [agent.loop :as loop-support]
   [agent.runtime.messages :as runtime-messages])
  (:import
   (java.util UUID)
   (java.util.concurrent Callable Executors LinkedBlockingQueue ThreadPoolExecutor ThreadFactory TimeUnit RejectedExecutionException)
   (java.util.concurrent.atomic AtomicLong)))

(def stopped-content runtime-messages/stopped-content)

(defn request-id []
  (str (UUID/randomUUID)))

(defn- daemon-thread-factory [prefix]
  (let [counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(def default-limits
  {:max-concurrent-turns 16 :max-active-sessions 64
   :max-queued-per-session 32 :max-pending-turns 256})

(defn- bounded-executor [threads capacity prefix]
  (ThreadPoolExecutor. threads threads 0 TimeUnit/MILLISECONDS
                       (LinkedBlockingQueue. capacity) (daemon-thread-factory prefix)))

(defn create-service
  ([] (create-service {}))
  ([opts]
   (let [limits (merge default-limits (select-keys opts (keys default-limits)))]
     (doseq [[k v] limits]
       (when-not (and (integer? v) (pos? v))
         (throw (ex-info "Chat limits must be positive integers" {:type :validation-failed :key k :value v}))))
     {:stream-flush-scheduler (Executors/newSingleThreadScheduledExecutor
                               (daemon-thread-factory "iris-chat-stream-flush"))
      :turn-executor (bounded-executor (:max-concurrent-turns limits) (:max-pending-turns limits) "iris-chat-turn")
      :loop-executor (bounded-executor 4 16 "iris-chat-loop")
      :limits limits
      :loop-state (loop-support/create-service)
      :streaming-state (atom {})
      :session-runtimes (atom {})
      :loop-workers (atom {})
      :stopping? (atom false)
      :manager-lock (Object.)})))

(defn require-service [system]
  (or (:chat-service system)
      (throw (ex-info "chat-service missing from system"
                      {:type :chat-service-missing}))))

(defn stopping? [service]
  (true? @(:stopping? service)))

(defn ensure-running! [service]
  (when (stopping? service)
    (throw (ex-info "chat-service stopped" {:type :chat-service-stopped})))
  service)

(defn stopped-result
  ([]
   (stopped-result nil))
  ([request-id]
   (cond-> {:content stopped-content
            :stop-reason :cancelled
            :cancelled? true}
     request-id (assoc :request-id request-id))))

(defn deliver-stopped! [{:keys [result request-id]}]
  (when result
    (deliver result {:result (stopped-result request-id)})))

(defn submit! [service f]
  (ensure-running! service)
  (let [task (bound-fn [] (f))]
    (try
      (.submit (:turn-executor service)
               ^Callable (reify Callable (call [_] (task))))
      (catch RejectedExecutionException e
        (throw (ex-info "Chat executor is full or stopped" {:type :chat-overloaded} e))))))

(defn submit-loop! [service f]
  (submit! (assoc service :turn-executor (:loop-executor service)) f))

(defn stop!
  [service]
  (if service
    (let [{:keys [active queued workers]} (locking (:manager-lock service)
                                            (reset! (:stopping? service) true)
                                            (reset! (get-in service [:loop-state :states]) {})
                                            (let [runtimes @(:session-runtimes service)
                                                  workers (vals @(:loop-workers service))]
                                              (reset! (:session-runtimes service) {})
                                              (reset! (:loop-workers service) {})
                                              (reset! (:streaming-state service) {})
                                              {:active (keep :active (vals runtimes))
                                               :queued (mapcat #(seq (:queue %)) (vals runtimes))
                                               :workers workers}))]
      (doseq [{:keys [cancelled? future] :as item} active]
        (when cancelled? (reset! cancelled? true))
        (when future (.cancel future true))
        (deliver-stopped! item))
      (doseq [item queued]
        (deliver-stopped! item))
      (doseq [worker workers]
        (.cancel worker true))
      (.shutdownNow (:loop-executor service))
      (when-let [scheduler (:stream-flush-scheduler service)]
        (.shutdown scheduler)
        (when-not (.awaitTermination scheduler 1 TimeUnit/SECONDS)
          (.shutdownNow scheduler)))
      (when-let [executor (:turn-executor service)]
        (.shutdown executor)
        (when-not (.awaitTermination executor 1 TimeUnit/SECONDS)
          (.shutdownNow executor)))
      {:stopped true})
    {:stopped false}))

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

(defn active-llm
  [system]
  (config/active-provider-config (get-in system [:config :llm])))

(defn session-state
  [system session-id]
  (let [service (:chat-service system)
        {:keys [active queue]} (some-> service :session-runtimes deref
                                       (get session-id))
        llm (when system (active-llm system))
        loop-state (loop-support/active-state (get-in system [:chat-service :loop-state]) session-id)
        streaming (some-> service :streaming-state deref (get session-id))]
    (cond-> {:working? (boolean active)
             :queued-count (count queue)
             :active-provider (some-> (:provider llm) name)
             :active-model (:model llm)}
      active (assoc :active-request-id (:request-id active)
                    :active-started-at (:started-at active))
      streaming (assoc :streaming? true)
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
    (try
      (chat-util/emit! system {:event-type :session-state-changed
                             :entity-type :session
                             :entity-id session-id
                             :payload (state-event-payload (session-state system session-id)
                                                           reason)})
      (catch Exception e
        (logging/log-error! :agent.chat/session-notification-failed e {:session-id session-id})))))

(defn cancel-session!
  [system session-id]
  (if-let [service (:chat-service system)]
    (let [{:keys [active queued loop-worker]} (locking (:manager-lock service)
                                                (let [{:keys [active queued runtimes]}
                                                      (queue-state/cancel @(:session-runtimes service) session-id)
                                                      loop-worker (get @(:loop-workers service) session-id)]
                                                  (when-let [token (:cancelled? active)] (reset! token true))
                                                  (reset! (:session-runtimes service) runtimes)
                                                  (swap! (:streaming-state service) dissoc session-id)
                                                  (swap! (:loop-workers service) dissoc session-id)
                                                  {:active active
                                                   :queued queued
                                                   :loop-worker loop-worker}))]
      (when-let [{:keys [cancelled?]} active]
        (reset! cancelled? true))
      (when loop-worker
        (.cancel loop-worker true))
      (doseq [item queued]
        (deliver-stopped! item))
      (let [result {:session-id session-id
                    :cancelled-active? (boolean active)
                    :cleared-queued-count (count queued)
                    :queued-items queued}]
        (when (or active (seq queued))
          (emit-session-state! system session-id :cancel))
        result))
    {:session-id session-id
     :cancelled-active? false
     :cleared-queued-count 0}))

(defn streaming-state
  "Returns in-progress assistant text/thinking accumulated for `session-id`."
  [system session-id]
  (when session-id
    (let [value (some-> system :chat-service :streaming-state deref (get session-id))]
      (when (map? value)
        (select-keys value [:content :thinking])))))

(defn clear-streaming!
  ([system session-id] (clear-streaming! system session-id nil))
  ([system session-id request-id]
   (when session-id
     (when-let [state (some-> system :chat-service :streaming-state)]
       (swap! state (fn [states]
                      (if (or (nil? request-id) (= request-id (get-in states [session-id :request-id])))
                        (dissoc states session-id) states)))))))
