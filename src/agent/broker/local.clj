(ns agent.broker.local
  "In-process broker backend with wildcard subscriptions."
  (:require
   [agent.broker.core :as broker]
   [clojure.core.async :as async])
  (:import
   (java.util UUID)))

(defrecord LocalBroker [subscriptions published-count replay-fn]
  broker/IBroker
  (publish! [_ {:keys [subject] :as message}]
    (swap! published-count inc)
    (doseq [[_ {:keys [pattern channel]}] @subscriptions]
      (when (broker/match-subject? pattern subject)
        (async/put! channel message)))
    message)
  (subscribe! [_ pattern]
    (broker/subscribe! _ pattern {}))
  (subscribe! [_ pattern opts]
    (let [subscription {:id (str (UUID/randomUUID))
                        :pattern pattern
                        :opts opts
                        :channel (async/chan 64)}]
      (swap! subscriptions assoc (:id subscription) subscription)
      subscription))
  (unsubscribe! [_ {:keys [id channel]}]
    (swap! subscriptions dissoc id)
    (async/close! channel)
    true)
  (replay! [_ pattern]
    (broker/replay! _ pattern {}))
  (replay! [_ pattern opts]
    (vec (or (when replay-fn
               (replay-fn pattern opts))
             [])))
  (request! [this subject payload]
    (broker/request! this subject payload {}))
  (request! [this subject payload {:keys [timeout-ms wait?]
                                   :or {timeout-ms 10000
                                        wait? true}}]
    (let [request-id (str (UUID/randomUUID))
          reply-to (broker/reply-subject request-id)
          sub (broker/subscribe! this reply-to)
          message {:subject subject
                   :request-id request-id
                   :reply-to reply-to
                   :payload payload}]
      (broker/publish! this message)
      (if-not wait?
        {:request-id request-id
         :reply-to reply-to}
        (let [timeout-ch (async/timeout timeout-ms)
              [reply port] (async/alts!! [(:channel sub) timeout-ch])]
          (broker/unsubscribe! this sub)
          (if (= port timeout-ch)
            {:request-id request-id
             :reply-to reply-to
             :timed-out true}
            {:request-id request-id
             :reply-to reply-to
             :response reply})))))
  (health-check [_]
    {:healthy true
     :backend :local
     :subscription_count (count @subscriptions)
     :published_count @published-count}))

(defn create-broker
  ([] (create-broker {}))
  ([{:keys [replay-fn]}]
   (->LocalBroker (atom {}) (atom 0) replay-fn)))
