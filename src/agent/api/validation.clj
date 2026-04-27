(ns agent.api.validation
  "Input validation, body normalization, session helpers, and event emission
   shared across handler namespaces."
  (:require
   [agent.api.errors :as errors]
   [agent.persistence.sqlite :as sqlite]
   [clojure.string :as str]))

(def valid-roles #{"system" "user" "assistant" "tool"})

(defn valid-message? [message]
  (and (map? message)
       (string? (:role message))
       (contains? valid-roles (:role message))
       (string? (:content message))
       (not (str/blank? (:content message)))))

(defn ensure-string! [field value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (errors/api-error 400 "bad_request"
                             (str (name field) " must be a non-blank string")))))

(defn ensure-string-vec! [field value]
  (when-not (and (vector? value) (every? string? value))
    (throw (errors/api-error 400 "bad_request"
                             (str (name field) " must be a vector of strings")))))

(defn ensure-session-id! [session-id]
  (when (and (some? session-id) (not (string? session-id)))
    (throw (errors/api-error 400 "bad_request" "session_id must be a string"))))

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
               (let [message-types (vec (or (:message_types policy)
                                            (:message-types policy)
                                            []))
                     routes (vec (or (:routes policy) []))
                     required-capabilities (vec (or (:required_capabilities policy)
                                                    (:required-capabilities policy)
                                                    []))]
                 (ensure-string-vec! :message_types message-types)
                 (ensure-string-vec! :routes routes)
                 (ensure-string-vec! :required_capabilities required-capabilities)
                 (assoc acc (if (keyword? peer-ref) (name peer-ref) (str peer-ref))
                        {:message-types message-types
                         :routes routes
                         :required-capabilities required-capabilities})))
             {}
             (or policies {})))

(defn normalize-chat-request [body]
  (let [messages (:messages body)
        prompt (:prompt body)
        session-id (:session_id body)
        stream? (true? (:stream body))]
    (ensure-session-id! session-id)
    (when (and messages prompt)
      (throw (errors/api-error 400 "bad_request" "Provide either messages or prompt, not both")))
    (cond
      (some? messages)
      (do
        (when-not (vector? messages)
          (throw (errors/api-error 400 "bad_request" "messages must be a vector")))
        (when (empty? messages)
          (throw (errors/api-error 400 "bad_request" "messages must not be empty")))
        (when-not (every? valid-message? messages)
          (throw (errors/api-error 400 "bad_request"
                                   "each message must include valid string role and content")))
        {:messages messages
         :session-id session-id
         :stream? stream?})

      (string? prompt)
      (do
        (when (str/blank? prompt)
          (throw (errors/api-error 400 "bad_request" "prompt must not be blank")))
        {:messages [{:role "user" :content prompt}]
         :session-id session-id
         :stream? stream?})

      :else
      (throw (errors/api-error 400 "bad_request" "Expected messages vector or prompt string")))))
