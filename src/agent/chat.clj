(ns agent.chat
  "First-class session chat loop."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.broker.core :as broker]
   [agent.config :as config]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.planner :as planner]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
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

(def stopped-content "Stopped.")

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

(defn- token-cancelled? [cancelled?]
  (true? @cancelled?))

(defn- cancelled-error []
  (ex-info "Chat stopped" {:type :chat-cancelled}))

(defn- throw-if-cancelled! [cancelled?]
  (when (token-cancelled? cancelled?)
    (throw (cancelled-error))))

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

(defn- broadcast-delta! [system session-id request-id delta]
  (when-let [bus (or (:event-bus system) (:broker system))]
    (let [event {:event-type "chat.delta"
                 :entity-type "session"
                 :entity-id session-id
                 :request-id request-id
                 :payload {:delta delta}}]
      (doseq [msg (broker/event->messages event)]
        (broker/publish! bus msg)))))

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

(defn- emit-delta!
  [system session-id request-id on-delta delta]
  (when (and (string? delta) (not= "" delta))
    (when session-id
      (swap! streaming-state update session-id (fnil str "") delta))
    (broadcast-delta! system session-id request-id delta)
    (when on-delta (on-delta delta))))

(defn- consume-llm-stream!
  "Drains an LLM stream channel, dispatching deltas. Returns accumulated text."
  [ch system session-id request-id on-delta]
  (loop [acc ""]
    (if-let [value (async/<!! ch)]
      (let [delta (stream-delta-text value)]
        (emit-delta! system session-id request-id on-delta delta)
        (recur (str acc delta)))
      acc)))

(defn- stream-llm-response!
  "Issues a plain streaming LLM call and dispatches deltas. Returns content string."
  [system session-id request-id messages on-delta]
  (let [ch (llm-core/stream (:llm-provider system)
                            messages
                            {:model (config/active-model (get-in system [:config :llm]))})]
    (consume-llm-stream! ch system session-id request-id on-delta)))

(defn- emit-content-as-delta!
  "Streams a pre-known content string through on-delta as a single delta. For
   synthesized terminal text (max-steps, approval-required) so consumers see a
   final chunk."
  [system session-id request-id on-delta content]
  (when (and on-delta (not (str/blank? content)))
    (emit-delta! system session-id request-id on-delta content)))

(defn- fallback-complete!
  [system messages session-id prompt request-id error on-delta]
  (try
    (let [content (if on-delta
                    (stream-llm-response! system session-id request-id messages on-delta)
                    (telemetry/complete-with-telemetry! (:telemetry system)
                                                        (:llm-provider system)
                                                        messages
                                                        {}
                                                        {:agent-id "chat"
                                                         :model (config/active-model (get-in system [:config :llm]))}))]
      (clear-streaming! session-id)
      (emit! system {:event-type :chat.fallback_completion
                     :entity-type :session
                     :entity-id session-id
                     :request-id request-id
                     :payload {:reason (.getMessage error)}})
      (let [assistant-message (persist-completion! system session-id prompt content request-id)]
        (extract-turn-memory! system session-id nil assistant-message request-id))
      (emit! system {:event-type :chat.completed
                     :entity-type :session
                     :entity-id session-id
                     :request-id request-id
                     :payload {:fallback true}})
      {:content content
       :request-id request-id
       :fallback? true})
    (catch Exception fallback-error
      (clear-streaming! session-id)
      (let [content (error-content fallback-error)
            assistant-message (persist-completion! system session-id prompt content request-id)]
            (emit! system {:event-type :chat.failed
                           :entity-type :session
                           :entity-id session-id
                           :request-id request-id
                           :payload (assoc (error-payload fallback-error)
                                           :initial-error (.getMessage error)
                                           :initial-type (some-> error ex-data :type))})
        {:content (:content assistant-message)
         :request-id request-id
         :error? true}))))

(defn run!
  "Run a chat turn for `session-id`. With `:on-delta`, the user-visible response
   streams token-by-token through that callback. With `:on-tool-call`, channels
   that surface tool activity inline (e.g. Telegram) receive one call per tool
   turn with `{:tool-call ... :receipt ...}`. The agentic planner loop runs the
   same way regardless of either callback."
  [system {:keys [messages session-id max-steps context on-delta on-tool-call]}]
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
        initial-messages (into (cond-> []
                                 iris-context (conj iris-context)
                                 true (conj (memory-message recall)))
                               history)
        ops (->ChatKernelOps system session-id request-id context)
        stream-content? (and on-delta
                             (not (false? (get-in system [:config :llm :stream-content?] true))))
        on-content-delta (when stream-content?
                           (fn [chunk]
                             (throw-if-cancelled! cancelled?)
                             (emit-delta! system session-id request-id on-delta chunk)))
        finish! (fn [content trace extra]
                  (clear-streaming! session-id)
                  (let [assistant-message (persist-completion! system session-id prompt content request-id)]
                    (extract-turn-memory! system session-id user-message assistant-message request-id))
                  (merge {:content content
                          :request-id request-id
                          :trace trace
                          :stream? (some? on-delta)}
                         extra))]
    (register-run! session-id request-id cancelled?)
    (emit! system {:event-type :chat.started
                   :entity-type :session
                   :entity-id session-id
                   :request-id request-id
                   :payload {:message-count (count history)
                             :stream (some? on-delta)}})
    (emit! system {:event-type :chat.memory.recalled
                   :entity-type :session
                   :entity-id session-id
                   :request-id request-id
                   :payload {:query prompt
                             :message-count (count (get-in recall [:search :messages]))
                             :event-count (count (get-in recall [:search :events]))
                             :fact-count (count (get-in recall [:search :facts]))
                             :prompt-document-count (count (get-in recall [:prompt :documents]))}})
    (try
      (loop [step-no 0
             state {}
             planner-messages initial-messages
             trace []]
        (throw-if-cancelled! cancelled?)
        (if (>= step-no max-steps)
          (let [content "Stopped: max chat tool steps reached."]
            (emit-content-as-delta! system session-id request-id on-delta content)
            (finish! content trace {}))
          (let [step (planner/plan-step! (:llm-provider system)
                                         {:messages planner-messages
                                          :state state
                                          :tools (tools/list-tools (:tool-registry system))
                                          :telemetry (:telemetry system)
                                          :agent-id (or session-id "chat")
                                          :model (config/active-model (get-in system [:config :llm]))
                                          :on-content-delta on-content-delta})
                _ (throw-if-cancelled! cancelled?)
                executable-step (select-keys step [:schema-version :state :directives :receipts])
                executed (kernel-runtime/execute-step!
                          ops
                          (or session-id "chat")
                          executable-step
                          {:execute-safe-tools? true
                           :yolo? (true? (get-in system [:config :tools :yolo?]))})
                _ (throw-if-cancelled! cancelled?)
                receipts (:receipts executed)
                trace* (conj trace {:step step-no
                                    :directives (:directives step)
                                    :receipts receipts})]
            (emit! system {:event-type :chat.planner.step
                           :entity-type :session
                           :entity-id session-id
                           :request-id request-id
                           :payload {:step step-no
                                     :directives (:directives step)
                                     :receipts receipts}})
            (let [llm-response (:llm-response step)
                  provider-tool-calls (seq (:tool-calls llm-response))
                  protocol-messages (when provider-tool-calls
                                      (tool-protocol-messages request-id
                                                               (:content llm-response)
                                                               provider-tool-calls
                                                               receipts))]
              (when protocol-messages
                (persist-tool-turn! system session-id
                                    (first protocol-messages)
                                    (rest protocol-messages))
                (when on-tool-call
                  (doseq [[tool-call receipt] (map vector provider-tool-calls receipts)]
                    (try
                      (on-tool-call {:tool-call tool-call :receipt receipt})
                      (catch Exception _ nil)))))
              (if-let [receipt (complete-receipt receipts)]
                (let [content (result-text (:result receipt))]
                  (when-not stream-content?
                    (emit-content-as-delta! system session-id request-id on-delta content))
                  (emit! system {:event-type :chat.completed
                                 :entity-type :session
                                 :entity-id session-id
                                 :request-id request-id
                                 :payload {:steps (inc step-no)
                                           :stream (some? on-delta)}})
                  (finish! content trace* {}))
                (let [approval-needed (vec (approval-receipts receipts))]
                  (if (seq approval-needed)
                    (let [approvals (mapv #(request-approval! system session-id %) approval-needed)
                          content (approval-message approvals)]
                      (emit-content-as-delta! system session-id request-id on-delta content)
                      (finish! content trace* {:approvals approvals}))
                    (recur (inc step-no)
                           (merge state (:state executed))
                           (into planner-messages protocol-messages)
                           trace*))))))))
      (catch Exception e
        (if (or (token-cancelled? cancelled?)
                (= :chat-cancelled (some-> e ex-data :type)))
          (do
            (clear-streaming! session-id)
            (emit-content-as-delta! system session-id request-id on-delta stopped-content)
            (persist-completion! system session-id prompt stopped-content request-id)
            (emit! system {:event-type :chat.cancelled
                           :entity-type :session
                           :entity-id session-id
                           :request-id request-id
                           :payload {:message (.getMessage e)}})
            {:content stopped-content
             :request-id request-id
             :trace []
             :stream? (some? on-delta)
             :cancelled? true})
          (do
            (emit! system {:event-type :chat.error
                           :entity-type :session
                           :entity-id session-id
                           :request-id request-id
                           :payload (error-payload e)})
            (fallback-complete! system initial-messages session-id prompt request-id e on-delta))))
      (finally
        (unregister-run! session-id request-id)))))
