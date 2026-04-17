(ns agent.broker.local
  "In-process broker backend with wildcard subscriptions."
  (:require
   [agent.broker.core :as broker]
   [clojure.core.async :as async])
  (:import
   (java.util UUID)))

(defrecord LocalBroker [subscriptions published-count]
  broker/IBroker
  (publish! [_ {:keys [subject] :as message}]
    (swap! published-count inc)
    (doseq [[_ {:keys [pattern channel]}] @subscriptions]
      (when (broker/match-subject? pattern subject)
        (async/put! channel message)))
    message)
  (subscribe! [_ pattern]
    (let [subscription {:id (str (UUID/randomUUID))
                        :pattern pattern
                        :channel (async/chan 64)}]
      (swap! subscriptions assoc (:id subscription) subscription)
      subscription))
  (unsubscribe! [_ {:keys [id channel]}]
    (swap! subscriptions dissoc id)
    (async/close! channel)
    true)
  (health-check [_]
    {:healthy true
     :backend :local
     :subscription_count (count @subscriptions)
     :published_count @published-count}))

(defn create-broker []
  (->LocalBroker (atom {}) (atom 0)))
