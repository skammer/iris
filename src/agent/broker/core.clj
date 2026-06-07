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
(defn all-runs-subject [] "runs.>")
(defn run-events-subject [run-id] (str "runs." run-id ".events"))
(defn run-commands-subject [run-id] (str "runs." run-id ".commands"))
(defn run-heartbeats-subject [run-id] (str "runs." run-id ".heartbeats"))
(defn run-checkpoints-subject [run-id] (str "runs." run-id ".checkpoints"))
(defn run-output-subject [run-id] (str "runs." run-id ".output"))
(defn reply-subject [request-id] (str "reply." request-id))

(defn event->subjects
  [{:keys [event-type entity-type entity-id]}]
  (let [event-type* (some-> event-type name)
        entity-type* (some-> entity-type name)
        run-id (some-> entity-id str)
        run-event? (and (= "agent_run" entity-type*)
                        (not (str/blank? run-id)))
        subjects (cond-> ["events.all"]
                   run-event?
                   (conj (run-events-subject run-id))

                   (and run-event? (= "agent.run.output" event-type*))
                   (conj (run-output-subject run-id))

                   (and run-event? (= "agent.run.heartbeat" event-type*))
                   (conj (run-heartbeats-subject run-id))

                   (and run-event? (= "agent.run.checkpointed" event-type*))
                   (conj (run-checkpoints-subject run-id))

                   (and run-event?
                        (str/starts-with? (or event-type* "") "agent.run.command."))
                   (conj (run-commands-subject run-id)))]
    (vec subjects)))

(defn event->messages [event]
  (mapv (fn [subject] {:subject subject :payload event})
        (event->subjects event)))

(defn command->message [command]
  {:subject (run-commands-subject (:run-id command))
   :payload command})

(defn heartbeat->message [heartbeat]
  {:subject (run-heartbeats-subject (:run-id heartbeat))
   :payload heartbeat})

(defn checkpoint->message [checkpoint]
  {:subject (run-checkpoints-subject (:run-id checkpoint))
   :payload checkpoint})
