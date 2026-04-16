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
    :event-sink event-sink}))

(defn health-check
  [orchestrator]
  {:healthy true
   :agent-count (count @(:agents orchestrator))
   :channel-count (count @(:channels orchestrator))})

(defn spawn-agent!
  [orchestrator {:keys [name role parent-id system-prompt]
                 :or {name "Subagent"
                      role "worker"}}]
  (let [created-at (now)
        agent {:id (random-id "agent")
               :name name
               :role role
               :parent-id parent-id
               :system-prompt system-prompt
               :status "idle"
               :created-at created-at
               :messages []
               :inbox (async/chan 64)}]
    (swap! (:agents orchestrator) assoc (:id agent) agent)
    (emit-event! orchestrator
                 {:event-type :agent.created
                  :entity-type :agent
                  :entity-id (:id agent)
                  :payload {:name (:name agent)
                            :role (:role agent)
                            :parent-id (:parent-id agent)}})
    (agent-view agent)))

(defn list-agents
  [orchestrator]
  (->> @(:agents orchestrator)
       vals
       (sort-by :created-at)
       (mapv agent-view)))

(defn get-agent
  [orchestrator agent-id]
  (some-> (ensure-agent! orchestrator agent-id) agent-view))

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
