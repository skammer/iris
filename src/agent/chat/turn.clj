(ns agent.chat.turn
  "Single chat turn execution."
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
   [agent.llm.messages :as llm-messages]
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
   [agent.util :as util]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

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
  (let [tool-name (keyword (:tool-name receipt))]
    (tool-approvals/create-request!
     (:store system)
     {:tool-name tool-name
      :input (:input receipt)
      :requested-by (or session-id "chat")
      :reason "chat tool call"
      :expires-at (approval-expires-at system)})))

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
                                                         :session-id session-id
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

(defn run-turn!
  "Run a chat turn for `session-id`. Public wrapper keeps persistence, transport
   callbacks, and runtime events around the evented runtime loop."
  [system {:keys [messages session-id max-steps context on-delta on-tool-call stream?
                  cancellation-token persist-user? user-message]
           :or {persist-user? true}
           :as opts}]
  (let [max-steps (long (or max-steps
                            (get-in system [:config :chat :max-steps])
                            defaults/chat-max-steps))
        request-id (or (:request-id opts) (service/request-id))
        cancelled? (or cancellation-token (atom false))
        prompt (history/latest-user-prompt messages)
        user-message (if persist-user?
                       (history/persist-user-turn! system session-id messages)
                       user-message)
        _ (when session-id
            (try
              (compaction/auto-compact! (:store system) session-id (:chat (:config system)))
              (catch Exception e
                (chat-util/emit-operation-failed! system session-id request-id :auto-compact e))))
        history (if session-id
                  (history/session-messages system session-id)
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
                      :f (subscribers/persistence-subscriber system session-id prompt request-id persisted)}
                     {:operation :streaming-callback
                      :f (subscribers/streaming-subscriber system session-id on-delta)}
                     {:operation :tool-call-callback
                      :f (subscribers/tool-call-subscriber on-tool-call)}]
        event-sink* (subscribers/loop-event-sink system subscribers)
        flusher (chat-streaming/stream-delta-flusher
                 event-sink*
                 (get-in system [:chat-service :stream-flush-scheduler]))
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
                 :timestamp (util/now-str)
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
                                          :max-parallelism (get-in system [:config :tools :max-parallelism])
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
