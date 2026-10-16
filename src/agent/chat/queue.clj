(ns agent.chat.queue
  "Per-session chat turn queue. Serializes turns for each session, supports
   cancellation, emits queue/session status events, and runs queued prompts
   through agent.chat.turn."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.chat.history :as history]
   [agent.chat.queue-state :as state]
   [agent.runtime.cancel :as cancel]
   [agent.logging :as logging]
   [agent.chat.loop-control :as loop-control]
   [agent.chat.service :as service]
   [agent.chat.turn :as turn]
   [agent.chat.util :as chat-util]
   [agent.loop :as loop-support]
   [agent.util :as util]))

(declare run! run-active-item!)

(defn- empty-queue []
  clojure.lang.PersistentQueue/EMPTY)

(defn- active-turn [request-id cancelled? result]
  {:request-id request-id
   :started-at (util/now-str)
   :cancelled? cancelled?
   :result result})

(defn- enqueue-item [state item]
  (update (or state {:queue (empty-queue)})
          :queue
          (fnil conj (empty-queue))
          item))

(defn- submit-active! [system {:keys [request-id result] :as item}]
  (let [service (service/require-service system)
        future (try
                 (service/submit! service #(run-active-item! system item))
                 (catch Throwable t
                   (let [failed (locking (:manager-lock service)
                                  (let [{:keys [active queue]} (get @(:session-runtimes service) (get-in item [:opts :session-id]))]
                                    (when (= request-id (:request-id active))
                                      (swap! (:session-runtimes service) dissoc (get-in item [:opts :session-id]))
                                      (cons active queue))))]
                     (doseq [pending failed]
                       (when-let [p (:result pending)] (deliver p {:error t})))
                     (when result (deliver result {:error t})))
                   (throw t)))]
    (locking (:manager-lock service)
      (when (= request-id (get-in @(:session-runtimes service)
                                  [(:session-id (:opts item)) :active :request-id]))
        (swap! (:session-runtimes service)
               assoc-in
               [(:session-id (:opts item)) :active :future]
               future)))
    future))

(defn- start-next-queued! [system session-id request-id terminal-reason]
  (let [service (service/require-service system)
        {:keys [item cleared?]} (locking (:manager-lock service)
                                  (let [transition (state/advance @(:session-runtimes service)
                                                                  session-id request-id (service/stopping? service)
                                                                  #(active-turn (:request-id %) (:cancelled? %) (:result %)))]
                                    (reset! (:session-runtimes service) (:runtimes transition))
                                    transition))]
    (cond
      item
      (do
        (service/emit-session-state! system session-id :drain)
        (submit-active! system item))

      cleared?
      (service/emit-session-state! system session-id terminal-reason))))

(defn- terminal-state-reason [result]
  (cond
    (:cancelled? result) :cancel
    (:error? result) :error
    :else :complete))

(defn- run-active-item! [system {:keys [opts request-id cancelled? queued-message result]}]
  (let [terminal-reason (atom :complete)]
    (try
      (let [_ (cancel/throw-if-cancelled! cancelled?)
            activated-message (history/activate-queued-message! system {:queued-message queued-message
                                                                        :request-id request-id})
            result* (turn/run-turn! system
                                    (cond-> (assoc opts
                                                   :request-id request-id
                                                   :cancellation-token cancelled?)
                                      queued-message (assoc :persist-user? false
                                                            :user-message activated-message)))]
        (reset! terminal-reason (terminal-state-reason result*))
        (when result
          (deliver result {:result result*}))
        result*)
      (catch Throwable t
        (reset! terminal-reason :error)
        (if (and cancelled? @cancelled?)
          (let [result* (service/stopped-result request-id)]
            (reset! terminal-reason :cancel)
            (try
              (when-let [session-id (:session-id opts)]
                (history/append-message! system session-id "assistant" service/stopped-content
                                         {:metadata {:request-id request-id}}))
              (catch Throwable persistence-error
                (when result (deliver result {:error persistence-error}))
                (throw persistence-error)))
            (when result
              (deliver result {:result result*}))
            result*)
          (do
            (when result
              (deliver result {:error t}))
            (throw t))))
      (finally
        (start-next-queued! system (:session-id opts) request-id @terminal-reason)))))

(defn- begin-managed-run! [system {:keys [session-id] :as opts} request-id cancelled? result]
  (let [service (service/require-service system)]
    (locking (:manager-lock service)
      (service/ensure-running! service)
      (when (state/full? @(:session-runtimes service) session-id (:limits service))
        (throw (ex-info "Chat queue is full" {:type :chat-overloaded :session-id session-id})))
      (if (get-in @(:session-runtimes service) [session-id :active])
        (let [queued-message (or (when (false? (:persist-user? opts))
                                   (:user-message opts))
                                 (history/persist-queued-user-turn!
                                  system session-id (:messages opts) request-id))
              item {:opts opts
                    :request-id request-id
                    :cancelled? cancelled?
                    :queued-message queued-message
                    :result result}]
          (swap! (:session-runtimes service) update session-id enqueue-item item)
          (try
            (chat-util/emit! system {:event-type :turn-queued
                                   :entity-type :session
                                   :entity-id session-id
                                   :request-id request-id
                                   :payload {:message-id (:id queued-message)
                                             :queued-count (get-in (service/session-state system session-id)
                                                                   [:queued-count])}})
            (catch Exception e
              (logging/log-error! :agent.chat/queue-notification-failed e {:session-id session-id})))
          (service/emit-session-state! system session-id :queued)
          :queued)
        (do
          (swap! (:session-runtimes service) assoc-in [session-id :active]
                 (active-turn request-id cancelled? result))
          (service/emit-session-state! system session-id :start)
          :active)))))

(defn- await-result! [result]
  (let [{:keys [result error]} @result]
    (if error
      (throw error)
      result)))

(defn cancel-session! [system session-id]
  (let [{:keys [queued-items] :as result} (service/cancel-session! system session-id)]
    (doseq [{:keys [queued-message request-id]} queued-items]
      (history/mark-queued-message-cancelled! system queued-message request-id))
    (dissoc result :queued-items)))

(defn run!
  "Run or queue a chat turn for `session-id`."
  [system {:keys [session-id] :as opts}]
  (let [prompt (history/latest-user-prompt (:messages opts))]
    (cond
      (not session-id)
      (try
        (.get (service/submit! (service/require-service system) #(turn/run-turn! system opts)))
        (catch java.util.concurrent.ExecutionException e (throw (.getCause e))))

      (and (not (:loop-turn? opts))
           (loop-support/control-command prompt))
      (loop-control/loop-command! system session-id prompt run!)

      (and (not (:loop-turn? opts))
           (loop-support/active? (get-in system [:chat-service :loop-state]) session-id))
      (loop-control/block-while-loop-active! system session-id prompt)

      :else
      (let [request-id* (or (:request-id opts) (service/request-id))
            cancelled? (atom false)
            result (promise)
            mode (begin-managed-run! system opts request-id* cancelled? result)]
        (case mode
          :active
          (do
            (submit-active! system {:opts opts
                                    :request-id request-id*
                                    :cancelled? cancelled?
                                    :result result})
            (await-result! result))

          :queued
          (await-result! result))))))
