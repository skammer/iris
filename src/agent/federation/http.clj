(ns agent.federation.http
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.telemetry :as telemetry]
   [agent.util :as util]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security KeyFactory KeyPairGenerator Signature)
   (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)
   (java.sql SQLException)
   (java.time Duration Instant)
   (java.util Base64 UUID)))

(def retry-statuses #{408 429 500 502 503 504})

(defn- now [] (Instant/now))
(def ^:private now-str util/now-str)
(defn- random-nonce [] (str (UUID/randomUUID)))

(defn- b64-encode [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn- b64-decode [value]
  (.decode (Base64/getDecoder) ^String value))

(defn generate-ed25519-keypair
  []
  (let [generator (KeyPairGenerator/getInstance "Ed25519")
        pair (.generateKeyPair generator)]
    {:public-key (b64-encode (.getEncoded (.getPublic pair)))
     :private-key (b64-encode (.getEncoded (.getPrivate pair)))}))

(defn- decode-private-key [encoded]
  (.generatePrivate (KeyFactory/getInstance "Ed25519")
                    (PKCS8EncodedKeySpec. (b64-decode encoded))))

(defn- decode-public-key [encoded]
  (.generatePublic (KeyFactory/getInstance "Ed25519")
                   (X509EncodedKeySpec. (b64-decode encoded))))

(defn- canonical-value [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]]
                 [(if (keyword? k) (name k) (str k))
                  (canonical-value v)]))
          value)

    (sequential? value)
    (mapv canonical-value value)

    :else value))

(defn canonical-json [value]
  (json/generate-string (canonical-value value)))

(defn- signing-bytes [request]
  (.getBytes (canonical-json request) StandardCharsets/UTF_8))

(defn sign-request
  [request {:keys [key-id private-key timestamp nonce]}]
  (let [auth {:scheme "ed25519"
              :key_id key-id
              :timestamp (or timestamp (now-str))
              :nonce (or nonce (random-nonce))}
        unsigned (assoc request :auth auth)
        signer (doto (Signature/getInstance "Ed25519")
                 (.initSign (decode-private-key private-key))
                 (.update (signing-bytes unsigned)))]
    (assoc unsigned :auth (assoc auth :signature (b64-encode (.sign signer))))))

(defn- verify-signature!
  [request public-key* signature]
  (try
    (let [unsigned (update request :auth dissoc :signature)
          verifier (doto (Signature/getInstance "Ed25519")
                     (.initVerify (decode-public-key public-key*))
                     (.update (signing-bytes unsigned)))]
      (when-not (.verify verifier (b64-decode signature))
        (throw (ex-info "Federation signature invalid"
                        {:type :signature-invalid}))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info "Federation signature invalid"
                      {:type :signature-invalid}
                      e)))))

(defn- within-skew? [timestamp max-skew-ms]
  (try
    (let [instant (Instant/parse timestamp)
          delta (Math/abs (.toMillis (Duration/between instant (now))))]
      (<= delta max-skew-ms))
    (catch Exception _
      false)))

(defn- nonce-expires-at [timestamp max-skew-ms]
  (str (.plusMillis (Instant/parse timestamp) (* 2 max-skew-ms))))

(defn verify-request!
  [{:keys [store peer public-key max-clock-skew-ms]
    :or {max-clock-skew-ms 300000}}
   request]
  (let [peer-id (:peer_id request)
        {:keys [key_id timestamp nonce signature]} (:auth request)
        public-key* (or public-key
                        (:public-key peer)
                        (some-> peer :keys (get key_id) :public-key)
                        (some-> store
                                (sqlite/get-federation-peer-key peer-id key_id)
                                :public-key))]
    ;; Fail closed. Auth fields are required unconditionally, and a peer with no
    ;; resolvable key is rejected — previously every check sat inside
    ;; (when public-key* ...), so a peer registered without a key (orchestrator
    ;; only stores :keys when one is supplied) bypassed signature + replay checks
    ;; entirely and any unsigned, replayable message was accepted.
    (when-not (and key_id timestamp nonce signature)
      (throw (ex-info "Federation auth missing"
                      {:type :signature-missing})))
    (when-not public-key*
      (throw (ex-info "Federation signing key not found for peer"
                      {:type :signature-missing
                       :peer-id peer-id
                       :key-id key_id})))
    (when-not (within-skew? timestamp max-clock-skew-ms)
      (throw (ex-info "Federation timestamp outside skew"
                      {:type :timestamp-skew})))
    (verify-signature! request public-key* signature)
    (when store
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
                          e))))))
  true)

(defn- request-body
  [{:keys [peer-id remote-agent-id envelope]}]
  {:peer_id peer-id
   :to_agent_ref remote-agent-id
   :envelope envelope})

(defn- peer-base-url [peer]
  (str/replace (or (:base-url peer) "") #"/+$" ""))

(defn- delivery-url [peer inbox-path]
  (str (peer-base-url peer) inbox-path))

(defn- retryable-status? [status]
  (contains? retry-statuses status))

(defn- retry-delay-ms [base max-delay attempt]
  (min max-delay (* base (long (Math/pow 2 (max 0 (dec attempt)))))))

(defn- peer-state [state peer-id]
  (get-in state [:peers peer-id] {:in-flight 0
                                  :window []
                                  :failures 0
                                  :circuit :closed
                                  :open-until 0}))

(defn- prune-window [xs now-ms]
  (filterv #(<= (- now-ms %) 60000) xs))

(defn- acquire-peer!
  [state* peer-id {:keys [max-concurrency rate-limit-per-minute]}]
  (let [now-ms (System/currentTimeMillis)]
    (loop []
      (let [old @state*
            peer (peer-state old peer-id)
            peer* (if (and (= :open (:circuit peer))
                           (>= now-ms (:open-until peer)))
                    (assoc peer :circuit :half-open :failures 0)
                    peer)
            window (prune-window (:window peer*) now-ms)]
        (cond
          (and (= :open (:circuit peer*))
               (< now-ms (:open-until peer*)))
          {:ok? false :reason :circuit-open}

          (>= (:in-flight peer*) max-concurrency)
          {:ok? false :reason :concurrency-limited}

          (>= (count window) rate-limit-per-minute)
          {:ok? false :reason :rate-limited}

          :else
          (let [new (assoc-in old [:peers peer-id]
                              (-> peer*
                                  (assoc :window (conj window now-ms))
                                  (update :in-flight inc)))]
            (if (compare-and-set! state* old new)
              {:ok? true}
              (recur))))))))

(defn- release-peer!
  [state* peer-id success? {:keys [failure-threshold circuit-open-ms]}]
  (let [now-ms (System/currentTimeMillis)]
    (swap! state*
           (fn [state]
             (let [peer (peer-state state peer-id)
                   failures (if success? 0 (inc (long (:failures peer))))
                   open? (and (not success?) (>= failures failure-threshold))]
               (assoc-in state [:peers peer-id]
                         (cond-> (assoc peer
                                        :in-flight (max 0 (dec (long (:in-flight peer))))
                                        :failures failures
                                        :circuit (if success? :closed (:circuit peer)))
                           open? (assoc :circuit :open
                                        :open-until (+ now-ms circuit-open-ms)))))))))

(defn- post-json!
  [url timeout-ms body]
  (http/post url
             {:socket-timeout timeout-ms
              :connection-timeout timeout-ms
              :content-type :json
              :accept :json
              :throw-exceptions false
              :body (json/generate-string body)}))

(defn- parse-body [response]
  (when (seq (:body response))
    (json/parse-string (:body response) true)))

(defn- mark-outbox! [store id updates]
  (when (and store id)
    (sqlite/update-federation-outbox! store id updates)))

(defn- attempt-send!
  [{:keys [store telemetry state retry-policy peer-policy timeout-ms]} outbox-id url body peer-id]
  (let [{:keys [max-attempts base-delay-ms max-delay-ms]
         :or {max-attempts 3 base-delay-ms 100 max-delay-ms 2000}} retry-policy
        peer-policy* (merge {:max-concurrency 8
                             :rate-limit-per-minute 120
                             :failure-threshold 5
                             :circuit-open-ms 30000}
                            peer-policy)]
    (loop [attempt 1]
      (let [acquired (acquire-peer! state peer-id peer-policy*)]
        (if-not (:ok? acquired)
          (do
            (mark-outbox! store outbox-id {:state "failed"
                                           :last-error (name (:reason acquired))})
            {:ok? false
             :status 429
             :body {:message (name (:reason acquired))}
             :outbox-id outbox-id})
          (let [start (System/nanoTime)
                result (try
                         (mark-outbox! store outbox-id {:state "sent"
                                                        :attempt-count attempt})
                         (let [response (post-json! url timeout-ms body)
                               parsed (parse-body response)
                               ok? (<= 200 (:status response) 299)]
                           {:ok? ok?
                            :status (:status response)
                            :body parsed
                            :retry? (retryable-status? (:status response))
                            :error (when-not ok?
                                     (or (some-> parsed :message)
                                         (str "peer returned " (:status response))))})
                         (catch Exception e
                           {:ok? false
                            :status nil
                            :body {:message (.getMessage e)}
                            :retry? true
                            :error (.getMessage e)
                            :exception e}))
                duration-ms (/ (double (- (System/nanoTime) start)) 1000000.0)]
            (telemetry/record-federation-send! telemetry
                                               {:peer-id peer-id
                                                :duration-ms duration-ms
                                                :attempt attempt
                                                :success? (:ok? result)
                                                :status (:status result)
                                                :error (:exception result)})
            (release-peer! state peer-id (:ok? result) peer-policy*)
            (cond
              (:ok? result)
              (do
                (mark-outbox! store outbox-id {:state "acked"
                                               :attempt-count attempt
                                               :last-status (:status result)
                                               :last-error nil})
                (assoc result :outbox-id outbox-id))

              (and (:retry? result) (< attempt max-attempts))
              (do
                (mark-outbox! store outbox-id {:state "failed"
                                               :attempt-count attempt
                                               :last-status (:status result)
                                               :last-error (:error result)
                                               :next-attempt-at (str (.plusMillis (now)
                                                                                  (retry-delay-ms base-delay-ms max-delay-ms attempt)))})
                (Thread/sleep (retry-delay-ms base-delay-ms max-delay-ms attempt))
                (recur (inc attempt)))

              :else
              (do
                (mark-outbox! store outbox-id {:state "dead-letter"
                                               :attempt-count attempt
                                               :last-status (:status result)
                                               :last-error (:error result)})
                (assoc result :outbox-id outbox-id)))))))))

(defn create-forwarder
  ([] (create-forwarder {}))
  ([{:keys [timeout-ms inbox-path store private-key key-id retry-policy peer-policy telemetry]
     :or {timeout-ms 10000
          inbox-path "/v1/federation/inbox"}}]
   (let [state (atom {:peers {}})]
     (fn [{:keys [peer-id peer] :as delivery}]
       (let [url (delivery-url peer inbox-path)
             unsigned (request-body delivery)
             key-id* (or (:key-id peer) key-id)
             private-key* (or (:private-key peer) private-key)
             body (if (and key-id* private-key*)
                    (sign-request unsigned {:key-id key-id*
                                            :private-key private-key*})
                    unsigned)
             outbox (when store
                      (sqlite/create-federation-outbox!
                       store
                       {:peer-id peer-id
                        :key-id key-id*
                        :url url
                        :envelope body
                        :state "queued"}))
             result (attempt-send! {:store store
                                    :telemetry telemetry
                                    :state state
                                    :retry-policy retry-policy
                                    :peer-policy peer-policy
                                    :timeout-ms timeout-ms}
                                   (:id outbox)
                                   url
                                   body
                                   peer-id)]
         (assoc result :signed? (boolean (:auth body))))))))
