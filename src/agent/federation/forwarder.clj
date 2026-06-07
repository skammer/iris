(ns agent.federation.forwarder
  "Durable federation outbox and HTTP delivery worker."
  (:require
   [agent.federation.crypto :as crypto]
   [agent.persistence.sqlite :as sqlite]
   [agent.telemetry :as telemetry]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def ^:private retry-statuses #{408 429 500 502 503 504})

(defn- now [] (Instant/now))

(defn- request-body [{:keys [peer-id remote-agent-id envelope]}]
  {:peer_id peer-id
   :to_agent_ref remote-agent-id
   :envelope envelope})

(defn- peer-base-url [peer]
  (str/replace (or (:base-url peer) "") #"/+$" ""))

(defn- delivery-url [peer inbox-path]
  (str (peer-base-url peer) inbox-path))

(defn- retry-delay-ms [base max-delay attempt]
  (min max-delay (* base (long (Math/pow 2 (max 0 (dec attempt)))))))

(defn- retryable-status? [status]
  (contains? retry-statuses status))

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

(defn- post-json! [url timeout-ms body]
  (http/post url
             {:socket-timeout timeout-ms
              :connection-timeout timeout-ms
              :content-type :json
              :accept :json
              :throw-exceptions false
              :body (json/generate-string body)}))

(defn- parse-body [response]
  (when (seq (:body response))
    (try
      (json/parse-string (:body response) true)
      (catch Exception _
        {:raw (:body response)}))))

(defn- peer-policy* [peer-policy]
  (merge {:max-concurrency 8
          :rate-limit-per-minute 120
          :failure-threshold 5
          :circuit-open-ms 30000}
         peer-policy))

(defn- retry-policy* [retry-policy]
  (merge {:max-attempts 3
          :base-delay-ms 100
          :max-delay-ms 2000}
         retry-policy))

(defn- mark-result!
  [store outbox result attempt retry-policy]
  (let [{:keys [max-attempts base-delay-ms max-delay-ms]} retry-policy
        retry? (and (:retry? result) (< attempt max-attempts))]
    (if retry?
      (sqlite/mark-federation-outbox-retry!
       store
       (:id outbox)
       {:attempt-count attempt
        :last-status (:status result)
        :last-error (:error result)
        :next-attempt-at (str (.plusMillis (now)
                                           (retry-delay-ms base-delay-ms max-delay-ms attempt)))})
      (if (:ok? result)
        (sqlite/mark-federation-outbox-acked!
         store
         (:id outbox)
         {:attempt-count attempt
          :last-status (:status result)})
        (sqlite/mark-federation-outbox-dead-letter!
         store
         (:id outbox)
         {:attempt-count attempt
          :last-status (:status result)
          :last-error (:error result)})))
    (assoc result
           :retry-scheduled? retry?
           :outbox-id (:id outbox))))

(defn- send-outbox!
  [{:keys [store telemetry state timeout-ms peer-policy retry-policy result-handlers]} outbox]
  (let [peer-policy (peer-policy* peer-policy)
        retry-policy (retry-policy* retry-policy)
        peer-id (:peer-id outbox)
        attempt (inc (long (:attempt-count outbox)))
        acquired (acquire-peer! state peer-id peer-policy)]
    (if-not (:ok? acquired)
      (do
        (sqlite/mark-federation-outbox-retry!
         store
         (:id outbox)
         {:attempt-count (:attempt-count outbox)
          :last-error (name (:reason acquired))
          :next-attempt-at (str (.plusMillis (now) (:base-delay-ms retry-policy)))})
        {:ok? false
         :status 429
         :body {:message (name (:reason acquired))}
         :retry-scheduled? true
         :outbox-id (:id outbox)})
      (let [start (System/nanoTime)
            result (try
                     (let [response (post-json! (:url outbox) timeout-ms (:envelope outbox))
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
            duration-ms (/ (double (- (System/nanoTime) start)) 1000000.0)
            result* (mark-result! store outbox result attempt retry-policy)]
        (telemetry/record-federation-send! telemetry
                                           {:peer-id peer-id
                                            :duration-ms duration-ms
                                            :attempt attempt
                                            :success? (:ok? result)
                                            :status (:status result)
                                            :error (:exception result)})
        (release-peer! state peer-id (:ok? result) peer-policy)
        (when-not (:retry-scheduled? result*)
          (when-let [handler (get @result-handlers (:id outbox))]
            (swap! result-handlers dissoc (:id outbox))
            (handler result*)))
        result*))))

(defn- drain-once!
  [opts]
  (let [outboxes (sqlite/claim-due-federation-outbox!
                  (:store opts)
                  {:limit (:batch-size opts 25)})]
    (mapv #(send-outbox! opts %) outboxes)))

(defn- start-worker! [{:keys [running? worker stop? outbox-poll-ms] :as forwarder}]
  (when (compare-and-set! running? false true)
    (reset! stop? false)
    (let [thread (Thread.
                  (fn []
                    (try
                      (while (not @stop?)
                        (try
                          (drain-once! forwarder)
                          (catch Exception e
                            (when-let [log! (:log-error! forwarder)]
                              (log! e))))
                        (Thread/sleep (long outbox-poll-ms)))
                      (catch InterruptedException _
                        (reset! stop? true))
                      (finally
                        (reset! running? false))))
                  "iris-federation-outbox")]
      (.setDaemon thread true)
      (.start thread)
      (reset! worker thread)))
  forwarder)

(defn- stop-worker! [{:keys [running? worker stop?]}]
  (reset! stop? true)
  (when-let [thread @worker]
    (.interrupt ^Thread thread))
  (reset! worker nil)
  (reset! running? false)
  nil)

(defn- enqueue-delivery!
  [{:keys [store inbox-path key-id private-key result-handlers]} {:keys [peer-id peer on-result] :as delivery}]
  (cond
    (nil? store)
    {:ok? false :status 500 :body {:message "federation store missing"}}

    (str/blank? (peer-base-url peer))
    {:ok? false :status 400 :body {:message "federation peer base_url missing"}}

    (or (str/blank? (str key-id)) (str/blank? (str private-key)))
    {:ok? false :status 401 :body {:message "federation signing key missing"}}

    :else
    (let [unsigned (request-body delivery)
          body (crypto/sign-request unsigned {:key-id key-id
                                              :private-key private-key})
          outbox (sqlite/create-federation-outbox!
                  store
                  {:peer-id peer-id
                   :key-id key-id
                   :url (delivery-url peer inbox-path)
                   :envelope body
                   :state "queued"})]
      (when on-result
        (swap! result-handlers assoc (:id outbox) on-result))
      {:ok? true
       :queued? true
       :status 202
       :body {:message "queued"}
       :outbox-id (:id outbox)
       :signed? true})))

(defn create-forwarder
  ([] (create-forwarder {}))
  ([{:keys [timeout-ms inbox-path store key-id private-key retry-policy peer-policy
            telemetry outbox-poll-ms batch-size auto-start? log-error!]
     :or {timeout-ms 10000
          inbox-path "/v1/federation/inbox"
          outbox-poll-ms 1000
          batch-size 25
          auto-start? true}}]
   (let [forwarder {:store store
                    :telemetry telemetry
                    :state (atom {:peers {}})
                    :retry-policy retry-policy
                    :peer-policy peer-policy
                    :timeout-ms timeout-ms
                    :inbox-path inbox-path
                    :key-id key-id
                    :private-key private-key
                    :outbox-poll-ms outbox-poll-ms
                    :batch-size batch-size
                    :result-handlers (atom {})
                    :running? (atom false)
                    :stop? (atom false)
                    :worker (atom nil)
                    :log-error! log-error!}
         forwarder* (assoc forwarder
                           :deliver (fn [delivery] (enqueue-delivery! forwarder delivery))
                           :drain! (fn [] (drain-once! forwarder))
                           :start! (fn [] (start-worker! forwarder))
                           :stop! (fn [] (stop-worker! forwarder)))]
     (when (and auto-start? store)
       (start-worker! forwarder*))
     forwarder*)))
