(ns agent.orchestrator
  "Rewritten in-memory orchestrator/subagent runtime."
  (:require
   [agent.llm.core :as llm-core]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.time Instant)
   (java.util UUID)))

(defn- now []
  (str (Instant/now)))

(defn- random-id [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn- emit-event!
  [orchestrator event]
  (when-let [sink (:event-sink orchestrator)]
    (sink event)))

(defn- agent-view [agent]
  {:id (:id agent)
   :name (:name agent)
   :role (:role agent)
   :parent-id (:parent-id agent)
   :logical-address (:logical-address agent)
   :capabilities (vec (sort (:capabilities agent)))
   :allow-direct? (true? (:allow-direct? agent))
   :status (:status agent)
   :created-at (:created-at agent)
   :message-count (count (:messages agent))})

(defn- channel-view [channel]
  {:id (:id channel)
   :name (:name channel)
   :participants (vec (:participants channel))
   :created-at (:created-at channel)
   :message-count (count (:messages channel))})

(defn- ensure-agent! [orchestrator agent-id]
  (or (get @(:agents orchestrator) agent-id)
      (throw (ex-info "Agent not found" {:type :agent-not-found
                                        :agent-id agent-id}))))

(defn- ensure-channel! [orchestrator channel-id]
  (or (get @(:channels orchestrator) channel-id)
      (throw (ex-info "Channel not found" {:type :channel-not-found
                                          :channel-id channel-id}))))

(defn- build-llm-messages [agent]
  (vec
   (concat
    (when-let [system-prompt (:system-prompt agent)]
      [{:role "system" :content system-prompt}])
    (map #(select-keys % [:role :content]) (:messages agent)))))

(defn create-orchestrator
  ([] (create-orchestrator {}))
  ([{:keys [event-sink]}]
   {:agents (atom {})
    :channels (atom {})
    :interop-windows (atom {})
    :interop-deliveries (atom {})
    :interop-messages (atom {})
    :event-sink event-sink}))

(defn- logical-address [agent-id]
  (str "agent://" agent-id))

(defn- ensure-agent-by-ref!
  [orchestrator value]
  (or (get @(:agents orchestrator) value)
      (some (fn [[_ agent]]
              (when (= value (:logical-address agent))
                agent))
            @(:agents orchestrator))
      (throw (ex-info "Agent not found" {:type :agent-not-found
                                        :agent-ref value}))))

(defn health-check
  [orchestrator]
  {:healthy true
   :agent-count (count @(:agents orchestrator))
   :channel-count (count @(:channels orchestrator))
   :interop-delivery-count (count @(:interop-deliveries orchestrator))
   :interop-message-count (count @(:interop-messages orchestrator))})

(defn spawn-agent!
  [orchestrator {:keys [name role parent-id system-prompt capabilities allow-direct? logical-address
                        trusted-peers interop-rate-limit-per-minute]
                 :or {name "Subagent"
                      role "worker"
                      capabilities []}}]
  (let [created-at (now)
        agent {:id (random-id "agent")
               :name name
               :role role
               :parent-id parent-id
               :system-prompt system-prompt
               :logical-address logical-address
               :capabilities (set capabilities)
               :allow-direct? (true? allow-direct?)
               :trusted-peers (set trusted-peers)
               :interop-rate-limit-per-minute (long (or interop-rate-limit-per-minute 60))
               :status "idle"
               :created-at created-at
               :messages []
               :inbox (async/chan 64)}]
    (let [resolved-address (or logical-address (agent.orchestrator/logical-address (:id agent)))
          agent* (assoc agent :logical-address resolved-address)]
      (swap! (:agents orchestrator) assoc (:id agent*) agent*)
      (emit-event! orchestrator
                   {:event-type :agent.created
                    :entity-type :agent
                    :entity-id (:id agent*)
                    :payload {:name (:name agent*)
                              :role (:role agent*)
                              :parent-id (:parent-id agent*)
                              :logical-address (:logical-address agent*)
                              :capabilities (vec (:capabilities agent*))
                              :allow-direct? (:allow-direct? agent*)
                              :trusted-peers (vec (:trusted-peers agent*))
                              :interop-rate-limit-per-minute (:interop-rate-limit-per-minute agent*)}})
      (agent-view agent*))))

(defn list-agents
  [orchestrator]
  (->> @(:agents orchestrator)
       vals
       (sort-by :created-at)
       (mapv agent-view)))

(defn get-agent
  [orchestrator agent-id]
  (some-> (ensure-agent! orchestrator agent-id) agent-view))

(defn describe-agent-interop
  [orchestrator agent-ref]
  (let [agent (ensure-agent-by-ref! orchestrator agent-ref)]
    {:id (:id agent)
     :logical-address (:logical-address agent)
     :capabilities (vec (sort (:capabilities agent)))
     :trusted-peers (vec (sort (:trusted-peers agent)))
     :interop-rate-limit-per-minute (:interop-rate-limit-per-minute agent)
     :allow-direct? (true? (:allow-direct? agent))
     :status (:status agent)}))

(defn- ensure-interop-message!
  [orchestrator message-id]
  (or (get @(:interop-messages orchestrator) message-id)
      (throw (ex-info "Interop message not found"
                      {:type :interop-message-not-found
                       :message-id message-id}))))

(defn- store-interop-message!
  [orchestrator envelope]
  (swap! (:interop-messages orchestrator) assoc (:id envelope) envelope)
  (swap! (:interop-deliveries orchestrator)
         (fn [state]
           (reduce-kv (fn [acc k v]
                        (assoc acc k (if (= (:id v) (:id envelope))
                                       envelope
                                       v)))
                      {}
                      state)))
  envelope)

(defn list-interop-messages
  ([orchestrator agent-ref] (list-interop-messages orchestrator agent-ref {}))
  ([orchestrator agent-ref {:keys [direction status]}]
   (let [agent (ensure-agent-by-ref! orchestrator agent-ref)]
     (->> @(:interop-messages orchestrator)
          vals
          (filter (fn [message]
                    (case direction
                      :inbound (= (:to-agent-id message) (:id agent))
                      :outbound (= (:from-agent-id message) (:id agent))
                      (or (= (:to-agent-id message) (:id agent))
                          (= (:from-agent-id message) (:id agent))))))
          (filter (fn [message]
                    (if status
                      (= (:status message) status)
                      true)))
          (sort-by :created-at)
          vec))))

(defn register-agent-capabilities!
  [orchestrator agent-ref {:keys [capabilities allow-direct? trusted-peers interop-rate-limit-per-minute]}]
  (let [agent (ensure-agent-by-ref! orchestrator agent-ref)
        updated (-> agent
                    (assoc :capabilities (set capabilities))
                    (assoc :allow-direct? (true? allow-direct?))
                    (assoc :trusted-peers (set trusted-peers))
                    (assoc :interop-rate-limit-per-minute (long (or interop-rate-limit-per-minute
                                                                   (:interop-rate-limit-per-minute agent)
                                                                   60))))]
    (swap! (:agents orchestrator) assoc (:id agent) updated)
    (emit-event! orchestrator
                 {:event-type :agent.interop.capabilities.updated
                  :entity-type :agent
                  :entity-id (:id agent)
                  :payload {:capabilities (vec (:capabilities updated))
                            :allow-direct? (:allow-direct? updated)
                            :trusted-peers (vec (:trusted-peers updated))
                            :interop-rate-limit-per-minute (:interop-rate-limit-per-minute updated)}})
    (describe-agent-interop orchestrator (:id agent))))

(defn list-agent-messages
  [orchestrator agent-id]
  (:messages (ensure-agent! orchestrator agent-id)))

(defn send-agent-message!
  [orchestrator llm-provider agent-id {:keys [role content]
                                       :or {role "user"}}]
  (when-not (and (string? content) (not (str/blank? content)))
    (throw (ex-info "content must be a non-blank string"
                    {:type :validation-failed
                     :field :content})))
  (let [input-message {:role role
                       :content content
                       :created-at (now)}
        agent-before (ensure-agent! orchestrator agent-id)
        agent-after-input (update agent-before :messages conj input-message)
        completion (llm-core/complete llm-provider (build-llm-messages agent-after-input) {})
        assistant-message {:role "assistant"
                           :content completion
                           :created-at (now)}]
    (swap! (:agents orchestrator)
           assoc agent-id
           (-> agent-after-input
               (assoc :status "idle")
               (update :messages conj assistant-message)))
    (emit-event! orchestrator
                 {:event-type :agent.message.processed
                  :entity-type :agent
                  :entity-id agent-id
                  :payload {:input-role role
                            :response-role "assistant"}})
    {:agent (agent-view (get @(:agents orchestrator) agent-id))
     :input input-message
     :response assistant-message}))

(defn- choose-interop-route [from-agent to-agent requested-route]
  (let [direct-allowed? (and (:allow-direct? from-agent) (:allow-direct? to-agent))]
    (case requested-route
      :direct (if direct-allowed?
                :direct
                (throw (ex-info "Direct interop denied"
                                {:type :permission-denied
                                 :reason :direct-not-allowed})))
      :routed :routed
      (if direct-allowed? :direct :routed))))

(defn- trusted-peer?
  [to-agent from-agent]
  (let [trusted (:trusted-peers to-agent)]
    (or (contains? trusted (:id from-agent))
        (contains? trusted (:logical-address from-agent)))))

(defn- enforce-interop-trust! [from-agent to-agent]
  (when-not (trusted-peer? to-agent from-agent)
    (throw (ex-info "Interop denied"
                    {:type :permission-denied
                     :reason :peer-not-trusted}))))

(defn- prune-window [timestamps now-ms]
  (filterv #(<= (- now-ms %) 60000) timestamps))

(defn- enforce-interop-rate-limit!
  [orchestrator from-agent]
  (let [now-ms (.toEpochMilli (Instant/now))
        agent-id (:id from-agent)
        limit (long (or (:interop-rate-limit-per-minute from-agent) 60))
        accepted? (atom false)]
    (swap! (:interop-windows orchestrator)
           (fn [state]
             (let [current (prune-window (get state agent-id []) now-ms)]
               (if (>= (count current) limit)
                 state
                 (do
                   (reset! accepted? true)
                   (assoc state agent-id (conj current now-ms)))))))
    (when-not @accepted?
      (throw (ex-info "Interop rate limit exceeded"
                      {:type :rate-limited
                       :agent-id agent-id
                       :limit limit})))))

(defn send-interop-message!
  [orchestrator from-agent-ref to-agent-ref {:keys [message-type content route request-id delivery-mode]
                                             :or {message-type "request"
                                                  delivery-mode "at-most-once"}}]
  (when-not (and (string? content) (not (str/blank? content)))
    (throw (ex-info "content must be a non-blank string"
                    {:type :validation-failed
                     :field :content})))
  (let [from-agent (ensure-agent-by-ref! orchestrator from-agent-ref)
        to-agent (ensure-agent-by-ref! orchestrator to-agent-ref)
        _ (enforce-interop-trust! from-agent to-agent)
        _ (enforce-interop-rate-limit! orchestrator from-agent)
        route* (choose-interop-route from-agent to-agent
                                     (when route (keyword (str/lower-case (name route)))))
        dedupe-key (when request-id [(:id from-agent) (:id to-agent) request-id])]
    (if (and dedupe-key
             (= "at-most-once" delivery-mode)
             (contains? @(:interop-deliveries orchestrator) dedupe-key))
      (get @(:interop-deliveries orchestrator) dedupe-key)
      (let [envelope {:id (random-id "interop")
                      :request-id request-id
                      :message-type message-type
                      :delivery-mode delivery-mode
                      :from-agent-id (:id from-agent)
                      :to-agent-id (:id to-agent)
                      :from-address (:logical-address from-agent)
                      :to-address (:logical-address to-agent)
                      :route (name route*)
                      :content content
                      :status "delivered"
                      :delivery-count 1
                      :created-at (now)
                      :last-delivered-at (now)
                      :acked-at nil
                      :acknowledged-by nil
                      :ack-type nil}]
        (async/>!! (:inbox to-agent)
                   {:role "user"
                    :content (str "[interop:" message-type "] "
                                  (:logical-address from-agent) ": " content)
                    :created-at (:created-at envelope)
                    :interop envelope})
        (store-interop-message! orchestrator envelope)
        (when dedupe-key
          (swap! (:interop-deliveries orchestrator) assoc dedupe-key envelope))
        (emit-event! orchestrator
                     {:event-type :agent.interop.message.sent
                      :entity-type :agent
                      :entity-id (:id from-agent)
                      :payload envelope})
        (emit-event! orchestrator
                     {:event-type :agent.interop.message.delivered
                      :entity-type :agent
                      :entity-id (:id to-agent)
                      :payload envelope})
        envelope))))

(defn acknowledge-interop-message!
  [orchestrator agent-ref message-id {:keys [ack-type]
                                      :or {ack-type "ack"}}]
  (let [agent (ensure-agent-by-ref! orchestrator agent-ref)
        envelope (ensure-interop-message! orchestrator message-id)]
    (when-not (= (:to-agent-id envelope) (:id agent))
      (throw (ex-info "Interop ack denied"
                      {:type :permission-denied
                       :reason :not-recipient
                       :agent-id (:id agent)
                       :message-id message-id})))
    (let [updated (if (:acked-at envelope)
                    envelope
                    (-> envelope
                        (assoc :status "acked")
                        (assoc :acked-at (now))
                        (assoc :acknowledged-by (:id agent))
                        (assoc :ack-type ack-type)))]
      (store-interop-message! orchestrator updated)
      (emit-event! orchestrator
                   {:event-type :agent.interop.message.acked
                    :entity-type :agent
                    :entity-id (:id agent)
                    :payload {:message-id (:id updated)
                              :from-agent-id (:from-agent-id updated)
                              :ack-type (:ack-type updated)
                              :acked-at (:acked-at updated)}})
      updated)))

(defn retry-interop-message!
  [orchestrator agent-ref message-id]
  (let [agent (ensure-agent-by-ref! orchestrator agent-ref)
        envelope (ensure-interop-message! orchestrator message-id)]
    (when-not (= (:from-agent-id envelope) (:id agent))
      (throw (ex-info "Interop retry denied"
                      {:type :permission-denied
                       :reason :not-sender
                       :agent-id (:id agent)
                       :message-id message-id})))
    (when (:acked-at envelope)
      (throw (ex-info "Acked interop message cannot be retried"
                      {:type :validation-failed
                       :field :message-id
                       :message-id message-id})))
    (let [to-agent (ensure-agent! orchestrator (:to-agent-id envelope))
          updated (-> envelope
                      (assoc :status "delivered")
                      (assoc :last-delivered-at (now))
                      (update :delivery-count (fnil inc 1)))]
      (async/>!! (:inbox to-agent)
                 {:role "user"
                  :content (str "[interop:" (:message-type updated) "] "
                                (:from-address updated) ": " (:content updated))
                  :created-at (:last-delivered-at updated)
                  :interop updated})
      (store-interop-message! orchestrator updated)
      (emit-event! orchestrator
                   {:event-type :agent.interop.message.retried
                    :entity-type :agent
                    :entity-id (:id agent)
                    :payload {:message-id (:id updated)
                              :to-agent-id (:to-agent-id updated)
                              :delivery-count (:delivery-count updated)}})
      (emit-event! orchestrator
                   {:event-type :agent.interop.message.delivered
                    :entity-type :agent
                    :entity-id (:to-agent-id updated)
                    :payload updated})
      updated)))

(defn create-channel!
  [orchestrator {:keys [name participants]
                 :or {name "Channel"
                      participants []}}]
  (let [participant-set (set participants)]
    (doseq [participant-id participant-set]
      (ensure-agent! orchestrator participant-id))
    (let [channel {:id (random-id "channel")
                   :name name
                   :participants participant-set
                   :created-at (now)
                   :messages []
                   :bus (async/chan 128)}]
      (swap! (:channels orchestrator) assoc (:id channel) channel)
      (emit-event! orchestrator
                   {:event-type :channel.created
                    :entity-type :channel
                    :entity-id (:id channel)
                    :payload {:name (:name channel)
                              :participants (vec participant-set)}})
      (channel-view channel))))

(defn list-channels
  [orchestrator]
  (->> @(:channels orchestrator)
       vals
       (sort-by :created-at)
       (mapv channel-view)))

(defn list-channel-messages
  [orchestrator channel-id]
  (:messages (ensure-channel! orchestrator channel-id)))

(defn post-channel-message!
  [orchestrator channel-id {:keys [sender-id content]}]
  (when-not (and (string? content) (not (str/blank? content)))
    (throw (ex-info "content must be a non-blank string"
                    {:type :validation-failed
                     :field :content})))
  (ensure-agent! orchestrator sender-id)
  (let [channel (ensure-channel! orchestrator channel-id)
        participants (:participants channel)]
    (when (and (seq participants)
               (not (contains? participants sender-id)))
      (throw (ex-info "Sender is not a participant"
                      {:type :permission-denied
                       :channel-id channel-id
                       :sender-id sender-id})))
    (let [message {:id (random-id "msg")
                   :sender-id sender-id
                   :content content
                   :created-at (now)
                   :channel-id channel-id}]
      (swap! (:channels orchestrator) update-in [channel-id :messages] conj message)
      (async/>!! (:bus (get @(:channels orchestrator) channel-id)) message)
      (doseq [participant-id participants
              :when (not= participant-id sender-id)]
        (let [delivered {:role "user"
                         :content (str "[channel:" (:name channel) "] "
                                       sender-id ": " content)
                         :created-at (now)
                         :channel-id channel-id
                         :sender-id sender-id}]
          (async/>!! (:inbox (ensure-agent! orchestrator participant-id)) delivered)))
      (emit-event! orchestrator
                   {:event-type :channel.message.posted
                    :entity-type :channel
                    :entity-id channel-id
                    :payload {:sender-id sender-id
                              :message-id (:id message)}})
      message)))

(defn consume-agent-inbox!
  [orchestrator llm-provider agent-id]
  (let [agent (ensure-agent! orchestrator agent-id)
        inbox (:inbox agent)
        drained (loop [acc []]
                  (if-let [message (async/poll! inbox)]
                    (recur (conj acc message))
                    acc))]
    (if (empty? drained)
      {:agent (agent-view agent)
       :consumed 0
       :response nil}
      (let [agent-after-input (update agent :messages into drained)
            completion (llm-core/complete llm-provider (build-llm-messages agent-after-input) {})
            assistant-message {:role "assistant"
                               :content completion
                               :created-at (now)}]
        (swap! (:agents orchestrator)
               assoc agent-id
               (-> agent-after-input
                   (assoc :status "idle")
                   (update :messages conj assistant-message)))
        (emit-event! orchestrator
                     {:event-type :agent.inbox.consumed
                      :entity-type :agent
                      :entity-id agent-id
                      :payload {:consumed (count drained)}})
        {:agent (agent-view (get @(:agents orchestrator) agent-id))
         :consumed (count drained)
         :response assistant-message}))))
