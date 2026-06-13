(ns agent.channels.core
  "External channel adapter contracts. A channel is an integration surface such
   as Telegram: it can be started/stopped, report health, describe capabilities,
   and optionally send outbound messages or typing indicators."
  (:require
   [clojure.string :as str]
   [clojure.set :as set]))

(def supported-capabilities
  #{:supports-outbound
    :supports-typing})

(defprotocol IChannelAdapter
  (describe-adapter [this])
  (adapter-health-check [this])
  (start-adapter! [this])
  (stop-adapter! [this])
  (send-adapter-message! [this destination message]))

(defprotocol IChannelTyping
  (send-adapter-typing! [this recipient metadata]))

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
  {:supports-typing IChannelTyping})

(defn- non-blank-content!
  [content]
  (let [content* (str content)]
    (when (str/blank? content*)
      (throw (ex-info "Channel message content must be non-blank"
                      {:type :channel-message-validation-failed
                       :field :content})))
    content*))

(defn- require-recipient!
  [recipient]
  (when (nil? recipient)
    (throw (ex-info "Channel message recipient is required"
                    {:type :channel-message-validation-failed
                     :field :recipient})))
  recipient)

(defn create-send-message
  [content recipient & {:keys [thread-id subject attachments cancellation-token metadata]
                        :or {attachments []
                             metadata {}}}]
  {:type :channel/send-message
   :content (non-blank-content! content)
   :recipient (require-recipient! recipient)
   :thread-id thread-id
   :subject subject
   :attachments (vec attachments)
   :cancellation-token cancellation-token
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
    (assoc message
           :type (or (:type message) :channel/send-message)
           :content (non-blank-content! (:content message))
           :recipient (require-recipient! (or (:recipient message) destination))
           :attachments (vec (or (:attachments message) []))
           :metadata (or (:metadata message) {}))
    (create-send-message message destination)))

(defn capability-validation-errors
  [adapter]
  (if-not (satisfies? IChannelAdapter adapter)
    [{:capability :channel-adapter
      :adapter nil
      :message "Adapter does not implement IChannelAdapter"}]
    (let [description (describe-adapter adapter)
          caps (:capabilities description #{})]
      (vec
       (concat
        (when (contains? caps :supports-outbound)
          (when (instance? agent.channels.core.BasicChannelAdapter adapter)
            [{:capability :supports-outbound
              :adapter (:name description)
              :message "Capability supports-outbound requires a custom adapter implementation"}]))
        (for [[cap protocol] optional-capability-requirements
              :when (and (contains? caps cap)
                         (not (satisfies? protocol adapter)))]
          {:capability cap
           :adapter (:name description)
           :message (str "Capability " (name cap) " has no adapter protocol implementation")}))))))

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
    (when (nil? adapter-name)
      (throw (ex-info "Channel adapter name is required"
                      {:type :channel-adapter-validation-failed
                       :field :name})))
    (when (contains? (:adapters registry) adapter-name)
      (throw (ex-info "Channel adapter is already registered"
                      {:type :duplicate-channel-adapter
                       :adapter adapter-name})))
    (assoc registry :adapters (assoc (:adapters registry) adapter-name adapter))))

(defn send-channel-message!
  [adapter message]
  (let [message* (normalize-send-message nil message)]
    (when (seq (:attachments message*))
      (unsupported-operation! :send-attachments {:adapter (:name (describe-adapter adapter))}))
    (send-adapter-message! adapter (:recipient message*) message*)))

(defn send-typing!
  ([adapter recipient] (send-typing! adapter recipient {}))
  ([adapter recipient metadata]
   (if (satisfies? IChannelTyping adapter)
     (send-adapter-typing! adapter recipient metadata)
     (unsupported-operation! :typing {:adapter (:name (describe-adapter adapter))}))))

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
                              (try
                                {:name name
                                 :health (adapter-health-check adapter)}
                                (catch Exception e
                                  {:name name
                                   :health {:healthy false
                                            :error (.getMessage e)
                                            :type (some-> e ex-data :type)}})))))
        healthy? (every? #(true? (get-in % [:health :healthy] true)) statuses)]
    {:healthy healthy?
     :count (count adapters)
     :adapters statuses}))
