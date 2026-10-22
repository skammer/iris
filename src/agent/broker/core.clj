(ns agent.broker.core
  "In-process event broker contract. Publishers emit subject/payload messages;
   subscribers receive matching live messages, optional replay results, or
   request/reply responses without knowing the concrete backend."
  (:require
   [clojure.string :as str]))

(defprotocol IBroker
  (publish! [this message])
  (subscribe! [this pattern] [this pattern opts])
  (unsubscribe! [this subscription])
  (replay! [this pattern] [this pattern opts])
  (request! [this subject payload] [this subject payload opts])
  (health-check [this]))

(defn wildcard-pattern? [pattern]
  (str/ends-with? (str pattern) ">"))

(defn match-subject?
  [pattern subject]
  (let [pattern* (str pattern)
        subject* (str subject)]
    (cond
      (= pattern* subject*) true
      (wildcard-pattern? pattern*)
      (let [prefix (subs pattern* 0 (dec (count pattern*)))]
        (str/starts-with? subject* prefix))
      :else false)))

(defn all-events-subject [] "events.>")
(defn reply-subject [request-id] (str "reply." request-id))

(defn session-events-subject [session-id]
  (str "session.events." session-id ".events"))

(defn subject-session-id [subject]
  (let [value (str subject) prefix "session.events." suffix ".events"]
    (when (and (str/starts-with? value prefix) (str/ends-with? value suffix)
               (> (count value) (+ (count prefix) (count suffix))))
      (subs value (count prefix) (- (count value) (count suffix))))))

(defn event->subjects
  [event]
  (cond-> ["events.all"]
    (and (= "session" (some-> (:entity-type event) name)) (:entity-id event))
    (conj (session-events-subject (:entity-id event)))))

(defn event->messages [event]
  (mapv (fn [subject] {:subject subject :payload event})
        (event->subjects event)))
