(ns agent.chat
  "First-class session chat loop."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.config :as config]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.planner :as planner]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
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
(def tool-output-max-chars 8000)

(defn- request-id []
  (str (UUID/randomUUID)))

(defonce ^:private streaming-state (atom {}))
(defonce ^:private active-runs (atom {}))

(def stopped-content runtime-loop/stopped-content)

(defn active-run
  [session-id]
  (when-let [run (and session-id (get @active-runs session-id))]
    (select-keys run [:request-id :started-at])))

(defn active? [session-id]
  (boolean (active-run session-id)))

(defn cancel-session!
  [session-id]
  (if-let [{:keys [cancelled? request-id]} (and session-id (get @active-runs session-id))]
    (do
      (reset! cancelled? true)
      (swap! active-runs
             (fn [runs]
               (if (= request-id (get-in runs [session-id :request-id]))
                 (dissoc runs session-id)
                 runs)))
      {:cancelled? true
       :session-id session-id
       :request-id request-id})
    {:cancelled? false
     :session-id session-id}))

(defn- register-run! [session-id request-id cancelled?]
  (when session-id
    (swap! active-runs assoc session-id {:request-id request-id
                                         :cancelled? cancelled?
                                         :started-at (str (Instant/now))})))

(defn- unregister-run! [session-id request-id]
  (when session-id
    (swap! active-runs
           (fn [runs]
             (if (= request-id (get-in runs [session-id :request-id]))
               (dissoc runs session-id)
               runs)))))

(defn- active-llm
  [system]
  (config/active-provider-config (get-in system [:config :llm])))

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

(defn- append-message!
  ([system session-id role content]
   (append-message! system session-id role content nil))
  ([system session-id role content extra]
   (let [message (sqlite/append-message! (:store system) session-id role content extra)
         payload (cond-> {:role role :content content}
                   (:tool-calls extra) (assoc :tool-calls (:tool-calls extra))
                   (:tool-call-id extra) (assoc :tool-call-id (:tool-call-id extra)))]
     (emit! system {:event-type :message.appended
                    :entity-type :session
                    :entity-id session-id
                    :payload payload})
     message)))

(defn- latest-user-prompt [messages]
  (:content (last (filter #(= "user" (:role %)) messages))))

(defn- truncate-text [text max-chars]
  (let [text* (or text "")]
    (if (> (count text*) max-chars)
      (str (subs text* 0 max-chars)
           "\n\n[truncated "
           (- (count text*) max-chars)
           " chars]")
      text*)))

(defn- db-message-content->openai [role content]
  (let [content* (or content "")]
    (if (= "tool" role)
      (truncate-text content* history-message-max-chars)
      content*)))

(defn- db-message->openai
  [{:keys [role content tool-calls tool-call-id]}]
  (cond-> {:role role :content (db-message-content->openai role content)}
    (seq tool-calls) (assoc :tool_calls tool-calls)
    tool-call-id (assoc :tool_call_id tool-call-id)))

(defn- session-messages [system session-id]
  (if session-id
    (mapv db-message->openai (sqlite/list-messages (:store system) session-id))
    []))

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

(defn- result-text [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (json/generate-string value)))

(defn- approval-receipts [receipts]
  (filter #(= :approval-required (keyword (:status %))) receipts))

(defn- complete-receipt [receipts]
  (some #(when (= :completed (keyword (:status %))) %) receipts))

(defn- tool-call-id [request-id idx tool-call]
  (or (:id tool-call)
      (:call_id tool-call)
      (str "call_" request-id "_" idx)))

(defn- normalize-tool-call-for-chat [request-id idx tool-call]
  (let [function (or (:function tool-call)
                     (:function_call tool-call)
                     tool-call)
        arguments (or (:arguments function)
                      (:input tool-call)
                      (:args tool-call)
                      {})
        arguments* (cond
                     (string? arguments) arguments
                     (nil? arguments) "{}"
                     :else (json/generate-string arguments))]
    {:id (tool-call-id request-id idx tool-call)
     :type (or (:type tool-call) "function")
     :function {:name (or (:name function)
                          (:tool-name tool-call)
                          (:tool_name tool-call)
                          (:name tool-call))
                :arguments arguments*}}))

(defn- assistant-tool-call-message [request-id content tool-calls]
  {:role "assistant"
   :content (or content "")
   :tool_calls (mapv (fn [[idx tool-call]]
                       (normalize-tool-call-for-chat request-id idx tool-call))
                     (map-indexed vector tool-calls))})

(defn- memory-tool-output-content [receipt]
  (let [status (keyword (:status receipt))]
    (case status
      (:ok :completed) (truncate-text (:result receipt) tool-output-max-chars)
      :denied (str "Memory tool denied: " (:reason receipt))
      :approval-required (str "Memory tool approval required: " (:reason receipt))
      (str "Memory tool failed: " (or (:reason receipt) (:error-type receipt) "unknown error")))))

(defn- tool-output-content [receipt]
  (if (= "memory" (some-> (:tool-name receipt) name))
    (memory-tool-output-content receipt)
    (let [payload (select-keys receipt
                               [:status :tool-name :result :reason :error-type :input])
          text (json/generate-string payload)]
      (if (> (count text) tool-output-max-chars)
        (json/generate-string
         (assoc (select-keys receipt [:status :tool-name :reason :error-type :input])
                :truncated true
                :original-chars (count text)
                :preview (subs text 0 tool-output-max-chars)))
        text))))

(defn- tool-output-message [tool-call receipt]
  {:role "tool"
   :tool_call_id (:id tool-call)
   :content (tool-output-content receipt)})

(defn- tool-protocol-messages [request-id content tool-calls receipts]
  (let [tool-calls* (mapv (fn [[idx tool-call]]
                            (normalize-tool-call-for-chat request-id idx tool-call))
                          (map-indexed vector tool-calls))]
    (into [(assistant-tool-call-message request-id content tool-calls)]
          (map tool-output-message tool-calls* receipts))))

(defn- persist-tool-turn!
  [system session-id assistant-msg tool-msgs]
  (when session-id
    (append-message! system session-id "assistant" (:content assistant-msg)
                     {:tool-calls (:tool_calls assistant-msg)})
    (clear-streaming! session-id)
    (doseq [tm tool-msgs]
      (append-message! system session-id "tool" (:content tm)
                       {:tool-call-id (:tool_call_id tm)}))))

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

(defn- approval-message [approvals]
  (str "Tool approval required: "
       (str/join ", "
                 (map (fn [approval]
                        (str (:tool-name approval) " approval_id=" (:id approval)))
                      approvals))))

(defn- persist-completion! [system session-id prompt content request-id]
  (let [llm (active-llm system)
        assistant-message (when session-id
                            (append-message! system session-id "assistant" content))]
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
    assistant-message))

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

(defn- persist-final-assistant!
  [system session-id prompt content request-id]
  (persist-completion! system session-id prompt content request-id))

(defn- persistence-subscriber
  [system session-id prompt request-id persisted]
  (fn [event]
    (when (same-event-type? event :message-end)
      (let [{:keys [role content final? tool-turn? tool-calls tool-call-id]} (event-payload event)]
        (cond
          (and (= "assistant" role) final?)
          (let [message (persist-final-assistant! system session-id prompt content request-id)]
            (swap! persisted assoc :assistant-message message))

          (and session-id (= "assistant" role) tool-turn?)
          (append-message! system session-id "assistant" content {:tool-calls tool-calls})

          (and session-id (= "tool" role) tool-turn?)
          (append-message! system session-id "tool" content {:tool-call-id tool-call-id}))))))

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
                                                         :model (config/active-model (get-in system [:config :llm]))}))]
      {:content content
       :fallback? true})
    (catch Exception fallback-error
      {:content (error-content fallback-error)
       :fallback? true
       :error? true
       :initial-error (.getMessage error)
       :initial-type (some-> error ex-data :type)})))

(defn run!
  "Run a chat turn for `session-id`. Public wrapper keeps persistence, transport
   callbacks, and legacy events around the evented runtime loop."
  [system {:keys [messages session-id max-steps context on-delta on-tool-call stream?]}]
  (let [max-steps (long (or max-steps
                            (get-in system [:config :chat :max-steps])
                            default-max-steps))
        request-id (request-id)
        cancelled? (atom false)
        prompt (latest-user-prompt messages)
        user-message (persist-user-turn! system session-id messages)
        _ (when session-id
            (try
              (compaction/auto-compact! (:store system) session-id (:chat (:config system)))
              (catch Exception _ nil)))
        history (if session-id (session-messages system session-id) messages)
        recall (recall-memory system session-id prompt)
        iris-context (iris-context-message system)
        stream-content? (and (or stream? on-delta)
                             (not (false? (get-in system [:config :llm :stream-content?] true))))
        persisted (atom {})
        subscribers [(persistence-subscriber system session-id prompt request-id persisted)
                     (streaming-subscriber session-id on-delta)
                     (tool-call-subscriber on-tool-call)
                     (legacy-subscriber system)]
        event-sink (loop-event-sink system subscribers)
        ops (->ChatKernelOps system session-id request-id context)
        context-injectors (cond-> []
                            iris-context (conj (constantly [iris-context]))
                            true (conj (constantly [(memory-message recall)])))]
    (register-run! session-id request-id cancelled?)
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
                     :request-id request-id
                     :session-id session-id
                     :agent-id (or session-id "chat")
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
        (unregister-run! session-id request-id)))))
