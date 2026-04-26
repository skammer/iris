(ns agent.chat
  "First-class session chat loop."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.planner :as planner]
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

(defn- request-id []
  (str (UUID/randomUUID)))

(defn- emit! [system event]
  (if-let [sink (:event-sink system)]
    (sink event)
    (sqlite/log-event! (:store system) event)))

(defn- append-message! [system session-id role content]
  (let [message (sqlite/append-message! (:store system) session-id role content)]
    (emit! system {:event-type :message.appended
                   :entity-type :session
                   :entity-id session-id
                   :payload {:role role
                             :content content}})
    message))

(defn- latest-user-prompt [messages]
  (:content (last (filter #(= "user" (:role %)) messages))))

(defn- session-messages [system session-id]
  (if session-id
    (mapv (fn [{:keys [role content]}]
            {:role role :content content})
          (sqlite/list-messages (:store system) session-id))
    []))

(defn- persist-user-turn! [system session-id messages]
  (when-let [content (and session-id (latest-user-prompt messages))]
    (when-not (str/blank? content)
      (append-message! system session-id "user" content))))

(defn- compact-json [value]
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
   :content (str "Relevant memory JSON: "
                 (compact-json recall))})

(defn- approval-expires-at [system]
  (str (.plusSeconds (Instant/now)
                     (long (get-in system [:config :tools :approvals :ttl-seconds] 900)))))

(defn- profile-permissions [system profile]
  (set (get-in system [:config :tools :permissions profile]
               (get-in system [:config :tools :permissions :agent] #{}))))

(defn- all-tool-names [system]
  (set (map :name (tools/list-tools (:tool-registry system)))))

(defrecord ChatKernelOps [system session-id request-id]
  kernel-ops/KernelOps
  (spawn-task-worker! [_ _]
    (throw (ex-info "Chat loop cannot spawn workers yet"
                    {:type :unsupported-directive})))
  (execute-agent-tool! [_ _ tool-name input context]
    (tools/execute-tool (:tool-registry system)
                        tool-name
                        input
                        (merge context
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

(defn- tool-result-message [receipts]
  {:role "system"
   :content (str "Tool receipts JSON: " (compact-json receipts))})

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
  (let [assistant-message (when session-id
                            (append-message! system session-id "assistant" content))]
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider (get-in system [:config :llm :provider])
                             :model (get-in system [:config :llm :model])
                             :prompt prompt
                             :response content})
    (emit! system {:event-type :completion.completed
                   :entity-type :session
                   :entity-id session-id
                   :request-id request-id
                   :payload {:provider (name (get-in system [:config :llm :provider]))
                             :model (get-in system [:config :llm :model])}})
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
        :model (get-in system [:config :llm :model])})
      (catch Exception e
        (emit! system {:event-type :chat.memory.extract_failed
                       :entity-type :session
                       :entity-id session-id
                       :request-id request-id
                       :payload {:message (.getMessage e)
                                 :type (some-> e ex-data :type)}})))))

(defn- error-content [error]
  (str "Chat failed: " (.getMessage ^Throwable error)))

(defn- fallback-complete! [system messages session-id prompt request-id error]
  (try
    (let [content (telemetry/complete-with-telemetry! (:telemetry system)
                                                      (:llm-provider system)
                                                      messages
                                                      {}
                                                      {:agent-id "chat"
                                                       :model (get-in system [:config :llm :model])})]
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
      (let [content (error-content fallback-error)
            assistant-message (persist-completion! system session-id prompt content request-id)]
        (emit! system {:event-type :chat.failed
                       :entity-type :session
                       :entity-id session-id
                       :request-id request-id
                       :payload {:message (.getMessage fallback-error)
                                 :type (some-> fallback-error ex-data :type)
                                 :initial-error (.getMessage error)}})
        {:content (:content assistant-message)
         :request-id request-id
         :error? true}))))

(defn- stream-delta-text [value]
  (cond
    (string? value) value
    (= :error (:type value)) (throw (ex-info (or (:error value) "LLM stream failed")
                                             {:type :llm-stream-error}))
    (map? value) (or (:content value)
                     (get-in value [:delta :content])
                     (get-in value [:message :content])
                     "")
    (nil? value) ""
    :else (str value)))

(defn stream!
  [system {:keys [messages session-id on-delta]}]
  (let [request-id (request-id)
        prompt (latest-user-prompt messages)
        user-message (persist-user-turn! system session-id messages)
        history (if session-id (session-messages system session-id) messages)
        recall (recall-memory system session-id prompt)
        stream-messages (into [(memory-message recall)] history)
        emit-delta! (or on-delta (constantly nil))]
    (emit! system {:event-type :chat.started
                   :entity-type :session
                   :entity-id session-id
                   :request-id request-id
                   :payload {:message-count (count history)
                             :stream true}})
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
      (let [ch (llm-core/stream (:llm-provider system)
                                stream-messages
                                {:model (get-in system [:config :llm :model])})
            content (loop [acc ""]
                      (if-let [value (async/<!! ch)]
                        (let [delta (stream-delta-text value)]
                          (when-not (str/blank? delta)
                            (emit-delta! delta))
                          (recur (str acc delta)))
                        acc))
            assistant-message (persist-completion! system session-id prompt content request-id)]
        (extract-turn-memory! system session-id user-message assistant-message request-id)
        (emit! system {:event-type :chat.completed
                       :entity-type :session
                       :entity-id session-id
                       :request-id request-id
                       :payload {:stream true}})
        {:content content
         :request-id request-id
         :stream? true})
      (catch Exception e
        (let [content (error-content e)
              assistant-message (persist-completion! system session-id prompt content request-id)]
          (emit! system {:event-type :chat.failed
                         :entity-type :session
                         :entity-id session-id
                         :request-id request-id
                         :payload {:message (.getMessage e)
                                   :type (some-> e ex-data :type)
                                   :stream true}})
          {:content (:content assistant-message)
           :request-id request-id
           :error? true})))))

(defn run!
  [system {:keys [messages session-id max-steps]
           :or {max-steps default-max-steps}}]
  (let [request-id (request-id)
        prompt (latest-user-prompt messages)
        user-message (persist-user-turn! system session-id messages)
        history (if session-id (session-messages system session-id) messages)
        recall (recall-memory system session-id prompt)
        initial-messages (into [(memory-message recall)] history)
        ops (->ChatKernelOps system session-id request-id)
        finish! (fn [content trace extra]
                  (let [assistant-message (persist-completion! system session-id prompt content request-id)]
                    (extract-turn-memory! system session-id user-message assistant-message request-id))
                  (merge {:content content
                          :request-id request-id
                          :trace trace}
                         extra))]
    (emit! system {:event-type :chat.started
                   :entity-type :session
                   :entity-id session-id
                   :request-id request-id
                   :payload {:message-count (count history)}})
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
        (if (>= step-no max-steps)
          (finish! "Stopped: max chat tool steps reached." trace {})
          (let [step (planner/plan-step! (:llm-provider system)
                                         {:messages planner-messages
                                          :state state
                                          :tools (tools/list-tools (:tool-registry system))
                                          :telemetry (:telemetry system)
                                          :agent-id (or session-id "chat")
                                          :model (get-in system [:config :llm :model])})
                executable-step (select-keys step [:schema-version :state :directives :receipts])
                executed (kernel-runtime/execute-step!
                          ops
                          (or session-id "chat")
                          executable-step
                          {:execute-safe-tools? true
                           :yolo? (true? (get-in system [:config :tools :yolo?]))})
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
            (if-let [receipt (complete-receipt receipts)]
              (let [content (result-text (:result receipt))]
                (emit! system {:event-type :chat.completed
                               :entity-type :session
                               :entity-id session-id
                               :request-id request-id
                               :payload {:steps (inc step-no)}})
                (finish! content trace* {}))
              (let [approval-needed (vec (approval-receipts receipts))]
                (if (seq approval-needed)
                  (let [approvals (mapv #(request-approval! system session-id %) approval-needed)
                        content (approval-message approvals)]
                    (finish! content trace* {:approvals approvals}))
                  (recur (inc step-no)
                         (merge state (:state executed))
                         (conj planner-messages (tool-result-message receipts))
                         trace*)))))))
      (catch Exception e
        (emit! system {:event-type :chat.error
                       :entity-type :session
                       :entity-id session-id
                       :request-id request-id
                       :payload {:message (.getMessage e)
                                 :type (some-> e ex-data :type)}})
        (fallback-complete! system initial-messages session-id prompt request-id e)))))
