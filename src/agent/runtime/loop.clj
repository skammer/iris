(ns agent.runtime.loop
  "Evented chat-agent loop. No persistence or transport concerns live here."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.llm.messages :as llm-messages]
   [agent.planner :as planner]
   [agent.runtime.schema :as runtime-schema]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def stopped-content "Stopped.")
(def max-steps-content "Stopped: max chat tool steps reached.")

(defn- now-str [] (str (Instant/now)))

(defn- event!
  [sink event-type base payload]
  (let [event (runtime-schema/validate-runtime-event!
               (merge base
                      {:event-type event-type
                       :timestamp (now-str)}
                      (when (some? payload)
                        {:payload payload})))]
    (when sink
      (sink event))
    event))

(defn- cancelled-error []
  (ex-info "Chat stopped" {:type :chat-cancelled}))

(defn cancelled? [token]
  (cond
    (nil? token) false
    (instance? clojure.lang.IDeref token) (true? @token)
    (fn? token) (true? (token))
    :else (true? token)))

(defn throw-if-cancelled! [token]
  (when (cancelled? token)
    (throw (cancelled-error))))

(defn- result-text [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (json/generate-string value)))

(defn- approval-receipts [receipts]
  (filter #(= :approval-required (keyword (:status %))) receipts))

(defn- complete-receipt [receipts]
  (some #(when (= :completed (keyword (:status %))) %) receipts))

(defn- normalize-tool-call-block [request-id idx tool-call]
  (let [block (llm-messages/provider-tool-call->internal tool-call)]
    (cond-> block
      (not (:id block)) (assoc :id (str "call_" request-id "_" idx)))))

(defn- assistant-tool-call-message [request-id content tool-calls]
  (let [tool-blocks (mapv (fn [[idx tool-call]]
                            (normalize-tool-call-block request-id idx tool-call))
                          (map-indexed vector tool-calls))
        text-blocks (if (str/blank? (or content ""))
                      []
                      [{:type :text :text content}])]
    {:role "assistant"
     :content (vec (concat text-blocks tool-blocks))}))

(defn- truncate-text [text max-chars]
  (let [text* (or text "")]
    (if (> (count text*) max-chars)
      (str (subs text* 0 max-chars)
           "\n\n[truncated "
           (- (count text*) max-chars)
           " chars]")
      text*)))

(defn- memory-tool-output-content [receipt tool-output-max-chars]
  (let [status (keyword (:status receipt))]
    (case status
      (:ok :completed) (truncate-text (:result receipt) tool-output-max-chars)
      :denied (str "Memory tool denied: " (:reason receipt))
      :approval-required (str "Memory tool approval required: " (:reason receipt))
      (str "Memory tool failed: " (or (:reason receipt) (:error-type receipt) "unknown error")))))

(defn tool-output-content
  ([receipt] (tool-output-content receipt 8000))
  ([receipt tool-output-max-chars]
   (if (= "memory" (some-> (:tool-name receipt) name))
     (memory-tool-output-content receipt tool-output-max-chars)
     (let [payload (select-keys receipt
                                [:status :tool-name :result :reason :error-type :input])
           text (json/generate-string payload)]
       (if (> (count text) tool-output-max-chars)
         (json/generate-string
          (assoc (select-keys receipt [:status :tool-name :reason :error-type :input])
                 :truncated true
                 :original-chars (count text)
                 :preview (subs text 0 tool-output-max-chars)))
         text)))))

(defn- tool-output-message [tool-call receipt tool-output-max-chars]
  {:role "tool"
   :content [{:type :tool-result
              :tool-call-id (:id tool-call)
              :name (:name tool-call)
              :status (:status receipt)
              :content (tool-output-content receipt tool-output-max-chars)}]})

(defn tool-protocol-messages
  ([request-id content tool-calls receipts]
   (tool-protocol-messages request-id content tool-calls receipts 8000))
  ([request-id content tool-calls receipts tool-output-max-chars]
   (let [tool-calls* (mapv (fn [[idx tool-call]]
                             (normalize-tool-call-block request-id idx tool-call))
                           (map-indexed vector tool-calls))]
     (into [(assistant-tool-call-message request-id content tool-calls)]
           (map #(tool-output-message %1 %2 tool-output-max-chars)
                tool-calls*
                receipts)))))

(defn- tool-result-block [message]
  (first (filter #(= :tool-result (:type %))
                 (runtime-schema/normalize-content (:content message)))))

(defn- approval-message [approvals]
  (str "Tool approval required: "
       (str/join ", "
                 (map (fn [approval]
                        (str (:tool-name approval) " approval_id=" (:id approval)))
                      approvals))))

(defn- apply-context-injectors [messages injectors]
  (llm-messages/messages->internal
   (concat (mapcat (fn [injector]
                     (vec (or (injector {:messages messages}) [])))
                   injectors)
           messages)))

(defn- usage+ [a b]
  (merge-with (fn [x y]
                (if (and (number? x) (number? y))
                  (+ x y)
                  y))
              (or a {})
              (or b {})))

(defn- emit-message-delta! [sink base delta]
  (when (and (string? delta) (not= "" delta))
    (event! sink
            :message-update
            base
            {:role "assistant"
             :delta delta
             :append? true})))

(defn- emit-terminal-message! [sink base content final-payload]
  (when-not (str/blank? (or content ""))
    (event! sink :message-update base {:role "assistant"
                                       :delta content
                                       :append? true
                                       :synthetic? true}))
  (event! sink :message-end base (merge {:role "assistant"
                                         :content content
                                         :content-blocks [{:type :text :text (or content "")}]
                                         :final? true}
                                        final-payload)))

(defn- emit-tool-turn! [sink base request-id llm-response tool-calls receipts tool-output-max-chars]
  (let [protocol-messages (tool-protocol-messages request-id
                                                  (:content llm-response)
                                                  tool-calls
                                                  receipts
                                                  tool-output-max-chars)
        assistant-msg (first protocol-messages)
        tool-msgs (vec (rest protocol-messages))]
    (event! sink :message-end base {:role "assistant"
                                    :content (llm-messages/content-text assistant-msg)
                                    :content-blocks (:content assistant-msg)
                                    :tool-calls (llm-messages/message-tool-calls assistant-msg)
                                    :tool-turn? true})
    (doseq [tool-msg tool-msgs]
      (let [tool-result (tool-result-block tool-msg)]
        (event! sink :message-end base {:role "tool"
                                        :content (:content tool-result)
                                        :content-blocks (:content tool-msg)
                                        :tool-call-id (:tool-call-id tool-result)
                                        :tool-turn? true})))
    (doseq [[tool-call receipt] (map vector tool-calls receipts)]
      (event! sink :tool-execution-end base {:tool-call tool-call
                                             :receipt receipt
                                             :tool-name (some-> (:tool-name receipt) name)
                                             :status (some-> (:status receipt) name)}))
    protocol-messages))

(defn run!
  [{:keys [messages context-injectors system-prompt tools model provider-config
           telemetry planner-fn execute-step-fn approval-fn fallback-fn event-sink
           cancellation-token request-id session-id agent-id max-steps stream?
           tool-output-max-chars]
    :or {planner-fn planner/plan-step!
         max-steps 6
         tool-output-max-chars 8000}}]
  (let [base {:entity-type :session
              :entity-id session-id
              :request-id request-id}
        agent-id* (or agent-id session-id "chat")
        messages* (apply-context-injectors (vec (or messages [])) context-injectors)
        stream?* (true? stream?)
        delta-emitted? (atom false)
        emit-delta! (fn [chunk]
                      (when (and (string? chunk) (not= "" chunk))
                        (reset! delta-emitted? true)
                        (emit-message-delta! event-sink base chunk)))
        on-content-delta (when stream?*
                           (fn [chunk]
                             (throw-if-cancelled! cancellation-token)
                             (emit-delta! chunk)))]
    (event! event-sink :agent-start base {:message-count (count messages*)
                                          :stream stream?*})
    (try
      (loop [step-no 0
             state {}
             planner-messages messages*
             trace []
             final-messages []
             usage {}]
        (throw-if-cancelled! cancellation-token)
        (if (>= step-no max-steps)
          (do
            (emit-terminal-message! event-sink base max-steps-content {:stop-reason :max-steps})
            (event! event-sink :agent-end base {:steps step-no
                                                :stop-reason :max-steps
                                                :stream stream?*})
            {:content max-steps-content
             :request-id request-id
             :final-messages (conj final-messages {:role "assistant"
                                                   :content max-steps-content})
             :trace trace
             :usage usage
             :stop-reason :max-steps
             :stream? stream?*})
          (do
            (event! event-sink :turn-start base {:step step-no})
            (reset! delta-emitted? false)
            (event! event-sink :message-start base {:role "assistant"
                                                    :step step-no})
            (let [step (planner-fn provider-config
                                   {:messages planner-messages
                                    :state state
                                    :tools tools
                                    :telemetry telemetry
                                    :agent-id agent-id*
                                    :model model
                                    :system-prompt system-prompt
                                    :on-content-delta on-content-delta})
                  _ (throw-if-cancelled! cancellation-token)
                  executable-step (select-keys step [:schema-version :state :directives :receipts])
                  executed (execute-step-fn executable-step)
                  _ (throw-if-cancelled! cancellation-token)
                  receipts (:receipts executed)
                  trace-entry {:step step-no
                               :directives (:directives step)
                               :receipts receipts}
                  trace* (conj trace trace-entry)
                  llm-response (:llm-response step)
                  usage* (usage+ usage (:usage llm-response))]
              (event! event-sink :turn-end base {:step step-no
                                                 :directives (:directives step)
                                                 :receipts receipts})
              (let [provider-tool-calls (seq (:tool-calls llm-response))
                    protocol-messages (when provider-tool-calls
                                        (emit-tool-turn! event-sink
                                                         base
                                                         request-id
                                                         llm-response
                                                         provider-tool-calls
                                                         receipts
                                                         tool-output-max-chars))
                    final-messages* (into final-messages protocol-messages)]
                (if-let [receipt (complete-receipt receipts)]
                  (let [content (result-text (:result receipt))]
                    (when-not @delta-emitted?
                      (emit-delta! content))
                    (event! event-sink :message-end base {:role "assistant"
                                                          :content content
                                                          :final? true
                                                          :stop-reason :completed})
                    (event! event-sink :agent-end base {:steps (inc step-no)
                                                        :stop-reason :completed
                                                        :stream stream?*})
                    {:content content
                     :request-id request-id
                     :final-messages (conj final-messages* {:role "assistant"
                                                            :content content})
                     :trace trace*
                     :usage usage*
                     :stop-reason :completed
                     :stream? stream?*})
                  (let [approval-needed (vec (approval-receipts receipts))]
                    (if (seq approval-needed)
                      (let [approvals (if approval-fn
                                        (approval-fn approval-needed)
                                        approval-needed)
                            content (approval-message approvals)]
                        (event! event-sink :tool-execution-update base {:kind :approval-required
                                                                        :approvals approvals
                                                                        :receipts approval-needed})
                        (emit-terminal-message! event-sink base content {:stop-reason :approval-required
                                                                         :approvals approvals})
                        (event! event-sink :agent-end base {:steps (inc step-no)
                                                            :stop-reason :approval-required
                                                            :stream stream?*})
                        {:content content
                         :request-id request-id
                         :final-messages (conj final-messages* {:role "assistant"
                                                                :content content})
                         :trace trace*
                         :usage usage*
                         :stop-reason :approval-required
                         :approvals approvals
                         :stream? stream?*})
                      (recur (inc step-no)
                             (merge state (:state executed))
                             (into planner-messages protocol-messages)
                             trace*
                             final-messages*
                             usage*)))))))))
      (catch Exception e
        (if (or (cancelled? cancellation-token)
                (= :chat-cancelled (some-> e ex-data :type)))
          (do
            (emit-terminal-message! event-sink base stopped-content {:stop-reason :cancelled})
            (event! event-sink :agent-end base {:stop-reason :cancelled
                                                :message (.getMessage e)
                                                :stream stream?*})
            {:content stopped-content
             :request-id request-id
             :final-messages [{:role "assistant" :content stopped-content}]
             :trace []
             :usage {}
             :stop-reason :cancelled
             :stream? stream?*
             :cancelled? true})
          (do
            (event! event-sink :agent-end base {:stop-reason :planner-error
                                                :message (.getMessage e)
                                                :type (some-> e ex-data :type)
                                                :stream stream?*})
            (if fallback-fn
              (try
                (reset! delta-emitted? false)
                (event! event-sink :message-start base {:role "assistant"
                                                        :fallback? true
                                                        :reason (.getMessage e)})
                (let [fallback (fallback-fn {:messages messages*
                                             :error e
                                             :stream? stream?*
                                             :emit-delta emit-delta!})
                      content (:content fallback)]
                  (when-not @delta-emitted?
                    (emit-delta! content))
                  (event! event-sink :message-end base {:role "assistant"
                                                        :content content
                                                        :final? true
                                                        :fallback? true
                                                        :stop-reason (if (:error? fallback) :error :completed)})
                  (event! event-sink :agent-end base {:stop-reason (if (:error? fallback) :error :completed)
                                                      :fallback? true
                                                      :stream stream?*})
                  (merge {:request-id request-id
                          :final-messages [{:role "assistant" :content content}]
                          :trace []
                          :usage (:usage fallback {})
                          :stop-reason (if (:error? fallback) :error :completed)
                          :stream? stream?*}
                         fallback))
                (catch Exception fallback-error
                  (let [content (str "Chat failed: " (.getMessage fallback-error))]
                    (event! event-sink :message-end base {:role "assistant"
                                                          :content content
                                                          :final? true
                                                          :fallback? true
                                                          :stop-reason :error})
                    (event! event-sink :agent-end base {:stop-reason :error
                                                        :fallback? true
                                                        :message (.getMessage fallback-error)
                                                        :initial-error (.getMessage e)
                                                        :stream stream?*})
                    {:content content
                     :request-id request-id
                     :final-messages [{:role "assistant" :content content}]
                     :trace []
                     :usage {}
                     :stop-reason :error
                     :stream? stream?*
                     :error? true})))
              (throw e))))))))
