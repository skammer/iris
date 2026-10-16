(ns agent.broker.local
  "Local core.async broker backend. Stores subscriptions in memory, routes
   exact or prefix-wildcard subjects, isolates slow subscribers with buffers,
   and delegates replay to the system event store."
  (:require
   [agent.broker.core :as broker]
   [agent.defaults :as defaults]
   [clojure.core.async :as async]
   [clojure.core.async.impl.protocols :as impl])
  (:import
   (java.util UUID)))

(defn- counted-buffer [buffer size dropped]
  (reify
    impl/UnblockingBuffer
    impl/Buffer
    (full? [_] false)
    (remove! [_]
      (let [message (impl/remove! buffer)]
        (cond-> message (pos? @dropped) (assoc :broker/dropped-count @dropped))))
    (add!* [this message]
      (when (>= (count buffer) size) (swap! dropped inc))
      (impl/add!* buffer message)
      this)
    (close-buf! [_] (impl/close-buf! buffer))
    clojure.lang.Counted
    (count [_] (count buffer))))

(defn- channel-buffer [{:keys [buffer-size buffer-strategy]
                        :or {buffer-size defaults/broker-channel-buffer-size
                             ;; Default to :sliding so the :park (async/put!) path can never
                             ;; accumulate pending puts and throw at the 1024 hard cap: a sliding
                             ;; buffer never reports full, it drops the oldest message instead.
                             ;; A slow subscriber must not be able to abort event emission for
                             ;; every other subscriber (publish! runs synchronously on the
                             ;; event-sink thread).
                             buffer-strategy :sliding}} dropped]
  (case (keyword buffer-strategy)
    :dropping (counted-buffer (async/dropping-buffer buffer-size) buffer-size dropped)
    :sliding (counted-buffer (async/sliding-buffer buffer-size) buffer-size dropped)
    :fixed buffer-size
    buffer-size))

(defn- publish-to-subscriber! [{:keys [channel opts dropped-count]} message]
  (case (keyword (:slow-client opts :park))
    :drop-new
    (when-not (async/offer! channel message)
      (swap! dropped-count inc))

    :block
    (let [timeout-ms (long (or (:block-timeout-ms opts)
                               defaults/broker-block-timeout-ms))
          timeout-ch (async/timeout timeout-ms)
          [ok? port] (async/alts!! [[channel message] timeout-ch])]
      (when-not (and (= port channel) ok?)
        (swap! dropped-count inc)))

    (async/put! channel message)))

(defrecord LocalBroker [subscriptions published-count replay-fn]
  broker/IBroker
  (publish! [_ {:keys [subject] :as message}]
    (swap! published-count inc)
    (doseq [[_ {:keys [pattern] :as subscription}] @subscriptions]
      (when (broker/match-subject? pattern subject)
        (publish-to-subscriber! subscription message)))
    message)
  (subscribe! [_ pattern]
    (broker/subscribe! _ pattern {}))
  (subscribe! [_ pattern opts]
    (let [dropped (atom 0)
          subscription {:id (str (UUID/randomUUID))
                        :pattern pattern
                        :opts opts
                        :dropped-count dropped
                        :channel (async/chan (channel-buffer opts dropped))}]
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
          message {:subject subject
                   :request-id request-id
                   :reply-to reply-to
                   :payload payload}]
      (if-not wait?
        (do
          (broker/publish! this message)
          {:request-id request-id
           :reply-to reply-to})
        (let [sub (broker/subscribe! this reply-to)]
          (try
            (broker/publish! this message)
            (let [timeout-ch (async/timeout timeout-ms)
                  [reply port] (async/alts!! [(:channel sub) timeout-ch])]
              (if (= port timeout-ch)
                {:request-id request-id
                 :reply-to reply-to
                 :timed-out true}
                {:request-id request-id
                 :reply-to reply-to
                 :response reply}))
            (finally
              (broker/unsubscribe! this sub)))))))
  (health-check [_]
    {:healthy true
     :backend :local
     :subscription_count (count @subscriptions)
     :published_count @published-count}))

(defn create-broker
  ([] (create-broker {}))
  ([{:keys [replay-fn]}]
   (->LocalBroker (atom {}) (atom 0) replay-fn)))
