(ns agent.broker.core
  "Broker abstraction for local/runtime event transport."
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

(defn event->subjects
  [_event]
  ["events.all"])

(defn event->messages [event]
  (mapv (fn [subject] {:subject subject :payload event})
        (event->subjects event)))
