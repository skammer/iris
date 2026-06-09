(ns agent.runtime.loop
  "Evented chat-agent loop. No persistence or transport concerns live here."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.defaults :as defaults]
   [agent.llm.messages :as llm-messages]
   [agent.planner :as planner]
   [agent.runtime.cancel :as cancel]
   [agent.runtime.doom-loop :as doom-loop]
   [agent.runtime.events :as runtime-events]
   [agent.runtime.messages :as runtime-messages]
   [agent.runtime.nudge :as nudge]
   [agent.runtime.tool-router :as tool-router]
   [agent.security :as security]
   [agent.util :as util]
   [clojure.string :as str]))

(def ^:private event! runtime-events/emit!)
(def ^:private emit-message-delta! runtime-events/emit-message-delta!)
(def ^:private emit-thinking-delta! runtime-events/emit-thinking-delta!)
(def ^:private emit-terminal-message! runtime-events/emit-terminal-message!)
(def ^:private max-token-stop-reason? runtime-events/max-token-stop-reason?)
(def ^:private emit-max-token-truncation! runtime-events/emit-max-token-truncation!)
(def ^:private emit-tool-turn! runtime-events/emit-tool-turn!)

(def ^:private cancelled? cancel/cancelled?)
(def ^:private throw-if-cancelled! cancel/throw-if-cancelled!)

(def ^:private result-text util/result-content)
(def ^:private stopped-content runtime-messages/stopped-content)
(def ^:private max-steps-content runtime-messages/max-steps-content)
(def ^:private doom-loop-content runtime-messages/doom-loop-content)
(def ^:private max-tokens-content runtime-messages/max-tokens-content)
(def ^:private guardrail-exhausted-content runtime-messages/guardrail-exhausted-content)

(defn- approval-receipts [receipts]
  (filter #(= :approval-required (keyword (:status %))) receipts))

(defn- approval-required? [receipt]
  (= :approval-required (keyword (:status receipt))))

(defn- complete-receipt [receipts]
  (some #(when (= :completed (keyword (:status %))) %) receipts))

(defn- approval-message [approvals]
  (str "Tool approval required: "
       (str/join ", "
                 (map (fn [approval]
                        (str (:tool-name approval) " approval_id=" (:id approval)))
                      approvals))))

(defn- display-reason [approval]
  (some-> (:reason approval) str str/trim not-empty))

(defn- tool-call-key [value]
  (when-let [tool-call-id (:tool-call-id value)]
    [(some-> (:tool-name value) keyword) tool-call-id]))

(defn- input-key [value]
  ;; Same fingerprint as agent.tools.approvals/input-hash so approval
  ;; alignment cannot drift from approval validation.
  [(some-> (:tool-name value) keyword) (security/canonical-json (:input value))])

(defn- align-approval-reasons [receipts approvals]
  (let [approvals-by-tool-call (group-by tool-call-key (filter :tool-call-id approvals))
        approvals-by-input (group-by input-key approvals)]
    (mapv (fn [receipt]
            (if-not (approval-required? receipt)
              receipt
              (let [approval (or (some-> (tool-call-key receipt)
                                         approvals-by-tool-call
                                         first)
                                 (some-> (input-key receipt)
                                         approvals-by-input
                                         first))
                    reason (display-reason approval)]
                (cond-> receipt
                  reason (assoc :reason reason)))))
          receipts)))

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

(defn- directive-tool-name [directive]
  (keyword (get-in directive [:payload :tool-name])))

(defn- respond-directive? [directive]
  (and (= :tool-call (keyword (:type directive)))
       (= :respond (directive-tool-name directive))))

(defn- respond-content [directive]
  (or (get-in directive [:payload :input :content])
      (get-in directive [:payload :input "content"])
      ""))

(defn- strip-respond-when-mixed [step llm-response]
  (let [directives (vec (:directives step))
        respond-only? (and (seq directives) (every? respond-directive? directives))
        mixed? (and (some respond-directive? directives)
                    (some #(and (= :tool-call (keyword (:type %)))
                                (not (respond-directive? %)))
                          directives))]
    (cond
      respond-only?
      (let [content (respond-content (first directives))]
        (-> step
            (assoc :directives [{:type :complete
                                 :payload {:result content}}])
            (assoc :llm-response (assoc llm-response
                                        :content content
                                        :tool-calls []
                                        :synthetic-respond? true))))

      mixed?
      (let [keep-directives (remove respond-directive? directives)
            keep-tools (remove #(= :respond (keyword (:name (runtime-messages/normalize-tool-call-block "respond" 0 %))))
                               (:tool-calls llm-response))]
        (-> step
            (assoc :directives (vec keep-directives))
            (assoc :llm-response (assoc llm-response :tool-calls (vec keep-tools)))))

      :else step)))

(defn- attach-allowed-tools [step allowed-tools]
  (if (some? allowed-tools)
    (update step :directives
            (fn [directives]
              (mapv (fn [directive]
                      (if (= :tool-call (keyword (:type directive)))
                        (update-in directive [:payload :context] merge {:allowed-tools allowed-tools})
                        directive))
                    directives)))
    step))

(defn- retry-events! [sink base verdict step-no]
  (event! sink :guardrail-blocked base {:step step-no
                                        :action (name (:action verdict))
                                        :reason (name (:reason verdict))
                                        :fingerprint (:fingerprint verdict)})
  (event! sink :nudge-injected base {:step step-no
                                     :reason (name (:reason verdict))
                                     :content (:content verdict)}))

(defn- terminal-result
  "Canonical terminal return map for run!. Every terminal branch returns the
   same seven base keys; `extra` carries any branch-specific keys. Centralising
   the shape here means a contract change touches one site instead of nine
   (a missed site is how the max-token branch silently drifted)."
  ([content request-id final-messages trace usage stop-reason stream?]
   (terminal-result content request-id final-messages trace usage stop-reason stream? nil))
  ([content request-id final-messages trace usage stop-reason stream? extra]
   (merge {:content content
           :request-id request-id
           :final-messages final-messages
           :trace trace
           :usage usage
           :stop-reason stop-reason
           :stream? stream?}
          extra)))

(defn- fatal-guardrail! [sink base verdict step-no final-messages trace usage stream? request-id]
  (let [content (or (:content verdict) guardrail-exhausted-content)]
    (emit-terminal-message! sink base content {:stop-reason :guardrail-exhausted
                                               :metadata {:reason (some-> (:reason verdict) name)}})
    (event! sink :agent-end base {:steps (inc step-no)
                                  :stop-reason :guardrail-exhausted
                                  :stream stream?})
    (terminal-result content request-id
                     (conj final-messages {:role "assistant" :content content})
                     trace usage :guardrail-exhausted stream?
                     {:guardrail? true})))

(defn run!
  [{:keys [messages context-injectors system-prompt tools model provider-config
           telemetry observer planner-fn context-pack-fn execute-step-fn approval-fn fallback-fn event-sink
           cancellation-token request-id session-id agent-id max-steps stream?
           tool-output-max-chars doom-loop-config chat-profile on-thinking-delta]
    :or {planner-fn planner/plan-step!
         context-pack-fn identity
         max-steps defaults/chat-max-steps
         tool-output-max-chars defaults/tool-output-max-chars
         doom-loop-config doom-loop/default-config}}]
  (let [base {:entity-type :session :entity-id session-id :request-id request-id}
        agent-id* (or agent-id session-id "chat")
        doom-loop-config* (doom-loop/normalize-config doom-loop-config)
        messages* (apply-context-injectors (vec (or messages [])) context-injectors)
        stream?* (true? stream?)
        chat-profile* (nudge/normalize-profile chat-profile)
        delta-emitted? (atom false)
        pending-deltas (atom [])
        buffer-deltas? (nudge/enabled? chat-profile*)
        emit-delta! (fn [chunk]
                      (when (and (string? chunk) (not= "" chunk))
                        (reset! delta-emitted? true)
                        (emit-message-delta! event-sink base chunk)))
        emit-thinking! (fn [chunk]
                         (when (and (string? chunk) (not= "" chunk))
                           (emit-thinking-delta! event-sink base chunk)
                           (when on-thinking-delta
                             (on-thinking-delta chunk))))
        flush-pending-deltas! (fn []
                                (doseq [chunk @pending-deltas]
                                  (emit-delta! chunk))
                                (reset! pending-deltas []))
        discard-pending-deltas! #(reset! pending-deltas [])
        on-content-delta (when stream?*
                           (fn [chunk]
                             (throw-if-cancelled! cancellation-token)
                             (when (and (string? chunk) (not= "" chunk))
                               (if buffer-deltas?
                                 (swap! pending-deltas conj chunk)
                                 (emit-delta! chunk)))))]
    (event! event-sink :agent-start base {:message-count (count messages*) :stream stream?*})
    (try
      (loop [step-no 0
             state {}
             planner-messages messages*
             trace []
             final-messages []
             usage {}
             doom-loop-state (doom-loop/new-state)
             nudge-state (nudge/new-state)]
        (throw-if-cancelled! cancellation-token)
        (if (>= step-no max-steps)
          (do
            (emit-terminal-message! event-sink base max-steps-content {:stop-reason :max-steps})
            (event! event-sink :agent-end base {:steps step-no :stop-reason :max-steps :stream stream?*})
            (terminal-result max-steps-content request-id
                             (conj final-messages {:role "assistant" :content max-steps-content})
                             trace usage :max-steps stream?*))
          (let [_ (event! event-sink :turn-start base {:step step-no})
                _ (reset! delta-emitted? false)
                _ (discard-pending-deltas!)
                _ (event! event-sink :message-start base {:role "assistant" :step step-no})
                {planner-messages* :messages repairs :repairs} (runtime-messages/normalize-chat-history planner-messages)
                _ (when (seq repairs)
                    (event! event-sink :message-update base {:kind :history-repaired :repairs repairs}))
                context-pack-raw (context-pack-fn {:messages planner-messages*
                                                   :system-prompt system-prompt
                                                   :tools tools
                                                   :model model
                                                   :provider-config provider-config
                                                   :request-id request-id
                                                   :session-id session-id
                                                   :step step-no})
                context-pack (cond
                               (vector? context-pack-raw) {:messages context-pack-raw}
                               (map? context-pack-raw) context-pack-raw
                               :else {:messages planner-messages*})
                planner-visible-messages (or (:messages context-pack) planner-messages*)
                routed (tool-router/route-tools {:tools tools
                                                 :profile chat-profile*
                                                 :messages planner-visible-messages})
                routed-tools (:tools routed)
                allowed-tools (:allowed-tools routed)
                _ (when (contains? context-pack :tokens-before)
                    (event! event-sink :message-update base
                            {:kind :context-budget
                             :step step-no
                             :tokens-before (:tokens-before context-pack)
                             :tokens-after (:tokens-after context-pack)
                             :budgets (:budgets context-pack)
                             :decisions (:decisions context-pack)}))
                _ (doseq [warning (:warnings context-pack)]
                    (event! event-sink :message-update base {:kind :context-warning
                                                             :step step-no
                                                             :warning warning}))
                _ (when-let [compaction (:compaction context-pack)]
                    (event! event-sink :message-update base {:kind :context-compacted
                                                             :step step-no
                                                             :compaction compaction}))
                step (planner-fn provider-config
                                 {:messages planner-visible-messages
                                  :state state
                                  :tools routed-tools
                                  :telemetry telemetry
                                  :observer observer
                                  :trace trace
                                  :agent-id agent-id*
                                  :request-id request-id
                                  :session-id session-id
                                  :model model
                                  :system-prompt system-prompt
                                  :context-pack context-pack
                                  :tool-choice (when (and (:force-tool-choice? chat-profile*) (seq routed-tools))
                                                 "required")
                                  :on-content-delta on-content-delta
                                  :on-thinking-delta (when stream?* emit-thinking!)})
                _ (throw-if-cancelled! cancellation-token)
                llm-response0 (:llm-response step)
                step* (-> step
                          (strip-respond-when-mixed llm-response0)
                          (attach-allowed-tools allowed-tools))
                executable-step (select-keys step* [:schema-version :state :directives :receipts])
                llm-response (:llm-response step*)
                usage* (usage+ usage (:usage llm-response))
                max-token? (max-token-stop-reason? (:stop-reason llm-response))
                ;; A length-truncated turn is only terminal when it produced no
                ;; executable tool calls. finish_reason="length" frequently rides
                ;; along with a complete tool_calls array (the model emitted the
                ;; call, then hit the output cap) — discarding those makes the
                ;; agent dead-end on turns that actually produced work. When the
                ;; tool calls are present we fall through and execute them; only
                ;; a tool-call-free truncation surfaces as a max-tokens stop.
                max-token-terminal? (and max-token?
                                         (empty? (:tool-calls llm-response)))
                pre-verdict (nudge/check-before-exec chat-profile*
                                                     nudge-state
                                                     {:step executable-step
                                                      :llm-response llm-response
                                                      :allowed-tools allowed-tools
                                                      :max-token? max-token?})]
            (cond
              (= :retry (:action pre-verdict))
              (do
                (discard-pending-deltas!)
                (retry-events! event-sink base pre-verdict step-no)
                (recur (inc step-no) state (conj planner-messages* (nudge/nudge-message pre-verdict))
                       trace final-messages usage* doom-loop-state
                       (nudge/record-retry nudge-state pre-verdict)))

              (= :fatal (:action pre-verdict))
              (do
                (discard-pending-deltas!)
                (retry-events! event-sink base pre-verdict step-no)
                (fatal-guardrail! event-sink base pre-verdict step-no final-messages trace usage* stream?* request-id))

              max-token-terminal?
              (do
                (discard-pending-deltas!)
                (emit-max-token-truncation! event-sink base request-id llm-response)
                (event! event-sink :agent-end base {:steps (inc step-no)
                                                    :stop-reason :max-tokens
                                                    :stream stream?*})
                (terminal-result max-tokens-content request-id
                                 (conj final-messages {:role "assistant" :content max-tokens-content})
                                 trace usage* :max-tokens stream?*
                                 {:error? true}))

              :else
              (let [doom-check (doom-loop/check-step doom-loop-state doom-loop-config* executable-step)
                    doom-loop-state* (:state doom-check)]
                (if (:detected? doom-check)
                  (let [call (:call doom-check)
                        payload {:kind :doom-loop-detected
                                 :tool-name (:tool-name call)
                                 :input (:input call)
                                 :fingerprint (:fingerprint call)
                                 :count (:count doom-check)
                                 :threshold (:threshold doom-loop-config*)
                                 :window-size (:window-size doom-loop-config*)}]
                    (discard-pending-deltas!)
                    (event! event-sink :tool-execution-update base payload)
                    (emit-terminal-message! event-sink base doom-loop-content {:stop-reason :doom-loop
                                                                               :metadata payload})
                    (event! event-sink :agent-end base {:steps (inc step-no)
                                                        :stop-reason :doom-loop
                                                        :stream stream?*})
                    (terminal-result doom-loop-content request-id
                                     (conj final-messages {:role "assistant" :content doom-loop-content})
                                     trace usage* :doom-loop stream?*
                                     {:guardrail? true
                                      :doom-loop payload}))
                  (let [executed (execute-step-fn executable-step)
                        _ (throw-if-cancelled! cancellation-token)
                        receipts (:receipts executed)
                        post-verdict (nudge/check-after-exec chat-profile* nudge-state {:receipts receipts})
                        trace-entry {:step step-no :directives (:directives step*) :receipts receipts}
                        trace* (conj trace trace-entry)]
                    (cond
                      (= :retry (:action post-verdict))
                      (do
                        (discard-pending-deltas!)
                        (retry-events! event-sink base post-verdict step-no)
                        (event! event-sink :turn-end base {:step step-no
                                                           :directives (:directives step*)
                                                           :receipts receipts})
                        (recur (inc step-no)
                               (merge state (:state executed))
                               (conj planner-messages* (nudge/nudge-message post-verdict))
                               trace*
                               final-messages
                               usage*
                               doom-loop-state*
                               (-> nudge-state
                                   (nudge/record-execution executable-step receipts)
                                   (nudge/record-retry post-verdict))))

                      (= :fatal (:action post-verdict))
                      (do
                        (discard-pending-deltas!)
                        (retry-events! event-sink base post-verdict step-no)
                        (fatal-guardrail! event-sink base post-verdict step-no final-messages trace* usage* stream?* request-id))

                      :else
                      (do
                        (flush-pending-deltas!)
                        (let [approval-needed (vec (approval-receipts receipts))
                              approvals (when (seq approval-needed)
                                          (if approval-fn (approval-fn approval-needed) approval-needed))
                              receipts* (if (seq approvals)
                                          (align-approval-reasons receipts approvals)
                                          receipts)
                              approval-needed* (vec (approval-receipts receipts*))]
                          (event! event-sink :turn-end base {:step step-no
                                                             :directives (:directives step*)
                                                             :receipts receipts*})
                          (let [provider-tool-calls (seq (:tool-calls llm-response))
                                protocol-messages (when provider-tool-calls
                                                    (emit-tool-turn! event-sink base request-id llm-response
                                                                     provider-tool-calls receipts* tool-output-max-chars))
                              final-messages* (into final-messages protocol-messages)]
                          (if-let [receipt (complete-receipt receipts)]
                            (let [content (result-text (:result receipt))]
                              (when-not @delta-emitted?
                                (emit-delta! content))
                              (event! event-sink :message-end base
                                      (cond-> {:role "assistant"
                                               :content content
                                               :final? true
                                               :stop-reason :completed}
                                        (:usage llm-response) (assoc :metadata {:usage (:usage llm-response)})))
                              (event! event-sink :agent-end base {:steps (inc step-no)
                                                                  :stop-reason :completed
                                                                  :stream stream?*})
                              (terminal-result content request-id
                                               (conj final-messages* {:role "assistant" :content content})
                                               trace* usage* :completed stream?*))
                            (if (seq approval-needed*)
                              (let [content (approval-message approvals)]
                                  (event! event-sink :tool-execution-update base {:kind :approval-required
                                                                                  :approvals approvals
                                                                                  :receipts approval-needed*})
                                  (emit-terminal-message! event-sink base content {:stop-reason :approval-required
                                                                                   :approvals approvals})
                                  (event! event-sink :agent-end base {:steps (inc step-no)
                                                                      :stop-reason :approval-required
                                                                      :stream stream?*})
                                  (terminal-result content request-id
                                                   (conj final-messages* {:role "assistant" :content content})
                                                   trace* usage* :approval-required stream?*
                                                   {:approvals approvals}))
                              (recur (inc step-no)
                                     (merge state (:state executed))
                                     (into planner-messages* protocol-messages)
                                     trace*
                                     final-messages*
                                     usage*
                                     doom-loop-state*
                                     (nudge/record-execution nudge-state executable-step receipts*)))))))))))))))
      (catch Exception e
        (if (or (cancelled? cancellation-token)
                (= :chat-cancelled (some-> e ex-data :type)))
          (do
            (emit-terminal-message! event-sink base stopped-content {:stop-reason :cancelled})
            (event! event-sink :agent-end base {:stop-reason :cancelled
                                                :message (.getMessage e)
                                                :stream stream?*})
            (terminal-result stopped-content request-id
                             [{:role "assistant" :content stopped-content}]
                             [] {} :cancelled stream?*
                             {:cancelled? true}))
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
                (merge (terminal-result content request-id
                                        [{:role "assistant" :content content}]
                                        [] (:usage fallback {})
                                        (if (:error? fallback) :error :completed) stream?*)
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
                  (terminal-result content request-id
                                   [{:role "assistant" :content content}]
                                   [] {} :error stream?*
                                   {:error? true}))))
            (do
              (event! event-sink :agent-end base {:stop-reason :planner-error
                                                  :message (.getMessage e)
                                                  :type (some-> e ex-data :type)
                                                  :stream stream?*})
              (throw e))))))))
