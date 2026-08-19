(ns agent.restart-handoff
  "Persists a session-local message before restart and dispatches it as the
   first automatic turn when the runtime starts again."
  (:require
   [agent.chat :as chat]
   [agent.logging :as logging]
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str]))

(def max-message-chars 20000)

(defn schedule!
  [system {:keys [session-id message permission-profile]}]
  (let [message* (some-> message str str/trim)]
    (when-not (some-> session-id str str/trim not-empty)
      (throw (ex-info "Restart handoff requires a chat session"
                      {:type :validation-failed})))
    (when (str/blank? message*)
      (throw (ex-info "Restart handoff message must not be blank"
                      {:type :validation-failed})))
    (when (> (count message*) max-message-chars)
      (throw (ex-info "Restart handoff message is too long"
                      {:type :validation-failed
                       :max-chars max-message-chars})))
    (sqlite/schedule-restart-handoff!
     (:store system)
     {:session-id session-id
      :message message*
      :permission-profile (or permission-profile :chat)})))

(defn- failed-result? [result]
  (or (:error? result) (:cancelled? result)))

(defn- run-handoff!
  [system {:keys [id session-id message permission-profile] :as handoff}]
  (try
    (let [user-message (sqlite/ensure-restart-handoff-message! (:store system) handoff)
          result (chat/run! system
                            {:session-id session-id
                             :messages [{:role "user" :content message}]
                             :persist-user? false
                             :user-message user-message
                             :permission-profile permission-profile
                             :context {:restart-handoff-id id}})]
      (if (failed-result? result)
        (sqlite/finish-restart-handoff! (:store system) id :failed
                                        (or (some-> (:error result) str)
                                            "Automatic turn failed"))
        (sqlite/finish-restart-handoff! (:store system) id :succeeded nil)))
    (catch Throwable error
      (try
        (sqlite/finish-restart-handoff! (:store system) id :failed (.getMessage error))
        (catch Throwable persistence-error
          (logging/log-error! :agent.restart-handoff/finish-failed
                              persistence-error
                              {:handoff-id id :session-id session-id})))
      (logging/log-error! :agent.restart-handoff/dispatch-failed
                          error
                          {:handoff-id id :session-id session-id}))))

(defn dispatch-pending!
  [system]
  (let [handoffs (sqlite/claim-restart-handoffs! (:store system))]
    (doseq [handoff handoffs]
      (future (run-handoff! system handoff)))
    (when (seq handoffs)
      (logging/log! :agent.restart-handoff/dispatched
                    {:count (count handoffs)
                     :handoff-ids (mapv :id handoffs)}))
    (count handoffs)))
