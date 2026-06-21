(ns agent.chat.turn
  "Single chat turn orchestration. Prepares history, memory, model/profile, and
   kernel ops; wires runtime events to persistence/streaming subscribers; then
   runs agent.runtime.loop for one user turn."
  (:require
   [agent.chat.history :as history]
   [agent.chat.kernel-ops :as chat-kernel-ops]
   [agent.chat.memory :as chat-memory]
   [agent.chat.service :as service]
   [agent.chat.streaming :as chat-streaming]
   [agent.chat.subscribers :as subscribers]
   [agent.chat.util :as chat-util]
   [agent.config :as config]
   [agent.defaults :as defaults]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm-core]
   [agent.llm.instrumented :as llm-instrumented]
   [agent.llm.messages :as llm-messages]
   [agent.persistence.sqlite :as sqlite]
   [agent.planner :as planner]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
   [agent.runtime.context-pack :as context-pack]
   [agent.runtime.loop :as runtime-loop]
   [agent.skills :as skills]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.util :as util]
   [clojure.core.async :as async]
   [clojure.string :as str]))

(defn- iris-context-message [system]
  (when-let [context (some-> (get-in system [:config :iris :context]) str/trim not-empty)]
    {:role "system" :content context}))

(defn- skill-context-message [system prompt]
  (when-let [registry (:skills-registry system)]
    (when-let [section (some-> (skills/invoked-skills-section registry prompt)
                               str/trim
                               not-empty)]
      {:role "system" :content section})))

(defn- approval-reason [tool-name input]
  (or (some-> (or (:purpose input) (get input "purpose") (:reason input) (get input "reason"))
              str
              str/trim
              not-empty)
      (str "Agent requested " (name tool-name))))

(defn- request-approval! [system session-id receipt]
  (let [tool-name (keyword (:tool-name receipt))
        tool (tools/get-tool (:tool-registry system) tool-name)
        tool-description (when tool (tools/describe tool))]
    (if-let [approval-id (:approval-id receipt)]
      (tool-approvals/get-request (:store system) approval-id)
      (tool-approvals/request-with-magi!
       (:store system)
       {:magi-service (:magi-service system)
        :event-sink (:event-sink system)}
       {:tool-name tool-name
        :input (:input receipt)
        :requested-by (or session-id "chat")
        :reason (approval-reason tool-name (:input receipt))
        :expires-at (tool-approvals/default-expires-at system)}
       tool-description
       {:user (or session-id "chat")
        :request-id (:request-id receipt)}))))

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

(defn- consume-llm-stream-with!
  [ch emit-delta]
  (loop [acc ""]
    (if-let [value (async/<!! ch)]
      (let [delta (stream-delta-text value)]
        (emit-delta delta)
        (recur (str acc delta)))
      acc)))

(defn- fallback-content!
  [system model messages session-id request-id error stream? emit-delta]
  (try
    (let [content (if stream?
                    (let [ch (llm-core/stream (:llm-provider system)
                                              messages
                                              {:model model
                                               :session-id session-id})]
                      (consume-llm-stream-with! ch emit-delta))
                    (llm-instrumented/complete-with-telemetry! (:telemetry system)
                                                               (:llm-provider system)
                                                               messages
                                                               {}
                                                               {:agent-id (or session-id "chat")
                                                                :session-id session-id
                                                                :observer (:observer system)
                                                                :trace (:trace system)
                                                                :request-id request-id
                                                                :model model}))]
      {:content content
       :fallback? true})
    (catch Exception fallback-error
      {:content (error-content fallback-error)
       :fallback? true
       :error? true
       :initial-error (.getMessage error)
       :initial-type (some-> error ex-data :type)})))

(defn- summarize-context!
  [system model compaction-config prompt session-id]
  (let [response (llm-core/invoke
                  (:llm-provider system)
                  {:model model
                   :session-id session-id
                   :messages [{:role "system"
                               :content "Summarize compacted conversation context for the next LLM call."}
                              {:role "user"
                               :content prompt}]
                   :max-tokens (get compaction-config :summary-max-tokens 512)
                   :metadata {:context-pack-summary true}})]
    (if (map? response)
      (or (:content response)
          (llm-messages/content-text response)
          "")
      (str response))))

(defn- context-pack-fn
  [system {:keys [model compaction-config]}]
  (fn [ctx]
    (context-pack/pack-context
     (assoc ctx
            :config compaction-config
            :summarizer-fn (fn [{:keys [prompt]}]
                             (summarize-context! system
                                                 model
                                                 compaction-config
                                                 prompt
                                                 (:session-id ctx)))))))

(defn- auto-compact! [system session-id request-id chat-config]
  (when session-id
    (try
      (compaction/auto-compact! (:store system) session-id chat-config)
      (catch Exception e
        (chat-util/emit-operation-failed! system session-id request-id :auto-compact e)))))

(defn- context-injectors [system session-id prompt recall]
  (let [iris-context (iris-context-message system)
        active-mode (some-> (and session-id
                                 (sqlite/get-session (:store system) session-id))
                            :active-mode)
        mode-messages (prompts/apply-mode [] active-mode)
        skill-message (skill-context-message system prompt)]
    (cond-> []
      iris-context (conj (constantly [iris-context]))
      (seq mode-messages) (conj (constantly mode-messages))
      skill-message (conj (constantly [skill-message]))
      true (conj (constantly [(chat-memory/memory-message recall)])))))

(defn- prepare-turn-env
  "Gathers and derives every input the runtime loop needs for one chat turn:
   request identity, the persisted user turn, auto-compacted history, memory
   recall, context injectors, and the config-derived loop settings. Touches
   persistence (user turn, compaction, history reads) but installs no event
   subscriptions; that wiring stays in run-turn!."
  [system {:keys [messages session-id max-steps stream? on-delta on-thinking-delta
                  cancellation-token persist-user? user-message]
           :or {persist-user? true}
           :as opts}]
  (let [config (:config system)
        chat-config (:chat config)
        llm-config (:llm config)
        tools-config (:tools config)
        request-id (or (:request-id opts) (service/request-id))
        prompt (history/latest-user-prompt messages)
        user-message (if persist-user?
                       (history/persist-user-turn! system session-id messages request-id)
                       user-message)
        _ (auto-compact! system session-id request-id chat-config)
        history (if session-id
                  (history/session-messages system session-id)
                  (llm-messages/messages->internal messages))
        recall (chat-memory/recall-memory system session-id prompt request-id)]
    {:session-id session-id
     :request-id request-id
     :cancelled? (or cancellation-token (atom false))
     :prompt prompt
     :user-message user-message
     :history history
     :recall recall
     :context-injectors (context-injectors system session-id prompt recall)
     :model (config/active-model llm-config)
     :chat-profile (config/chat-profile config)
     :compaction-config (:compaction chat-config)
     :max-steps (long (or max-steps
                          (:max-steps chat-config)
                          defaults/chat-max-steps))
     :stream-content? (and (or stream? on-delta on-thinking-delta)
                           (not (false? (get llm-config :stream-content? true))))
     :doom-loop-config (get-in chat-config [:guardrails :doom-loop])
     :max-parallelism (:max-parallelism tools-config)
     :yolo? (true? (:yolo? tools-config))}))

(defn- memory-recalled-event [{:keys [session-id request-id prompt recall]}]
  {:event-type :message-update
   :entity-type :session
   :entity-id session-id
   :request-id request-id
   :timestamp (util/now-str)
   :payload {:kind :memory-recalled
             :query prompt
             :result-count (count (:results recall))
             :surface-counts (:surface-counts recall)
             :latency-ms (:latency-ms recall)
             :why (mapv #(select-keys % [:surface :id :reason :score :why])
                         (:results recall))}})

(def ^:private magi-context-message-limit 8)
(def ^:private magi-context-content-chars 1200)

(defn- magi-message [message]
  (let [role (some-> (:role message) name)
        content (some-> (llm-messages/content-text message) str/trim not-empty)]
    (when (and (#{"user" "assistant" "tool"} role) content)
      {:role role
       :content (util/truncate content
                               magi-context-content-chars
                               #(str " [truncated " % " chars]"))})))

(defn- magi-context [{:keys [session-id request-id prompt history]}]
  (cond-> {:session-id session-id
           :request-id request-id}
    (some-> prompt str/trim not-empty)
    (assoc :user-request (str/trim prompt))

    true
    (assoc :recent-messages (->> history
                                 (keep magi-message)
                                 (take-last magi-context-message-limit)
                                 vec))))

(defn- runtime-loop-options
  "Builds the agent.runtime.loop/run! options map from a prepared turn env plus
   the live wiring (event sink, kernel ops, thinking callback) created by
   run-turn!."
  [system
   {:keys [session-id request-id cancelled? history context-injectors model
           chat-profile max-steps stream-content? doom-loop-config
           max-parallelism yolo?]
    :as env}
   {:keys [event-sink ops on-thinking-delta]}]
  {:messages history
   :context-injectors context-injectors
   :system-prompt (planner/planner-system-prompt)
   :tools (tools/list-tools (:tool-registry system))
   :model model
   :provider-config (:llm-provider system)
   :chat-profile chat-profile
   :telemetry (:telemetry system)
   :observer (:observer system)
   :trace (:trace system)
   :request-id request-id
   :session-id session-id
   :agent-id (or session-id "chat")
   :context-pack-fn (context-pack-fn system env)
   :max-steps max-steps
   :stream? stream-content?
   :doom-loop-config doom-loop-config
   :cancellation-token cancelled?
   :event-sink event-sink
   :on-thinking-delta on-thinking-delta
   :execute-step-fn (fn [executable-step]
                      (kernel-runtime/execute-step!
                       ops
                       (or session-id "chat")
                       (kernel-schema/normalize-step executable-step)
                       {:execute-safe-tools? true
                        :cancellation-token cancelled?
                        :event-sink event-sink
                        :max-parallelism max-parallelism
                        :yolo? yolo?}))
   :approval-fn (fn [receipts]
                  (mapv #(request-approval! system session-id %) receipts))
   :fallback-fn (fn [{:keys [messages error stream? emit-delta]}]
                  (fallback-content! system
                                     model
                                     messages
                                     session-id
                                     request-id
                                     error
                                     stream?
                                     emit-delta))})

(defn run-turn!
  "Run a chat turn for `session-id`. Public wrapper keeps persistence, transport
   callbacks, and runtime events around the evented runtime loop:
   prepare inputs -> subscribe -> run loop -> finalize."
  [system {:keys [session-id on-delta on-thinking-delta on-tool-call stream? context] :as opts}]
  (let [{:keys [request-id prompt] :as env} (prepare-turn-env system opts)
        persisted (atom {})
        subscribers [{:operation :persistence
                      :f (subscribers/persistence-subscriber system session-id prompt request-id persisted)}
                     {:operation :streaming-callback
                      :f (subscribers/streaming-subscriber system session-id on-delta)}
                     {:operation :tool-call-callback
                      :f (subscribers/tool-call-subscriber on-tool-call)}]
        flusher (chat-streaming/stream-delta-flusher
                 (subscribers/loop-event-sink system subscribers)
                 (get-in system [:chat-service :stream-flush-scheduler]))
        event-sink (:emit! flusher)
        tool-context (assoc (or context {}) :magi-context (magi-context env))
        ops (chat-kernel-ops/->ChatKernelOps system session-id request-id tool-context)]
    (event-sink (memory-recalled-event env))
    (try
      (let [result (runtime-loop/run! (runtime-loop-options system
                                                            env
                                                            {:event-sink event-sink
                                                             :ops ops
                                                             :on-thinking-delta on-thinking-delta}))]
        (assoc result :stream? (boolean (or stream? on-delta))))
      (finally
        ((:flush! flusher))))))
