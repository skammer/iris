(ns agent.broker.core
  "Broker abstraction for local/runtime event transport."
  (:require
   [clojure.string :as str]))

(defprotocol IBroker
  (publish! [this message])
  (subscribe! [this pattern])
  (unsubscribe! [this subscription])
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
(defn all-runs-subject [] "runs.>")
(defn run-events-subject [run-id] (str "runs." run-id ".events"))
(defn run-commands-subject [run-id] (str "runs." run-id ".commands"))
(defn run-heartbeats-subject [run-id] (str "runs." run-id ".heartbeats"))
(defn run-checkpoints-subject [run-id] (str "runs." run-id ".checkpoints"))
(defn run-output-subject [run-id] (str "runs." run-id ".output"))

(defn event->subjects
  [{:keys [event-type entity-type entity-id]}]
  (let [run-event? (and (= "agent_run" (name entity-type)) (seq entity-id))
        subjects (cond-> ["events.all"]
                   run-event?
                   (conj (run-events-subject entity-id))

                   (= "agent.run.output" (name event-type))
                   (conj (run-output-subject entity-id))

                   (= "agent.run.heartbeat" (name event-type))
                   (conj (run-heartbeats-subject entity-id))

                   (= "agent.run.checkpointed" (name event-type))
                   (conj (run-checkpoints-subject entity-id))

                   (str/includes? (name event-type) "agent.run.command.")
                   (conj (run-commands-subject entity-id)))]
    (vec subjects)))

(defn event->messages [event]
  (mapv (fn [subject] {:subject subject :payload event})
        (event->subjects event)))
