(ns agent.chat.loop-control
  "Loop control commands and background loop worker."
  (:require
   [agent.chat.history :as history]
   [agent.chat.service :as service]
   [agent.loop :as loop-support]))

(defn- loop-worker-running? [system session-id]
  (when-let [worker (get @(-> system service/require-service :loop-workers) session-id)]
    (not (future-done? worker))))

(defn- loop-complete-message [record]
  (or (:content record)
      "Loop stopped."))

(defn- run-loop-worker! [system session-id run-fn]
  (try
    (loop []
      (when-let [state (when-not (service/stopping? (service/require-service system))
                         (loop-support/prepare-iteration! (get-in system [:chat-service :loop-state]) session-id))]
        (let [result (run-fn system
                             {:messages [{:role "user"
                                          :content (loop-support/build-prompt state)}]
                              :session-id session-id
                              :loop-turn? true})
              loop-opts (loop-support/options (:config system) {})
              validation (loop-support/run-validation (:run-cmd state) loop-opts)
              record (loop-support/record-result! (get-in system [:chat-service :loop-state]) session-id
                                                  (:content result)
                                                  validation
                                                  (:config system))]
          (when (:stopped? record)
            (history/append-message! system session-id "assistant" (loop-complete-message record)
                                     {:metadata {:loop-control true}}))
          (when (loop-support/active? (get-in system [:chat-service :loop-state]) session-id)
            (recur)))))
    (catch Throwable t
      (when (loop-support/active? (get-in system [:chat-service :loop-state]) session-id)
        (loop-support/stop! (get-in system [:chat-service :loop-state]) session-id)
        (history/append-message! system session-id "assistant"
                                 (str "Loop stopped: " (.getMessage t))
                                 {:metadata {:loop-control true :error true}})))
    (finally
      (swap! (:loop-workers (service/require-service system)) dissoc session-id)
      (service/emit-session-state! system session-id :loop))))

(defn start-loop-worker! [system session-id run-fn]
  (when-not (loop-worker-running? system session-id)
    (try
      (let [worker (service/submit-loop! (service/require-service system)
                                       #(run-loop-worker! system session-id run-fn))]
        (swap! (:loop-workers (service/require-service system)) assoc session-id worker))
      (catch Exception e
        (loop-support/stop! (get-in system [:chat-service :loop-state]) session-id)
        (service/emit-session-state! system session-id :error)
        (throw e)))))

(defn loop-command!
  [system session-id text run-fn]
  (when-let [{:keys [content started? stopped?] :as result}
             (loop-support/handle-control! (get-in system [:chat-service :loop-state]) session-id (:config system) text)]
    (when stopped?
      (service/cancel-session! system session-id))
    (let [response (history/append-control-turn! system
                                                 session-id
                                                 text
                                                 content
                                                 {:loop-control true})]
      (when started?
        (start-loop-worker! system session-id run-fn))
      (assoc response :loop-control result))))

(defn block-while-loop-active! [system session-id text]
  (history/append-control-turn! system
                                session-id
                                text
                                "Loop active. Use /loop status or /loop stop."
                                {:loop-control true :blocked-by-loop true}))
