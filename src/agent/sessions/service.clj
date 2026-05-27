(ns agent.sessions.service
  "Session store facade with system event logging."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.system.events :as events]))

(defn create-session!
  ([system] (create-session! system nil))
  ([system title]
   (let [session (sqlite/create-session! (:store system) title)]
     (events/log-event! system
                        {:event-type :session.created
                         :entity-type :session
                         :entity-id (:id session)
                         :payload {:title title}})
     session)))

(defn list-sessions
  [system]
  (sqlite/list-sessions (:store system)))

(defn session-exists?
  [system session-id]
  (sqlite/session-exists? (:store system) session-id))

(defn list-messages
  [system session-id]
  (sqlite/list-messages (:store system) session-id))
