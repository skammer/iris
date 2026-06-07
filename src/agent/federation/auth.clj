(ns agent.federation.auth
  "Federation request authentication."
  (:require
   [agent.federation.crypto :as crypto]
   [agent.persistence.sqlite :as sqlite])
  (:import
   (java.sql SQLException)
   (java.time Duration Instant)))

(defn- now [] (Instant/now))

(defn- parse-instant [value]
  (try
    (Instant/parse value)
    (catch Exception _
      nil)))

(defn- within-skew? [timestamp max-skew-ms]
  (if-let [instant (parse-instant timestamp)]
    (let [delta (Math/abs (.toMillis (Duration/between instant (now))))]
      (<= delta max-skew-ms))
    false))

(defn- nonce-expires-at [timestamp max-skew-ms]
  (str (.plusMillis (Instant/parse timestamp) (* 2 max-skew-ms))))

(defn- active-key? [key-record timestamp]
  (let [signed-at (parse-instant timestamp)
        valid-from (some-> (:valid-from key-record) parse-instant)
        valid-until (some-> (:valid-until key-record) parse-instant)]
    (and signed-at
         (= "active" (:status key-record))
         (or (nil? valid-from) (not (.isBefore signed-at valid-from)))
         (or (nil? valid-until) (not (.isAfter signed-at valid-until))))))

(defn- peer-key [peer key-id]
  (some-> peer :keys (get key-id)))

(defn- key-record [store peer peer-id key-id]
  (or (peer-key peer key-id)
      (sqlite/get-federation-peer-key store peer-id key-id)))

(defn verify-request!
  [{:keys [store peer max-clock-skew-ms]
    :or {max-clock-skew-ms 300000}}
   request]
  (let [peer-id (:peer_id request)
        {:keys [scheme key_id timestamp nonce signature]} (:auth request)]
    (when-not (and scheme key_id timestamp nonce signature)
      (throw (ex-info "Federation auth missing"
                      {:type :signature-missing})))
    (when-not (= "ed25519" scheme)
      (throw (ex-info "Federation signature invalid"
                      {:type :signature-invalid
                       :scheme scheme})))
    (when-not store
      (throw (ex-info "Federation nonce store missing"
                      {:type :nonce-store-missing})))
    (when-not (within-skew? timestamp max-clock-skew-ms)
      (throw (ex-info "Federation timestamp outside skew"
                      {:type :timestamp-skew})))
    (let [key* (key-record store peer peer-id key_id)]
      (when-not key*
        (throw (ex-info "Federation signing key not found for peer"
                        {:type :signature-missing
                         :peer-id peer-id
                         :key-id key_id})))
      (when-not (active-key? key* timestamp)
        (throw (ex-info "Federation signing key inactive"
                        {:type :key-inactive
                         :peer-id peer-id
                         :key-id key_id})))
      (crypto/verify-signature! request (:public-key key*) signature))
    (try
      (sqlite/insert-federation-nonce!
       store
       {:peer-id peer-id
        :nonce nonce
        :expires-at (nonce-expires-at timestamp max-clock-skew-ms)})
      (catch SQLException e
        (throw (ex-info "Federation nonce replay"
                        {:type :nonce-replay
                         :peer-id peer-id
                         :nonce nonce}
                        e)))))
  true)
