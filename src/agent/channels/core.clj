(ns agent.channels.core
  "Pluggable channel adapter contracts for rewritten runtime."
  (:require
   [clojure.set :as set]))

(def supported-capabilities
  #{:supports-outbound
    :supports-streaming
    :supports-interactive
    :supports-threads
    :supports-voice-ingest
    :supports-pairing
    :supports-otp
    :supports-reactions
    :supports-location})

(defprotocol IChannelAdapter
  (describe-adapter [this])
  (adapter-health-check [this]))

(defrecord BasicChannelAdapter [description health-fn]
  IChannelAdapter
  (describe-adapter [_] description)
  (adapter-health-check [_] (health-fn)))

(defrecord ChannelAdapterRegistry [adapters])

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
  (let [adapter-name (:name (describe-adapter adapter))]
    (assoc registry :adapters (assoc (:adapters registry) adapter-name adapter))))

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
