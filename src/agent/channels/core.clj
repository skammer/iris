(ns agent.channels.core
  "Pluggable channel adapter contracts for rewritten runtime."
  (:require
   [clojure.set :as set]))

(def supported-capabilities
  #{:supports-outbound
    :supports-streaming
    :supports-typing
    :supports-progress
    :supports-interactive
    :supports-threads
    :supports-attachments
    :supports-cancellation
    :supports-voice-ingest
    :supports-pairing
    :supports-otp
    :supports-reactions
    :supports-location
    :supports-draft-updates
    :supports-draft-lifecycle
    :supports-draft-progress
    :supports-draft-cancel})

(defprotocol IChannelAdapter
  (describe-adapter [this])
  (adapter-health-check [this])
  (start-adapter! [this])
  (stop-adapter! [this])
  (send-adapter-message! [this destination message]))

(defprotocol IChannelTyping
  (send-adapter-typing! [this recipient metadata]))

(defprotocol IChannelProgress
  (update-adapter-progress! [this recipient progress]))

(defprotocol IChannelReactions
  (add-adapter-reaction! [this target reaction])
  (remove-adapter-reaction! [this target reaction]))

(defprotocol IChannelDrafts
  (send-adapter-draft! [this message])
  (update-adapter-draft! [this draft update])
  (finalize-adapter-draft! [this draft final]))

(defprotocol IChannelDraftProgress
  (update-adapter-draft-progress! [this draft progress]))

(defprotocol IChannelDraftCancel
  (cancel-adapter-draft! [this draft reason]))

(defrecord BasicChannelAdapter [description health-fn]
  IChannelAdapter
  (describe-adapter [_] description)
  (adapter-health-check [_] (health-fn))
  (start-adapter! [this] this)
  (stop-adapter! [this] this)
  (send-adapter-message! [_ _ _]
    (throw (ex-info "Adapter does not support send" {:type :unsupported-channel-send}))))

(defrecord ChannelAdapterRegistry [adapters])

(def optional-capability-requirements
  {:supports-typing IChannelTyping
   :supports-progress IChannelProgress
   :supports-reactions IChannelReactions
   :supports-draft-updates IChannelDrafts
   :supports-draft-lifecycle IChannelDrafts
   :supports-draft-progress IChannelDraftProgress
   :supports-draft-cancel IChannelDraftCancel})

(defn create-send-message
  [content recipient & {:keys [thread-id subject attachments cancellation-token metadata]
                        :or {attachments []
                             metadata {}}}]
  {:type :channel/send-message
   :content (str content)
   :recipient recipient
   :thread-id thread-id
   :subject subject
   :attachments (vec attachments)
   :cancellation-token cancellation-token
   :metadata (or metadata {})})

(defn create-inbound-message
  [content sender & {:keys [id reply-target channel timestamp thread-id thread-scope attachments metadata]
                     :or {attachments []
                          metadata {}}}]
  {:type :channel/inbound-message
   :id (or id (str (java.util.UUID/randomUUID)))
   :sender sender
   :reply-target reply-target
   :content (str content)
   :channel channel
   :timestamp (or timestamp (str (java.time.Instant/now)))
   :thread-id thread-id
   :thread-scope thread-scope
   :attachments (vec attachments)
   :metadata (or metadata {})})

(defn unsupported-operation!
  [operation details]
  (throw (ex-info "Channel adapter operation unsupported"
                  (merge {:type :unsupported-channel-operation
                          :operation operation}
                         details))))

(defn normalize-send-message
  [destination message]
  (if (map? message)
    (cond-> message
      (nil? (:recipient message)) (assoc :recipient destination)
      (nil? (:attachments message)) (assoc :attachments [])
      (nil? (:metadata message)) (assoc :metadata {}))
    (create-send-message message destination)))

(defn capability-validation-errors
  [adapter]
  (let [description (describe-adapter adapter)
        caps (:capabilities description #{})]
    (vec
     (for [[cap protocol] optional-capability-requirements
           :when (and (contains? caps cap)
                      (not (satisfies? protocol adapter)))]
       {:capability cap
        :adapter (:name description)
        :message (str "Capability " (name cap) " has no adapter protocol implementation")}))))

(defn validate-adapter-capabilities!
  [adapter]
  (when-let [errors (seq (capability-validation-errors adapter))]
    (throw (ex-info "Channel adapter capability validation failed"
                    {:type :channel-capability-validation-failed
                     :errors errors})))
  adapter)

(defn create-adapter-description
  [name display-name inbound-mode capabilities & {:keys [public-url-required? config-schema source]
                                                  :or {public-url-required? false
                                                       config-schema {}
                                                       source :builtin}}]
  (let [capabilities* (set capabilities)]
    (when-not (set/subset? capabilities* supported-capabilities)
      (throw (ex-info "Unsupported channel capabilities"
                      {:capabilities capabilities*})))
    {:name name
     :display-name display-name
     :inbound-mode inbound-mode
     :capabilities capabilities*
     :public-url-required? public-url-required?
     :config-schema config-schema
     :source source}))

(defn create-adapter
  [{:keys [description health-fn]}]
  (->BasicChannelAdapter description
                         (or health-fn (fn [] {:healthy true}))))

(defn create-registry
  ([] (->ChannelAdapterRegistry {}))
  ([adapters] (->ChannelAdapterRegistry adapters)))

(defn register-adapter
  [registry adapter]
  (validate-adapter-capabilities! adapter)
  (let [adapter-name (:name (describe-adapter adapter))]
    (assoc registry :adapters (assoc (:adapters registry) adapter-name adapter))))

(defn send-channel-message!
  [adapter message]
  (let [message* (normalize-send-message nil message)]
    (when (seq (:attachments message*))
      (unsupported-operation! :send-attachments {:adapter (:name (describe-adapter adapter))}))
    (send-adapter-message! adapter (:recipient message*) (:content message*))))

(defn send-typing!
  ([adapter recipient] (send-typing! adapter recipient {}))
  ([adapter recipient metadata]
   (if (satisfies? IChannelTyping adapter)
     (send-adapter-typing! adapter recipient metadata)
     (unsupported-operation! :typing {:adapter (:name (describe-adapter adapter))}))))

(defn update-progress!
  [adapter recipient progress]
  (if (satisfies? IChannelProgress adapter)
    (update-adapter-progress! adapter recipient progress)
    (unsupported-operation! :progress {:adapter (:name (describe-adapter adapter))})))

(defn add-reaction!
  [adapter target reaction]
  (if (satisfies? IChannelReactions adapter)
    (add-adapter-reaction! adapter target reaction)
    (unsupported-operation! :add-reaction {:adapter (:name (describe-adapter adapter))})))

(defn remove-reaction!
  [adapter target reaction]
  (if (satisfies? IChannelReactions adapter)
    (remove-adapter-reaction! adapter target reaction)
    (unsupported-operation! :remove-reaction {:adapter (:name (describe-adapter adapter))})))

(defn send-draft!
  [adapter message]
  (if (satisfies? IChannelDrafts adapter)
    (send-adapter-draft! adapter message)
    (unsupported-operation! :send-draft {:adapter (:name (describe-adapter adapter))})))

(defn update-draft!
  [adapter draft update]
  (if (satisfies? IChannelDrafts adapter)
    (update-adapter-draft! adapter draft update)
    (unsupported-operation! :update-draft {:adapter (:name (describe-adapter adapter))})))

(defn update-draft-progress!
  [adapter draft progress]
  (if (satisfies? IChannelDraftProgress adapter)
    (update-adapter-draft-progress! adapter draft progress)
    (unsupported-operation! :update-draft-progress {:adapter (:name (describe-adapter adapter))})))

(defn finalize-draft!
  [adapter draft final]
  (if (satisfies? IChannelDrafts adapter)
    (finalize-adapter-draft! adapter draft final)
    (unsupported-operation! :finalize-draft {:adapter (:name (describe-adapter adapter))})))

(defn cancel-draft!
  [adapter draft reason]
  (if (satisfies? IChannelDraftCancel adapter)
    (cancel-adapter-draft! adapter draft reason)
    (unsupported-operation! :cancel-draft {:adapter (:name (describe-adapter adapter))})))

(defn list-adapters
  [registry]
  (->> (:adapters registry)
       (sort-by key)
       (mapv (fn [[_ adapter]] (describe-adapter adapter)))))

(defn registry-health
  [registry]
  (let [adapters (:adapters registry)
        statuses (->> adapters
                      (sort-by key)
                      (mapv (fn [[name adapter]]
                              {:name name
                               :health (adapter-health-check adapter)})))
        healthy? (every? #(true? (get-in % [:health :healthy] true)) statuses)]
    {:healthy healthy?
     :count (count adapters)
     :adapters statuses}))
