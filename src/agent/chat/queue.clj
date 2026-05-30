(ns agent.chat.queue
  "Per-session chat turn queue."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.chat.history :as history]
   [agent.chat.loop-control :as loop-control]
   [agent.chat.service :as service]
   [agent.chat.turn :as turn]
   [agent.chat.util :as chat-util]
   [agent.loop :as loop-support]
   [agent.util :as util]))

(declare run! run-queued-item!)

(defn- empty-queue []
  clojure.lang.PersistentQueue/EMPTY)

(defn- active-turn [request-id cancelled? stream?]
  {:request-id request-id
   :started-at (util/now-str)
   :cancelled? cancelled?
   :stream? (boolean stream?)
   :stream-state (atom {})})

(defn- enqueue-item [state item]
  (update (or state {:queue (empty-queue)})
          :queue
          (fnil conj (empty-queue))
          item))

(defn- start-next-queued! [system session-id request-id terminal-reason]
  (let [service (service/require-service system)
        {:keys [item cleared?]} (locking (:manager-lock service)
                                  (let [{:keys [active queue]} (get @(:session-runtimes service) session-id)]
                                    (when (= request-id (:request-id active))
                                      (if (seq queue)
                                        (let [item (peek queue)
                                              queue* (pop queue)]
                                          (swap! (:session-runtimes service) assoc session-id
                                                 {:active (active-turn (:request-id item)
                                                                       (:cancelled? item)
                                                                       (get-in item [:opts :stream?]))
                                                  :queue queue*})
                                          {:item item})
                                        (do
                                          (swap! (:session-runtimes service) dissoc session-id)
                                          {:cleared? true})))))]
    (cond
      item
      (do
        (service/emit-session-state! system session-id :drain)
        (future (run-queued-item! system item)))

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
      (let [activated-message (history/activate-queued-message! system {:queued-message queued-message
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
        (when result
          (deliver result {:error t}))
        (throw t))
      (finally
        (start-next-queued! system (:session-id opts) request-id @terminal-reason)))))

(defn- run-queued-item! [system item]
  (run-active-item! system item))

(defn- begin-managed-run! [system {:keys [session-id stream?] :as opts} request-id cancelled? result]
  (let [service (service/require-service system)]
    (locking (:manager-lock service)
      (if (get-in @(:session-runtimes service) [session-id :active])
        (let [queued-message (history/persist-queued-user-turn! system session-id (:messages opts) request-id)
              item {:opts opts
                    :request-id request-id
                    :cancelled? cancelled?
                    :queued-message queued-message
                    :result result}]
          (swap! (:session-runtimes service) update session-id enqueue-item item)
          (chat-util/emit! system {:event-type :turn-queued
                                   :entity-type :session
                                   :entity-id session-id
                                   :request-id request-id
                                   :payload {:message-id (:id queued-message)
                                             :queued-count (get-in (service/session-state system session-id)
                                                                   [:queued-count])}})
          (service/emit-session-state! system session-id :queued)
          :queued)
        (do
          (swap! (:session-runtimes service) assoc-in [session-id :active]
                 (active-turn request-id cancelled? stream?))
          (service/emit-session-state! system session-id :start)
          :active)))))

(defn run!
  "Run or queue a chat turn for `session-id`."
  [system {:keys [session-id] :as opts}]
  (let [prompt (history/latest-user-prompt (:messages opts))]
    (cond
      (not session-id)
      (turn/run-turn! system opts)

      (and (not (:loop-turn? opts))
           (loop-support/control-command prompt))
      (loop-control/loop-command! system session-id prompt run!)

      (and (not (:loop-turn? opts))
           (loop-support/active? session-id))
      (loop-control/block-while-loop-active! system session-id prompt)

      :else
      (let [request-id* (or (:request-id opts) (service/request-id))
            cancelled? (atom false)
            result (promise)
            mode (begin-managed-run! system opts request-id* cancelled? result)]
        (case mode
          :active
          (run-active-item! system {:opts opts
                                    :request-id request-id*
                                    :cancelled? cancelled?})

          :queued
          (let [{:keys [result error]} @result]
            (if error
              (throw error)
              result)))))))
