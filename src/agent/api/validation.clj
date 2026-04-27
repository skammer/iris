(ns agent.api.validation
  "Semantic helpers and body normalizers shared across handler namespaces.
   Pure shape validation now lives in route-level malli :parameters schemas."
  (:require
   [agent.api.errors :as errors]
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str]))

(defn session-exists? [system session-id]
  (sqlite/session-exists? (:store system) session-id))

(defn ensure-session-exists! [system session-id]
  (when (and session-id (not (session-exists? system session-id)))
    (throw (errors/api-error 404 "session_not_found" "Session not found"))))

(defn emit-system-event!
  [system event]
  (if-let [sink (:event-sink system)]
    (sink event)
    (sqlite/log-event! (:store system) event)))

(defn normalize-trust-policies-body [policies]
  (reduce-kv (fn [acc peer-ref policy]
               (assoc acc (if (keyword? peer-ref) (name peer-ref) (str peer-ref))
                      {:message-types (vec (:message_types policy []))
                       :routes (vec (:routes policy []))
                       :required-capabilities (vec (:required_capabilities policy []))}))
             {}
             (or policies {})))

(defn normalize-chat-request [body]
  (let [messages (:messages body)
        prompt (:prompt body)
        session-id (:session_id body)
        stream? (true? (:stream body))]
    (when (and messages prompt)
      (throw (errors/api-error 400 "bad_request" "Provide either messages or prompt, not both")))
    (cond
      (some? messages)
      {:messages messages
       :session-id session-id
       :stream? stream?}

      (some? prompt)
      (do
        (when (str/blank? prompt)
          (throw (errors/api-error 400 "bad_request" "prompt must not be blank")))
        {:messages [{:role "user" :content prompt}]
         :session-id session-id
         :stream? stream?})

      :else
      (throw (errors/api-error 400 "bad_request" "Expected messages vector or prompt string")))))
