(ns agent.chat
  "First-class session chat loop."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.config :as config]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.planner :as planner]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
   [agent.runtime.context-pack :as context-pack]
   [agent.runtime.loop :as runtime-loop]
   [agent.telemetry :as telemetry]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.time Instant)
   (java.util UUID)))

(def default-max-steps 6)
(def memory-result-limit 5)
(def memory-max-chars 6000)
(def history-message-max-chars 8000)
(def stream-flush-interval-ms 50)
(def queued-message-metadata-key :queued)

(defn- request-id []
  (str (UUID/randomUUID)))

(defonce ^:private streaming-state (atom {}))
(defonce ^:private session-runtimes (atom {}))
(def ^:private manager-lock (Object.))

(declare emit-session-state!)

(def stopped-content runtime-loop/stopped-content)

(defn active-run
  [session-id]
  (when-let [run (and session-id (get-in @session-runtimes [session-id :active]))]
    (select-keys run [:request-id :started-at])))

(defn active? [session-id]
  (boolean (active-run session-id)))

(defn- active-llm
  [system]
  (config/active-provider-config (get-in system [:config :llm])))

(defn session-state
  [system session-id]
  (let [{:keys [active queue]} (get @session-runtimes session-id)
        llm (when system (active-llm system))]
    (cond-> {:working? (boolean active)
             :queued-count (count queue)
             :active-provider (some-> (:provider llm) name)
             :active-model (:model llm)}
      active (assoc :active-request-id (:request-id active)
                    :active-started-at (:started-at active)))))

(defn cancel-session!
  ([session-id]
   (locking manager-lock
     (if-let [{:keys [cancelled? request-id]} (and session-id (get-in @session-runtimes [session-id :active]))]
       (do
         (reset! cancelled? true)
         {:cancelled? true
          :session-id session-id
          :request-id request-id})
       {:cancelled? false
        :session-id session-id})))
  ([system session-id]
   (let [result (cancel-session! session-id)]
     (when (:cancelled? result)
       (emit-session-state! system session-id :cancel))
     result)))

(defn streaming-content
  "Returns in-progress assistant text accumulated for `session-id`, or nil."
  [session-id]
  (when session-id (get @streaming-state session-id)))

(defn- clear-streaming! [session-id]
  (when session-id
    (swap! streaming-state dissoc session-id)))

(defn- emit! [system event]
  (if-let [sink (:event-sink system)]
    (sink event)
    (sqlite/log-event! (:store system) event)))

(defn- state-event-payload [state reason]
  {:working (boolean (:working? state))
   :queued-count (:queued-count state 0)
   :active-provider (:active-provider state)
   :active-model (:active-model state)
   :active-request-id (:active-request-id state)
   :active-started-at (:active-started-at state)
   :reason (name reason)})

(defn- emit-session-state! [system session-id reason]
  (when (and system session-id)
    (emit! system {:event-type :chat.state.changed
                   :entity-type :session
                   :entity-id session-id
                   :payload (state-event-payload (session-state system session-id)
                                                 reason)})))

(defn- append-message!
  ([system session-id role content]
   (append-message! system session-id role content nil))
  ([system session-id role content extra]
   (let [message (sqlite/append-message! (:store system) session-id role content extra)
         payload (cond-> {:role role :content content}
                   (:tool-calls extra) (assoc :tool-calls (:tool-calls extra))
                   (:tool-call-id extra) (assoc :tool-call-id (:tool-call-id extra))
                   (:metadata extra) (assoc :metadata (:metadata extra))
                   (:excluded-from-context? extra) (assoc :excluded-from-context? true))]
     (emit! system {:event-type :message.appended
                    :entity-type :session
                    :entity-id session-id
                    :payload payload})
     message)))

(defn- latest-user-prompt [messages]
  (some-> (last (filter #(= "user" (if (keyword? (:role %))
                                      (name (:role %))
                                      (:role %)))
                        messages))
          llm-messages/content-text))

(defn- truncate-text [text max-chars]
  (let [text* (or text "")]
    (if (> (count text*) max-chars)
      (str (subs text* 0 max-chars)
           "\n\n[truncated "
           (- (count text*) max-chars)
           " chars]")
      text*)))

(defn- db-message-content [role content]
  (let [content* (or content "")]
    (if (= "tool" role)
      (truncate-text content* history-message-max-chars)
      content*)))

(defn- session-messages [system session-id]
  (if session-id
    (mapv (fn [{:keys [role content] :as message}]
            (llm-messages/message->internal
             (assoc message :content (db-message-content role content))))
          (sqlite/current-llm-context (:store system)
                                      session-id
                                      {:include-entry-id? true}))
    (llm-messages/messages->internal [])))

(defn- persist-user-turn! [system session-id messages]
  (when-let [content (and session-id (latest-user-prompt messages))]
    (when-not (str/blank? content)
      (append-message! system session-id "user" content))))

(defn- compact-memory-json
  "Serializes recalled memory to JSON, capped at memory-max-chars to keep
   recall payloads bounded."
  [value]
  (let [text (json/generate-string value)]
    (if (> (count text) memory-max-chars)
      (subs text 0 memory-max-chars)
      text)))

(defn- recall-memory [system session-id query]
  (let [prompt (memory/read-prompt-memory (:memory-service system))
        search (when-not (str/blank? (or query ""))
                 (memory/search-memory (:memory-service system)
                                       query
                                       {:limit memory-result-limit
                                        :session-id session-id
                                        :entity-type :session
                                        :entity-id session-id
                                        :scope {:type :session :id session-id}}))]
    {:prompt prompt
     :search search}))

(defn- memory-message [recall]
  {:role "system"
   :content (prompts/render "memory-context"
                            {:memory_json (compact-memory-json recall)})})

(defn- iris-context-message [system]
  (when-let [context (some-> (get-in system [:config :iris :context]) str/trim not-empty)]
    {:role "system" :content context}))

(defn- approval-expires-at [system]
  (str (.plusSeconds (Instant/now)
                     (long (get-in system [:config :tools :approvals :ttl-seconds] 900)))))

(defn- profile-permissions [system profile]
  (set (get-in system [:config :tools :permissions profile]
               (get-in system [:config :tools :permissions :agent] #{}))))

(defn- all-tool-names [system]
  (set (map :name (tools/list-tools (:tool-registry system)))))

(defrecord ChatKernelOps [system session-id request-id extra-context]
  kernel-ops/KernelOps
  (spawn-task-worker! [_ _]
    (throw (ex-info "Chat loop cannot spawn workers yet"
                    {:type :unsupported-directive})))
  (execute-agent-tool! [_ _ tool-name input context]
    (tools/execute-tool (:tool-registry system)
                        tool-name
                        input
                        (merge (or extra-context {})
                               context
                               {:user (or session-id "chat")
                                :session-id session-id
                                :request-id request-id
                                :permissions (profile-permissions system :chat)
                                :allowed-tools (all-tool-names system)
                                :yolo? (true? (get-in system [:config :tools :yolo?]))})))
  (send-agent-message! [_ _ _]
    (throw (ex-info "Chat loop cannot send agent messages yet"
                    {:type :unsupported-directive})))
  (patch-agent-state! [_ _ patch] patch)
  (set-agent-status! [_ _ _] nil)
  (emit-kernel-event! [_ event] (emit! system event)))

(defn- request-approval! [system session-id receipt]
  (let [tool-name (keyword (:tool-name receipt))
        approval (tool-approvals/create-request!
                  (:store system)
                  {:tool-name tool-name
                   :input (:input receipt)
                   :requested-by (or session-id "chat")
                   :reason "chat tool call"
                   :expires-at (approval-expires-at system)})]
    (emit! system {:event-type :chat.tool.approval_required
                   :entity-type :session
                   :entity-id session-id
                   :request-id (:id approval)
                   :payload {:tool-name (name tool-name)
                             :approval-id (:id approval)
                             :input (:input receipt)
                             :reason (:reason receipt)}})
    approval))

(defn- persist-completion!
  ([system session-id prompt content request-id]
   (persist-completion! system session-id prompt content request-id nil))
  ([system session-id prompt content request-id extra]
  (let [llm (active-llm system)
        assistant-message (when session-id
                            (append-message! system session-id "assistant" content extra))]
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider (:provider llm)
                             :model (:model llm)
                             :prompt prompt
                             :response content})
    (emit! system {:event-type :completion.completed
                   :entity-type :session
                   :entity-id session-id
                   :request-id request-id
                   :payload {:provider (name (:provider llm))
                             :model (:model llm)}})
    assistant-message)))

(defn- extract-turn-memory! [system session-id user-message assistant-message request-id]
  (when (and session-id user-message assistant-message)
    (try
      (memory/extract-and-save-facts!
       (:memory-service system)
       (or (:fact-llm-provider system) (:llm-provider system))
       {:user-message (:content user-message)
        :assistant-message (:content assistant-message)}
       {:session-id session-id
        :source-session-id session-id
        :source-message-ids [(:id user-message) (:id assistant-message)]
        :source-request-id request-id
        :model (config/active-model (get-in system [:config :llm]))})
      (catch Exception e
        (emit! system {:event-type :chat.memory.extract_failed
                       :entity-type :session
                       :entity-id session-id
                       :request-id request-id
                       :payload {:message (.getMessage e)
                                 :type (some-> e ex-data :type)}})))))

(defn- error-content [error]
  (str "Chat failed: " (.getMessage ^Throwable error)))

(defn- error-payload [error]
  (cond-> {:message (.getMessage ^Throwable error)}
    (ex-data error) (merge (ex-data error))))

(defn- stream-delta-text [value]
  (cond
    (string? value) value
    (= :error (:type value)) (throw (ex-info (or (:error value) "LLM stream failed")
                                             (merge {:type :llm-stream-error}
                                                    (:details value))))
    (map? value) (or (:content value)
                     (get-in value [:delta :content])
                     (get-in value [:message :content])
                     "")
    (nil? value) ""
    :else (str value)))

(defn- canonical-event-type [event]
  (keyword (str/replace (name (:event-type event)) #"_" "-")))

(defn- same-event-type? [event event-type]
  (= event-type (canonical-event-type event)))

(defn- event-payload [event]
  (let [payload (:payload event)]
    (if (map? payload) payload {:value payload})))

(defn- loop-event-sink
  [system subscribers]
  (fn [event]
    (doseq [subscriber subscribers]
      (try
        (subscriber event)
        (catch Exception _ nil)))
    (emit! system event)))

(defn- text-delta-event? [event]
  (let [payload (event-payload event)]
    (and (same-event-type? event :message-update)
         (string? (:delta payload))
         (not= "" (:delta payload)))))

(defn- buffered-delta-event [event text]
  (-> event
      (assoc :payload (assoc (event-payload event) :delta text))
      (assoc :timestamp (str (Instant/now)))))

(defn- stream-delta-flusher
  [emit-event!]
  (let [lock (Object.)
        state (atom {:text ""
                     :event nil
                     :scheduled? false
                     :timer-id 0})
        flush! (fn [expected-timer-id]
                 (let [event* (locking lock
                                (let [{:keys [text event timer-id]} @state]
                                  (when (or (nil? expected-timer-id)
                                            (= expected-timer-id timer-id))
                                    (swap! state assoc
                                           :text ""
                                           :event nil
                                           :scheduled? false)
                                    (when (and event (not= "" text))
                                      (buffered-delta-event event text)))))]
                   (when event*
                     (emit-event! event*))))]
    {:flush! #(flush! nil)
     :emit! (fn [event]
              (if (text-delta-event? event)
                (let [[schedule? timer-id] (locking lock
                                             (let [schedule? (not (:scheduled? @state))]
                                               (swap! state
                                                      (fn [s]
                                                        (cond-> (-> s
                                                                    (update :text str (get-in event [:payload :delta]))
                                                                    (assoc :event event
                                                                           :scheduled? true))
                                                          schedule? (update :timer-id inc))))
                                               [schedule? (:timer-id @state)]))]
                  (when schedule?
                    (future
                      (Thread/sleep stream-flush-interval-ms)
                      (flush! timer-id))))
                (do
                  (flush! nil)
                  (emit-event! event))))}))

(defn- persist-final-assistant!
  ([system session-id prompt content request-id]
   (persist-final-assistant! system session-id prompt content request-id nil))
  ([system session-id prompt content request-id extra]
   (persist-completion! system session-id prompt content request-id extra)))

(defn- message-extra [payload]
  (select-keys payload [:tool-calls :tool-call-id :metadata :excluded-from-context?]))

(defn- persistence-subscriber
  [system session-id prompt request-id persisted]
  (fn [event]
    (let [payload (event-payload event)]
      (cond
        (and session-id
             (same-event-type? event :message-update)
             (contains? #{:context-compacted "context-compacted"} (:kind payload)))
        (sqlite/append-entry! (:store system)
                              session-id
                              {:type :compaction
                               :payload (:compaction payload)})

        (same-event-type? event :message-end)
        (let [{:keys [role content final? tool-turn? audit? tool-calls tool-call-id]} payload]
          (cond
            (and (= "assistant" role) final?)
            (let [message (persist-final-assistant! system
                                                    session-id
                                                    prompt
                                                    content
                                                    request-id
                                                    (message-extra payload))]
              (swap! persisted assoc :assistant-message message))

            (and session-id (= "assistant" role) audit?)
            (append-message! system session-id "assistant" content (message-extra payload))

            (and session-id (= "assistant" role) tool-turn?)
            (append-message! system session-id "assistant" content {:tool-calls tool-calls})

            (and session-id (= "tool" role) tool-turn?)
            (append-message! system session-id "tool" content {:tool-call-id tool-call-id})))))))

(defn- streaming-subscriber
  [session-id on-delta]
  (fn [event]
    (let [payload (event-payload event)]
      (cond
        (and (same-event-type? event :message-update)
             (string? (:delta payload))
             (not= "" (:delta payload)))
        (do
          (when session-id
            (swap! streaming-state update session-id (fnil str "") (:delta payload)))
          (when on-delta
            (on-delta (:delta payload))))

        (and (same-event-type? event :message-end)
             (or (:final? payload) (:tool-turn? payload)))
        (clear-streaming! session-id)))))

(defn- tool-call-subscriber
  [on-tool-call]
  (fn [event]
    (when (and on-tool-call
               (same-event-type? event :tool-execution-end))
      (let [{:keys [tool-call receipt]} (event-payload event)]
        (try
          (on-tool-call {:tool-call tool-call :receipt receipt})
          (catch Exception _ nil))))))

(defn- legacy-event
  [event event-type payload]
  {:event-type event-type
   :entity-type (:entity-type event)
   :entity-id (:entity-id event)
   :request-id (:request-id event)
   :payload payload})

(defn- legacy-subscriber
  [system]
  (fn [event]
    (let [payload (event-payload event)]
      (case (canonical-event-type event)
        :agent-start
        (emit! system (legacy-event event :chat.started payload))

        :message-update
        (cond
          (= :memory-recalled (:kind payload))
          (emit! system (legacy-event event :chat.memory.recalled payload))

          (contains? payload :delta)
          (emit! system (legacy-event event "chat.delta" {:delta (:delta payload)})))

        :turn-end
        (emit! system (legacy-event event :chat.planner.step payload))

        :tool-execution-update
        (when (= :approval-required (:kind payload))
          (emit! system (legacy-event event :chat.tool.approval_required payload)))

        :message-start
        (when (:fallback? payload)
          (emit! system (legacy-event event :chat.fallback_completion payload)))

        :agent-end
        (case (keyword (:stop-reason payload))
          :planner-error (emit! system (legacy-event event :chat.error payload))
          :cancelled (emit! system (legacy-event event :chat.cancelled payload))
          :max-tokens (emit! system (legacy-event event :chat.failed payload))
          :error (emit! system (legacy-event event :chat.failed payload))
          (:completed :approval-required :max-steps)
          (emit! system (legacy-event event :chat.completed payload))
          nil)

        nil))))

(defn- consume-llm-stream-with!
  [ch emit-delta]
  (loop [acc ""]
    (if-let [value (async/<!! ch)]
      (let [delta (stream-delta-text value)]
        (emit-delta delta)
        (recur (str acc delta)))
      acc)))

(defn- fallback-content!
  [system messages session-id request-id error stream? emit-delta]
  (try
    (let [content (if stream?
                    (let [ch (llm-core/stream (:llm-provider system)
                                              messages
                                              {:model (config/active-model (get-in system [:config :llm]))})]
                      (consume-llm-stream-with! ch emit-delta))
                    (telemetry/complete-with-telemetry! (:telemetry system)
                                                        (:llm-provider system)
                                                        messages
                                                        {}
                                                        {:agent-id (or session-id "chat")
                                                         :observer (:observer system)
                                                         :trace (:trace system)
                                                         :request-id request-id
                                                         :model (config/active-model (get-in system [:config :llm]))}))]
      {:content content
       :fallback? true})
    (catch Exception fallback-error
      {:content (error-content fallback-error)
       :fallback? true
       :error? true
       :initial-error (.getMessage error)
       :initial-type (some-> error ex-data :type)})))

(defn- summarize-context!
  [system prompt]
  (let [response (llm-core/invoke
                  (:llm-provider system)
                  {:model (config/active-model (get-in system [:config :llm]))
                   :messages [{:role "system"
                               :content "Summarize compacted conversation context for the next LLM call."}
                              {:role "user"
                               :content prompt}]
                   :max-tokens (get-in system [:config :chat :compaction :summary-max-tokens] 512)
                   :metadata {:context-pack-summary true}})]
    (if (map? response)
      (or (:content response)
          (llm-messages/content-text response)
          "")
      (str response))))

(defn- context-pack-fn
  [system]
  (let [cfg (get-in system [:config :chat :compaction])]
    (fn [ctx]
      (context-pack/pack-context
       (assoc ctx
              :config cfg
              :summarizer-fn (fn [{:keys [prompt]}]
                               (summarize-context! system prompt)))))))

(defn- run-turn!
  "Run a chat turn for `session-id`. Public wrapper keeps persistence, transport
   callbacks, and legacy events around the evented runtime loop."
  [system {:keys [messages session-id max-steps context on-delta on-tool-call stream?
                  cancellation-token persist-user? user-message]
           :or {persist-user? true}
           :as opts}]
  (let [max-steps (long (or max-steps
                            (get-in system [:config :chat :max-steps])
                            default-max-steps))
        request-id (or (:request-id opts) (request-id))
        cancelled? (or cancellation-token (atom false))
        prompt (latest-user-prompt messages)
        user-message (if persist-user?
                       (persist-user-turn! system session-id messages)
                       user-message)
        _ (when session-id
            (try
              (compaction/auto-compact! (:store system) session-id (:chat (:config system)))
              (catch Exception _ nil)))
        history (if session-id
                  (session-messages system session-id)
                  (llm-messages/messages->internal messages))
        recall (recall-memory system session-id prompt)
        iris-context (iris-context-message system)
        active-mode (some-> (and session-id
                                  (sqlite/get-session (:store system) session-id))
                            :active-mode)
        mode-messages (prompts/apply-mode [] active-mode)
        stream-content? (and (or stream? on-delta)
                             (not (false? (get-in system [:config :llm :stream-content?] true))))
        persisted (atom {})
        subscribers [(persistence-subscriber system session-id prompt request-id persisted)
                     (streaming-subscriber session-id on-delta)
                     (tool-call-subscriber on-tool-call)
                     (legacy-subscriber system)]
        event-sink* (loop-event-sink system subscribers)
        flusher (stream-delta-flusher event-sink*)
        event-sink (:emit! flusher)
        ops (->ChatKernelOps system session-id request-id context)
        pack-context (context-pack-fn system)
        context-injectors (cond-> []
                            iris-context (conj (constantly [iris-context]))
                            (seq mode-messages) (conj (constantly mode-messages))
                            true (conj (constantly [(memory-message recall)])))]
    (event-sink {:event-type :message-update
                 :entity-type :session
                 :entity-id session-id
                 :request-id request-id
                 :timestamp (str (Instant/now))
                 :payload {:kind :memory-recalled
                           :query prompt
                           :message-count (count (get-in recall [:search :messages]))
                           :event-count (count (get-in recall [:search :events]))
                           :fact-count (count (get-in recall [:search :facts]))
                           :prompt-document-count (count (get-in recall [:prompt :documents]))}})
    (try
      (let [result (runtime-loop/run!
                    {:messages history
                     :context-injectors context-injectors
                     :system-prompt (planner/planner-system-prompt)
                     :tools (tools/list-tools (:tool-registry system))
                     :model (config/active-model (get-in system [:config :llm]))
                     :provider-config (:llm-provider system)
                     :telemetry (:telemetry system)
                     :observer (:observer system)
                     :trace (:trace system)
                     :request-id request-id
                     :session-id session-id
                     :agent-id (or session-id "chat")
                     :context-pack-fn pack-context
                     :max-steps max-steps
                     :stream? stream-content?
                     :cancellation-token cancelled?
                     :event-sink event-sink
                     :execute-step-fn (fn [executable-step]
                                        (kernel-runtime/execute-step!
                                         ops
                                         (or session-id "chat")
                                         (kernel-schema/normalize-step executable-step)
                                         {:execute-safe-tools? true
                                          :yolo? (true? (get-in system [:config :tools :yolo?]))}))
                     :approval-fn (fn [receipts]
                                    (mapv #(request-approval! system session-id %) receipts))
                     :fallback-fn (fn [{:keys [messages error stream? emit-delta]}]
                                    (fallback-content! system
                                                       messages
                                                       session-id
                                                       request-id
                                                       error
                                                       stream?
                                                       emit-delta))})]
        (extract-turn-memory! system
                              session-id
                              user-message
                              (:assistant-message @persisted)
                              request-id)
        (assoc result :stream? (boolean (or stream? on-delta))))
      (finally
        ((:flush! flusher))))))

(defn- empty-queue []
  clojure.lang.PersistentQueue/EMPTY)

(defn- active-turn [request-id cancelled? stream?]
  {:request-id request-id
   :started-at (str (Instant/now))
   :cancelled? cancelled?
   :stream? (boolean stream?)
   :stream-state (atom {})})

(defn- enqueue-item [state item]
  (update (or state {:queue (empty-queue)})
          :queue
          (fnil conj (empty-queue))
          item))

(defn- queued-user-metadata [request-id]
  {queued-message-metadata-key true
   :request-id request-id})

(defn- persist-queued-user-turn! [system session-id messages request-id]
  (when-let [content (and session-id (latest-user-prompt messages))]
    (when-not (str/blank? content)
      (append-message! system
                       session-id
                       "user"
                       content
                       {:metadata (queued-user-metadata request-id)
                        :excluded-from-context? true
                        :select-leaf? false}))))

(defn- activate-queued-message! [system {:keys [queued-message request-id]}]
  (when queued-message
    (let [metadata (-> (:metadata queued-message)
                       (dissoc queued-message-metadata-key)
                       (assoc :request-id request-id
                              :activated-at (str (Instant/now))))]
      (sqlite/update-message-runtime-flags! (:store system)
                                            (:id queued-message)
                                            {:metadata metadata
                                             :excluded-from-context? false
                                             :session-id (:session-id queued-message)
                                             :reparent-to-current-leaf? true
                                             :select-leaf? true})
      (emit! system {:event-type :message.updated
                     :entity-type :session
                     :entity-id (:session-id queued-message)
                     :request-id request-id
                     :payload {:message-id (:id queued-message)
                               :role "user"
                               :metadata metadata
                               :excluded-from-context? false}})
      (assoc queued-message :metadata metadata :excluded-from-context? false))))

(declare run-queued-item!)

(defn- start-next-queued! [system session-id request-id terminal-reason]
  (let [{:keys [item cleared?]} (locking manager-lock
                                  (let [{:keys [active queue]} (get @session-runtimes session-id)]
                                    (when (= request-id (:request-id active))
                                      (if (seq queue)
                                        (let [item (peek queue)
                                              queue* (pop queue)]
                                          (swap! session-runtimes assoc session-id
                                                 {:active (active-turn (:request-id item)
                                                                       (:cancelled? item)
                                                                       (get-in item [:opts :stream?]))
                                                  :queue queue*})
                                          {:item item})
                                        (do
                                          (swap! session-runtimes dissoc session-id)
                                          {:cleared? true})))))]
    (cond
      item
      (do
        (emit-session-state! system session-id :drain)
        (future (run-queued-item! system item)))

      cleared?
      (emit-session-state! system session-id terminal-reason))))

(defn- terminal-state-reason [result]
  (cond
    (:cancelled? result) :cancel
    (:error? result) :error
    :else :complete))

(defn- run-active-item! [system {:keys [opts request-id cancelled? queued-message result]}]
  (let [terminal-reason (atom :complete)]
    (try
      (let [activated-message (activate-queued-message! system {:queued-message queued-message
                                                                :request-id request-id})
            result* (run-turn! system
                               (cond-> (assoc opts
                                              :request-id request-id
                                              :cancellation-token cancelled?)
                                 queued-message (assoc :persist-user? false
                                                       :user-message activated-message)))]
        (reset! terminal-reason (terminal-state-reason result*))
        (when result
          (deliver result {:result result*}))
        result*)
      (catch Throwable t
        (reset! terminal-reason :error)
        (when result
          (deliver result {:error t}))
        (throw t))
      (finally
        (start-next-queued! system (:session-id opts) request-id @terminal-reason)))))

(defn- run-queued-item! [system item]
  (run-active-item! system item))

(defn- begin-managed-run! [system {:keys [session-id stream?] :as opts} request-id cancelled? result]
  (locking manager-lock
    (if (get-in @session-runtimes [session-id :active])
      (let [queued-message (persist-queued-user-turn! system session-id (:messages opts) request-id)
            item {:opts opts
                  :request-id request-id
                  :cancelled? cancelled?
                  :queued-message queued-message
                  :result result}]
        (swap! session-runtimes update session-id enqueue-item item)
        (emit! system {:event-type :chat.queued
                       :entity-type :session
                       :entity-id session-id
                       :request-id request-id
                       :payload {:message-id (:id queued-message)
                                 :queued-count (get-in (session-state system session-id)
                                                       [:queued-count])}})
        (emit-session-state! system session-id :queued)
        :queued)
      (do
        (swap! session-runtimes assoc-in [session-id :active]
               (active-turn request-id cancelled? stream?))
        (emit-session-state! system session-id :start)
        :active))))

(defn run!
  "Run or queue a chat turn for `session-id`."
  [system {:keys [session-id] :as opts}]
  (if-not session-id
    (run-turn! system opts)
    (let [request-id* (or (:request-id opts) (request-id))
          cancelled? (atom false)
          result (promise)
          mode (begin-managed-run! system opts request-id* cancelled? result)]
      (case mode
        :active
        (run-active-item! system {:opts opts
                                  :request-id request-id*
                                  :cancelled? cancelled?})

        :queued
        (let [{:keys [result error]} @result]
          (if error
            (throw error)
            result)))))) 
