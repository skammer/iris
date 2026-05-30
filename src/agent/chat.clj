(ns agent.chat
  "First-class session chat loop."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.chat.kernel-ops :as chat-kernel-ops]
   [agent.chat.memory :as chat-memory]
   [agent.chat.streaming :as chat-streaming]
   [agent.chat.util :as chat-util]
   [agent.config :as config]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [agent.loop :as loop-support]
   [agent.persistence.sqlite :as sqlite]
   [agent.planner :as planner]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
   [agent.runtime.context-pack :as context-pack]
   [agent.runtime.loop :as runtime-loop]
   [agent.skills :as skills]
   [agent.telemetry :as telemetry]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.time Instant)
   (java.util UUID)))

(def default-max-steps 6)
(def history-message-max-chars 8000)
(def queued-message-metadata-key :queued)

(defn- request-id []
  (str (UUID/randomUUID)))

(declare emit-session-state! run!)

(def stopped-content runtime-loop/stopped-content)

(defn create-service
  []
  {:streaming-state (atom {})
   :session-runtimes (atom {})
   :loop-workers (atom {})
   :manager-lock (Object.)})

(defn- require-service [system]
  (or (:chat-service system)
      (throw (ex-info "chat-service missing from system"
                      {:type :chat-service-missing}))))

(defn stop!
  [service]
  (if service
    (do
      (doseq [worker (vals @(:loop-workers service))]
        (future-cancel worker))
      (reset! (:loop-workers service) {})
      (reset! (:session-runtimes service) {})
      (reset! (:streaming-state service) {})
      {:stopped true})
    {:stopped false}))

(defn reload!
  [service]
  (stop! service)
  (create-service))

(defn health-check
  [service]
  (if service
    (let [runtimes @(:session-runtimes service)]
      {:healthy true
       :active-session-count (count (filter :active (vals runtimes)))
       :queued-count (reduce + (map #(count (:queue %)) (vals runtimes)))
       :loop-worker-count (count @(:loop-workers service))
       :streaming-session-count (count @(:streaming-state service))})
    {:healthy false
     :reason "chat-service missing"}))

(defn active-run
  [system session-id]
  (when-let [run (and session-id
                      (some-> system :chat-service :session-runtimes deref
                              (get-in [session-id :active])))]
    (select-keys run [:request-id :started-at])))

(defn active? [system session-id]
  (boolean (active-run system session-id)))

(defn- active-llm
  [system]
  (config/active-provider-config (get-in system [:config :llm])))

(defn session-state
  [system session-id]
  (let [{:keys [active queue]} (some-> system :chat-service :session-runtimes deref
                                       (get session-id))
        llm (when system (active-llm system))
        loop-state (loop-support/active-state session-id)]
    (cond-> {:working? (boolean active)
             :queued-count (count queue)
             :active-provider (some-> (:provider llm) name)
             :active-model (:model llm)}
      active (assoc :active-request-id (:request-id active)
                    :active-started-at (:started-at active))
      loop-state (assoc :loop-active? true
                        :loop-label (loop-support/iteration-label loop-state)
                        :loop-plan (:plan-file loop-state)))))

(defn cancel-session!
  [system session-id]
  (if-let [service (:chat-service system)]
    (let [result (locking (:manager-lock service)
                   (if-let [{:keys [cancelled? request-id]}
                            (and session-id
                                 (get-in @(:session-runtimes service) [session-id :active]))]
                     (do
                       (reset! cancelled? true)
                       {:cancelled? true
                        :session-id session-id
                        :request-id request-id})
                     {:cancelled? false
                      :session-id session-id}))]
      (when (:cancelled? result)
        (emit-session-state! system session-id :cancel))
      result)
    {:cancelled? false
     :session-id session-id}))

(defn streaming-content
  "Returns in-progress assistant text accumulated for `session-id`, or nil."
  [system session-id]
  (when session-id
    (some-> system :chat-service :streaming-state deref (get session-id))))

(defn- clear-streaming! [system session-id]
  (when session-id
    (when-let [state (some-> system :chat-service :streaming-state)]
      (swap! state dissoc session-id))))

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
    (chat-util/emit! system {:event-type :session-state-changed
                   :entity-type :session
                   :entity-id session-id
                   :payload (state-event-payload (session-state system session-id)
                                                 reason)})))

(defn- message-end-payload [role content extra]
  (cond-> {:role role :content content}
    (:content-blocks extra) (assoc :content-blocks (:content-blocks extra))
    (:tool-calls extra) (assoc :tool-calls (:tool-calls extra))
    (:tool-call-id extra) (assoc :tool-call-id (:tool-call-id extra))
    (:metadata extra) (assoc :metadata (:metadata extra))
    (:excluded-from-context? extra) (assoc :excluded-from-context? true)))

(defn- append-message-record!
  ([system session-id role content]
   (append-message-record! system session-id role content nil))
  ([system session-id role content extra]
   (sqlite/append-message! (:store system) session-id role content extra)))

(defn- append-message!
  ([system session-id role content]
   (append-message! system session-id role content nil))
  ([system session-id role content extra]
   (let [message (append-message-record! system session-id role content extra)]
     (chat-util/emit! system {:event-type :message-end
                    :entity-type :session
                    :entity-id session-id
                    :payload (message-end-payload role content extra)})
     message)))

(defn- append-control-turn! [system session-id user-text content metadata]
  (when-not (str/blank? (or user-text ""))
    (append-message! system session-id "user" user-text {:metadata metadata}))
  (append-message! system session-id "assistant" content {:metadata metadata})
  (emit-session-state! system session-id :loop)
  {:content content
   :stop-reason :loop-control})

(defn- loop-worker-running? [system session-id]
  (when-let [worker (get @(-> system require-service :loop-workers) session-id)]
    (not (future-done? worker))))

(defn- loop-complete-message [record]
  (or (:content record)
      "Loop stopped."))

(defn- run-loop-worker! [system session-id]
  (try
    (loop []
      (when-let [state (loop-support/prepare-iteration! session-id)]
        (let [result (run! system
                           {:messages [{:role "user"
                                        :content (loop-support/build-prompt state)}]
                            :session-id session-id
                            :loop-turn? true})
              loop-opts (loop-support/options (:config system) {})
              validation (loop-support/run-validation (:run-cmd state) loop-opts)
              record (loop-support/record-result! session-id
                                                  (:content result)
                                                  validation
                                                  (:config system))]
          (when (:stopped? record)
            (append-message! system session-id "assistant" (loop-complete-message record)
                             {:metadata {:loop-control true}}))
          (when (loop-support/active? session-id)
            (recur)))))
    (catch Throwable t
      (when (loop-support/active? session-id)
        (loop-support/stop! session-id)
        (append-message! system session-id "assistant"
                         (str "Loop stopped: " (.getMessage t))
                         {:metadata {:loop-control true :error true}})))
    (finally
      (swap! (:loop-workers (require-service system)) dissoc session-id)
      (emit-session-state! system session-id :loop))))

(defn- start-loop-worker! [system session-id]
  (when-not (loop-worker-running? system session-id)
    (let [worker (future (run-loop-worker! system session-id))]
      (swap! (:loop-workers (require-service system)) assoc session-id worker))))

(defn loop-command!
  [system session-id text]
  (when-let [{:keys [content started? stopped?] :as result}
             (loop-support/handle-control! session-id (:config system) text)]
    (when stopped?
      (cancel-session! system session-id))
    (let [response (append-control-turn! system
                                         session-id
                                         text
                                         content
                                         {:loop-control true})]
      (when started?
        (start-loop-worker! system session-id))
      (assoc response :loop-control result))))

(defn- block-while-loop-active! [system session-id text]
  (append-control-turn! system
                        session-id
                        text
                        "Loop active. Use /loop status or /loop stop."
                        {:loop-control true :blocked-by-loop true}))

(defn- user-message? [message]
  (= "user" (if (keyword? (:role message))
              (name (:role message))
              (:role message))))

(defn- latest-user-message [messages]
  (last (filter user-message? messages)))

(def ^:private media-block-types #{:image :audio :video :file})

(defn- media-block? [block]
  (contains? media-block-types (:type block)))

(defn- content-blocks-extra [message]
  (let [blocks (seq (get (llm-messages/message->internal message) :content))]
    (when (some media-block? blocks)
      {:content-blocks (vec blocks)})))

(defn- latest-user-prompt [messages]
  (some-> (latest-user-message messages)
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
    (if (and (= "tool" role) (string? content*))
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
  (when-let [message (and session-id (latest-user-message messages))]
    (let [content (llm-messages/content-text message)
          extra (content-blocks-extra message)]
      (when (or (not (str/blank? content)) extra)
        (append-message-record! system session-id "user" content extra)))))

(defn- iris-context-message [system]
  (when-let [context (some-> (get-in system [:config :iris :context]) str/trim not-empty)]
    {:role "system" :content context}))

(defn- skill-context-message [system prompt]
  (when-let [registry (:skills-registry system)]
    (when-let [section (some-> (skills/invoked-skills-section registry prompt)
                               str/trim
                               not-empty)]
      {:role "system" :content section})))

(defn- approval-expires-at [system]
  (str (.plusSeconds (Instant/now)
                     (long (get-in system [:config :tools :approvals :ttl-seconds] 900)))))

(defn- request-approval! [system session-id receipt]
  (let [tool-name (keyword (:tool-name receipt))
        approval (tool-approvals/create-request!
                  (:store system)
                  {:tool-name tool-name
                   :input (:input receipt)
                   :requested-by (or session-id "chat")
                   :reason "chat tool call"
                   :expires-at (approval-expires-at system)})]
    approval))

(defn- persist-completion!
  ([system session-id prompt content request-id]
   (persist-completion! system session-id prompt content request-id nil))
  ([system session-id prompt content _request-id extra]
  (let [llm (active-llm system)
        assistant-message (when session-id
                            (append-message-record! system session-id "assistant" content extra))]
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider (:provider llm)
                             :model (:model llm)
                             :prompt prompt
                             :response content})
    assistant-message)))

(defn- error-content [error]
  (str "Chat failed: " (.getMessage ^Throwable error)))

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

(defn- loop-event-sink
  [system subscribers]
  (fn [event]
    (doseq [{:keys [operation f]} subscribers]
      (try
        (f event)
        (catch Exception e
          (chat-util/emit-operation-failed! system
                                  (:entity-id event)
                                  (:request-id event)
                                  operation
                                  e
                                  {:trigger-event-type (:event-type event)}))))
    (chat-util/emit! system event)))

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
    (let [payload (chat-util/event-payload event)]
      (cond
        (and session-id
             (chat-util/same-event-type? event :message-update)
             (contains? #{:context-compacted "context-compacted"} (:kind payload)))
        (sqlite/append-entry! (:store system)
                              session-id
                              {:type :compaction
                               :payload (:compaction payload)})

        (chat-util/same-event-type? event :message-end)
        (let [{:keys [role content final? tool-turn? audit? tool-call-id]} payload]
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
            (append-message-record! system session-id "assistant" content (message-extra payload))

            (and session-id (= "assistant" role) tool-turn?)
            (append-message-record! system session-id "assistant" content (message-extra payload))

            (and session-id (= "tool" role) tool-turn?)
            (append-message-record! system session-id "tool" content {:tool-call-id tool-call-id})))))))

(defn- streaming-subscriber
  [system session-id on-delta]
  (fn [event]
    (let [payload (chat-util/event-payload event)]
      (cond
        (and (chat-util/same-event-type? event :message-update)
             (string? (:delta payload))
             (not= "" (:delta payload)))
        (do
          (when session-id
            (swap! (:streaming-state (require-service system))
                   update session-id (fnil str "") (:delta payload)))
          (when on-delta
            (on-delta (:delta payload))))

        (and (chat-util/same-event-type? event :message-end)
             (or (:final? payload) (:tool-turn? payload)))
        (clear-streaming! system session-id)))))

(defn- tool-call-subscriber
  [on-tool-call]
  (fn [event]
    (when (and on-tool-call
               (chat-util/same-event-type? event :tool-execution-end))
      (let [{:keys [tool-call receipt]} (chat-util/event-payload event)]
        (on-tool-call {:tool-call tool-call :receipt receipt})))))

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
   callbacks, and runtime events around the evented runtime loop."
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
              (catch Exception e
                (chat-util/emit-operation-failed! system session-id request-id :auto-compact e))))
        history (if session-id
                  (session-messages system session-id)
                  (llm-messages/messages->internal messages))
        recall (chat-memory/recall-memory system session-id prompt)
        iris-context (iris-context-message system)
        active-mode (some-> (and session-id
                                  (sqlite/get-session (:store system) session-id))
                            :active-mode)
        mode-messages (prompts/apply-mode [] active-mode)
        skill-message (skill-context-message system prompt)
        stream-content? (and (or stream? on-delta)
                             (not (false? (get-in system [:config :llm :stream-content?] true))))
        persisted (atom {})
        subscribers [{:operation :persistence
                      :f (persistence-subscriber system session-id prompt request-id persisted)}
                     {:operation :streaming-callback
                      :f (streaming-subscriber system session-id on-delta)}
                     {:operation :tool-call-callback
                      :f (tool-call-subscriber on-tool-call)}]
        event-sink* (loop-event-sink system subscribers)
        flusher (chat-streaming/stream-delta-flusher event-sink*)
        event-sink (:emit! flusher)
        ops (chat-kernel-ops/->ChatKernelOps system session-id request-id context)
        pack-context (context-pack-fn system)
        context-injectors (cond-> []
                            iris-context (conj (constantly [iris-context]))
                            (seq mode-messages) (conj (constantly mode-messages))
                            skill-message (conj (constantly [skill-message]))
                            true (conj (constantly [(chat-memory/memory-message recall)])))]
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
                     :chat-profile (config/chat-profile (:config system))
                     :telemetry (:telemetry system)
                     :observer (:observer system)
                     :trace (:trace system)
                     :request-id request-id
                     :session-id session-id
                     :agent-id (or session-id "chat")
                     :context-pack-fn pack-context
                     :max-steps max-steps
                     :stream? stream-content?
                     :doom-loop-config (get-in system [:config :chat :guardrails :doom-loop])
                     :cancellation-token cancelled?
                     :event-sink event-sink
                     :execute-step-fn (fn [executable-step]
                                        (kernel-runtime/execute-step!
                                         ops
                                         (or session-id "chat")
                                         (kernel-schema/normalize-step executable-step)
                                         {:execute-safe-tools? true
                                          :cancellation-token cancelled?
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
        (chat-memory/extract-turn-memory! system
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
  (when-let [message (and session-id (latest-user-message messages))]
    (let [content (llm-messages/content-text message)
          extra (merge (content-blocks-extra message)
                       {:metadata (queued-user-metadata request-id)
                        :excluded-from-context? true
                        :select-leaf? false})]
      (when (or (not (str/blank? content)) (:content-blocks extra))
        (append-message-record! system
                                session-id
                                "user"
                                content
                                extra)))))

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
      (chat-util/emit! system {:event-type :message.updated
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
  (let [service (require-service system)
        {:keys [item cleared?]} (locking (:manager-lock service)
                                  (let [{:keys [active queue]} (get @(:session-runtimes service) session-id)]
                                    (when (= request-id (:request-id active))
                                      (if (seq queue)
                                        (let [item (peek queue)
                                              queue* (pop queue)]
                                          (swap! (:session-runtimes service) assoc session-id
                                                 {:active (active-turn (:request-id item)
                                                                       (:cancelled? item)
                                                                       (get-in item [:opts :stream?]))
                                                  :queue queue*})
                                          {:item item})
                                        (do
                                          (swap! (:session-runtimes service) dissoc session-id)
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
  (let [service (require-service system)]
    (locking (:manager-lock service)
      (if (get-in @(:session-runtimes service) [session-id :active])
        (let [queued-message (persist-queued-user-turn! system session-id (:messages opts) request-id)
              item {:opts opts
                    :request-id request-id
                    :cancelled? cancelled?
                    :queued-message queued-message
                    :result result}]
          (swap! (:session-runtimes service) update session-id enqueue-item item)
          (chat-util/emit! system {:event-type :turn-queued
                         :entity-type :session
                         :entity-id session-id
                         :request-id request-id
                         :payload {:message-id (:id queued-message)
                                   :queued-count (get-in (session-state system session-id)
                                                         [:queued-count])}})
          (emit-session-state! system session-id :queued)
          :queued)
        (do
          (swap! (:session-runtimes service) assoc-in [session-id :active]
                 (active-turn request-id cancelled? stream?))
          (emit-session-state! system session-id :start)
          :active)))))

(defn run!
  "Run or queue a chat turn for `session-id`."
  [system {:keys [session-id] :as opts}]
  (let [prompt (latest-user-prompt (:messages opts))]
    (cond
      (not session-id)
      (run-turn! system opts)

      (and (not (:loop-turn? opts))
           (loop-support/control-command prompt))
      (loop-command! system session-id prompt)

      (and (not (:loop-turn? opts))
           (loop-support/active? session-id))
      (block-while-loop-active! system session-id prompt)

      :else
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
              result)))))))
