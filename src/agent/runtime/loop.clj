(ns agent.runtime.loop
  "Core agent loop for one chat turn. Plans model calls, streams deltas, executes
   tools, handles approvals/retries/fallbacks, and emits normalized events while
   leaving persistence and transports to the chat layer."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.defaults :as defaults]
   [agent.llm.messages :as llm-messages]
   [agent.planner :as planner]
   [agent.runtime.cancel :as cancel]
   [agent.runtime.turn-policy :as turn-policy]
   [agent.runtime.doom-loop :as doom-loop]
   [agent.runtime.events :as runtime-events]
   [agent.runtime.messages :as runtime-messages]
   [agent.runtime.nudge :as nudge]
   [agent.runtime.result :as result-tool]
   [agent.runtime.tool-router :as tool-router]
   [agent.security :as security]
   [agent.util :as util]
   [clojure.string :as str]))

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

(defn- retry-events! [sink base verdict step-no]
  (runtime-events/emit! sink :guardrail-blocked base {:step step-no
                                                      :action (name (:action verdict))
                                                      :reason (name (:reason verdict))
                                                      :fingerprint (:fingerprint verdict)})
  (runtime-events/emit! sink :nudge-injected base {:step step-no
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

;; Terminal emitters. Each one owns the event sequence and terminal-result
;; shape for exactly one way the loop can end; run! itself only decides
;; WHICH ending applies. `env` carries the per-run constants
;; {:sink :base :request-id :stream?}.

(defn- fatal-guardrail! [{:keys [sink base request-id stream?]} verdict step-no final-messages trace usage]
  (let [content (or (:content verdict) runtime-messages/guardrail-exhausted-content)]
    (runtime-events/emit-terminal-message! sink base content {:stop-reason :guardrail-exhausted
                                                              :metadata {:reason (some-> (:reason verdict) name)}})
    (runtime-events/emit! sink :agent-end base {:steps (inc step-no)
                                                :stop-reason :guardrail-exhausted
                                                :stream stream?})
    (terminal-result content request-id
                     (conj final-messages {:role "assistant" :content content})
                     trace usage :guardrail-exhausted stream?
                     {:guardrail? true})))

(defn- max-steps-terminal! [{:keys [sink base request-id stream?]} step-no final-messages trace usage]
  (runtime-events/emit-terminal-message! sink base runtime-messages/max-steps-content {:stop-reason :max-steps})
  (runtime-events/emit! sink :agent-end base {:steps step-no :stop-reason :max-steps :stream stream?})
  (terminal-result runtime-messages/max-steps-content request-id
                   (conj final-messages {:role "assistant" :content runtime-messages/max-steps-content})
                   trace usage :max-steps stream?))

(defn- max-tokens-terminal! [{:keys [sink base request-id stream?]} llm-response step-no final-messages trace usage]
  (runtime-events/emit-max-token-truncation! sink base request-id llm-response)
  (runtime-events/emit! sink :agent-end base {:steps (inc step-no)
                                              :stop-reason :max-tokens
                                              :stream stream?})
  (terminal-result runtime-messages/max-tokens-content request-id
                   (conj final-messages {:role "assistant" :content runtime-messages/max-tokens-content})
                   trace usage :max-tokens stream?
                   {:error? true}))

(defn- doom-loop-terminal! [{:keys [sink base request-id stream?]} doom-check config step-no final-messages trace usage]
  (let [call (:call doom-check)
        sequence? (= :repeated-sequence (:detection doom-check))
        payload (cond-> {:kind :doom-loop-detected
                         :detection (:detection doom-check)
                         :count (:count doom-check)
                         :threshold (if sequence?
                                      (:sequence-threshold config)
                                      (:threshold config))
                         :window-size (if sequence?
                                        (:sequence-window-size config)
                                        (:window-size config))}
                  call (assoc :tool-name (:tool-name call)
                              :input (:input call)
                              :fingerprint (:fingerprint call))
                  sequence? (assoc :sequence-length (:sequence-length doom-check)
                                   :sequence (:sequence doom-check)))]
    (runtime-events/emit! sink :tool-execution-update base payload)
    (runtime-events/emit-terminal-message! sink base runtime-messages/doom-loop-content {:stop-reason :doom-loop
                                                                                         :metadata payload})
    (runtime-events/emit! sink :agent-end base {:steps (inc step-no)
                                                :stop-reason :doom-loop
                                                :stream stream?})
    (terminal-result runtime-messages/doom-loop-content request-id
                     (conj final-messages {:role "assistant" :content runtime-messages/doom-loop-content})
                     trace usage :doom-loop stream?
                     {:guardrail? true
                      :doom-loop payload})))

(defn- completed-terminal!
  [{:keys [sink base request-id stream?]} content llm-response step-no final-messages trace usage]
  (runtime-events/emit! sink :message-end base
                        (cond-> {:role "assistant"
                                 :content content
                                 :final? true
                                 :stop-reason :completed}
                          (:usage llm-response) (assoc :metadata {:usage (:usage llm-response)})))
  (runtime-events/emit! sink :agent-end base {:steps (inc step-no)
                                              :stop-reason :completed
                                              :stream stream?})
  (terminal-result content request-id
                   (conj final-messages {:role "assistant" :content content})
                   trace usage :completed stream?))

(defn- approval-terminal!
  [{:keys [sink base request-id stream?]} approvals pending-receipts step-no final-messages trace usage]
  (let [content (approval-message approvals)]
    (runtime-events/emit! sink :tool-execution-update base {:kind :approval-required
                                                            :approvals approvals
                                                            :receipts pending-receipts})
    (runtime-events/emit-terminal-message! sink base content {:stop-reason :approval-required
                                                              :approvals approvals})
    (runtime-events/emit! sink :agent-end base {:steps (inc step-no)
                                                :stop-reason :approval-required
                                                :stream stream?})
    (terminal-result content request-id
                     (conj final-messages {:role "assistant" :content content})
                     trace usage :approval-required stream?
                     {:approvals approvals})))

(defn- cancelled-terminal! [{:keys [sink base request-id stream?]} e]
  (runtime-events/emit-terminal-message! sink base runtime-messages/stopped-content {:stop-reason :cancelled})
  (runtime-events/emit! sink :agent-end base {:stop-reason :cancelled
                                              :message (.getMessage ^Throwable e)
                                              :stream stream?})
  (terminal-result runtime-messages/stopped-content request-id
                   [{:role "assistant" :content runtime-messages/stopped-content}]
                   [] {} :cancelled stream?
                   {:cancelled? true}))

(defn- plan-turn!
  [{:keys [planner-messages fallback-messages context-pack-fn planner-fn
           system-prompt tools model provider-config request-id session-id step-no
           chat-profile* state telemetry observer trace agent-id* on-content-delta
           stream?* emit-thinking! event-sink base]}]
  (let [{planner-messages* :messages repairs :repairs} (runtime-messages/normalize-chat-history planner-messages)
        _ (reset! fallback-messages planner-messages*)
        _ (when (seq repairs)
            (runtime-events/emit! event-sink :message-update base {:kind :history-repaired :repairs repairs}))
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
            (runtime-events/emit! event-sink :message-update base
                                  {:kind :context-budget
                                   :step step-no
                                   :tokens-before (:tokens-before context-pack)
                                   :tokens-after (:tokens-after context-pack)
                                   :budgets (:budgets context-pack)
                                   :decisions (:decisions context-pack)}))
        _ (doseq [warning (:warnings context-pack)]
            (runtime-events/emit! event-sink :message-update base {:kind :context-warning
                                                                   :step step-no
                                                                   :warning warning}))
        _ (when-let [compaction (:compaction context-pack)]
            (runtime-events/emit! event-sink :message-update base {:kind :context-compacted
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
                          :on-thinking-delta (when stream?* emit-thinking!)})]
    {:step step :planner-visible-messages planner-visible-messages :allowed-tools allowed-tools}))

(defn- recover!
  [{:keys [cancellation-token env fallback-fn result-tool? delta-emitted? event-sink base fallback-messages stream?* emit-delta! request-id]} e]
  (if (or (cancel/cancelled? cancellation-token)
          (= :chat-cancelled (some-> e ex-data :type)))
    (cancelled-terminal! env e)
    (if (and fallback-fn (not result-tool?))
      (try
        (reset! delta-emitted? false)
        (runtime-events/emit! event-sink :message-start base {:role "assistant"
                                                              :fallback? true
                                                              :reason (.getMessage e)})
        (let [fallback (fallback-fn {:messages @fallback-messages
                                     :error e
                                     :stream? stream?*
                                     :emit-delta emit-delta!})
              content (:content fallback)]
          (when-not @delta-emitted?
            (emit-delta! content))
          (runtime-events/emit! event-sink :message-end base {:role "assistant"
                                                              :content content
                                                              :final? true
                                                              :fallback? true
                                                              :stop-reason (if (:error? fallback) :error :completed)})
          (runtime-events/emit! event-sink :agent-end base {:stop-reason (if (:error? fallback) :error :completed)
                                                            :fallback? true
                                                            :stream stream?*})
          (merge (terminal-result content request-id
                                  [{:role "assistant" :content content}]
                                  [] (:usage fallback {})
                                  (if (:error? fallback) :error :completed) stream?*)
                 fallback))
        (catch Exception fallback-error
          (when (= :chat-persistence-failed (:type (ex-data fallback-error))) (throw fallback-error))
          (let [content (str "Chat failed: " (.getMessage fallback-error))]
            (runtime-events/emit! event-sink :message-end base {:role "assistant"
                                                                :content content
                                                                :final? true
                                                                :fallback? true
                                                                :stop-reason :error})
            (runtime-events/emit! event-sink :agent-end base {:stop-reason :error
                                                              :fallback? true
                                                              :message (.getMessage fallback-error)
                                                              :initial-error (.getMessage e)
                                                              :stream stream?*})
            (terminal-result content request-id
                             [{:role "assistant" :content content}]
                             [] {} :error stream?*
                             {:error? true}))))
      (do
        (runtime-events/emit! event-sink :agent-end base {:stop-reason :planner-error
                                                          :message (.getMessage e)
                                                          :type (some-> e ex-data :type)
                                                          :stream stream?*})
        (throw e)))))

(defn- submit-result!
  [{:keys [env event-sink base request-id tool-output-max-chars discard-pending-deltas! emit-delta!]}
   {:keys [step-no trace final-messages nudge-state] :as turn}
   {:keys [submission llm-response0 step planner-visible-messages usage*]}]
  (let [_ (discard-pending-deltas!)
        error (:error submission)
        receipts (result-tool/receipts llm-response0 error)
        protocol (if (seq (:tool-calls llm-response0))
                   (runtime-events/emit-tool-turn! event-sink base request-id llm-response0
                                                  (:tool-calls llm-response0) receipts tool-output-max-chars)
                   (do
                     (runtime-events/emit! event-sink :message-end base
                                          {:role "assistant" :content (:content llm-response0 "")
                                           :audit? true :final? false})
                     [{:role "assistant" :content (:content llm-response0 "")}]))
        trace* (conj trace {:step step-no :directives (:directives step) :receipts receipts})
        final-messages* (into final-messages protocol)]
    (runtime-events/emit! event-sink :turn-end base
                         {:step step-no :directives (:directives step) :receipts receipts})
    (if error
      (let [verdict (turn-policy/result-verdict nudge-state error)]
        (retry-events! event-sink base verdict step-no)
        (if (= :retry (:action verdict))
          {:next-turn (turn-policy/advance turn
                       {:planner-messages planner-visible-messages :protocol protocol
                        :prompt {:role "user" :content error} :trace-entry (peek trace*)
                        :usage usage* :nudge-state (update nudge-state :result-retries (fnil inc 0))})}
          (fatal-guardrail! env verdict step-no final-messages* trace* usage*)))
      (let [content (:content submission)]
        (emit-delta! content)
        (assoc (completed-terminal! env content llm-response0 step-no final-messages* trace* usage*)
               :result-submitted? true)))))

(defn- execute-turn!
  [{:keys [env event-sink base request-id doom-loop-config* execute-step-fn cancellation-token
           chat-profile* approval-fn tool-output-max-chars delta-emitted? emit-delta!
           discard-pending-deltas! flush-pending-deltas!]}
   {:keys [step-no doom-loop-state trace final-messages nudge-state] :as turn}
   {:keys [executable-step step* llm-response planner-visible-messages usage*]}]
  (let [doom-check (doom-loop/check-step doom-loop-state doom-loop-config* executable-step)
        doom-loop-state* (:state doom-check)]
    (if (:detected? doom-check)
      (do
        (discard-pending-deltas!)
        (doom-loop-terminal! env doom-check doom-loop-config* step-no
                             final-messages trace usage*))
      (let [executed (execute-step-fn executable-step)
            _ (cancel/throw-if-cancelled! cancellation-token)
            receipts (:receipts executed)
            post-verdict (nudge/check-after-exec chat-profile* nudge-state {:receipts receipts})
            trace-entry {:step step-no :directives (:directives step*) :receipts receipts}
            trace* (conj trace trace-entry)]
        (case (turn-policy/post-action post-verdict)
          :retry
          (do
            (discard-pending-deltas!)
            (retry-events! event-sink base post-verdict step-no)
            (runtime-events/emit! event-sink :turn-end base {:step step-no
                                                             :directives (:directives step*)
                                                             :receipts receipts})
            {:next-turn (turn-policy/advance turn
                    {:planner-messages planner-visible-messages
                     :execution-state (:state executed) :prompt (nudge/nudge-message post-verdict)
                     :trace-entry trace-entry :usage usage* :doom-loop-state doom-loop-state*
                     :nudge-state (-> nudge-state
                                      (nudge/record-execution executable-step receipts)
                                      (nudge/record-retry post-verdict))})})

          :fatal
          (do
            (discard-pending-deltas!)
            (retry-events! event-sink base post-verdict step-no)
            (fatal-guardrail! env post-verdict step-no final-messages trace* usage*))

          :accept
          (do
            (flush-pending-deltas!)
            (let [approval-needed (vec (approval-receipts receipts))
                  approvals (when (seq approval-needed)
                              (if approval-fn (approval-fn approval-needed) approval-needed))
                  receipts* (if (seq approvals)
                              (align-approval-reasons receipts approvals)
                              receipts)
                  approval-needed* (vec (approval-receipts receipts*))]
              (runtime-events/emit! event-sink :turn-end base {:step step-no
                                                               :directives (:directives step*)
                                                               :receipts receipts*})
              (let [provider-tool-calls (seq (:tool-calls llm-response))
                    protocol-messages (when provider-tool-calls
                                        (runtime-events/emit-tool-turn! event-sink base request-id llm-response
                                                                        provider-tool-calls receipts* tool-output-max-chars))
                    final-messages* (into final-messages protocol-messages)
                    receipt (complete-receipt receipts)]
                (case (turn-policy/terminal-action receipt approval-needed*)
                  :complete
                  (let [content (util/result-content (:result receipt))]
                    (when-not @delta-emitted?
                      (emit-delta! content))
                    (completed-terminal! env content llm-response step-no
                                         final-messages* trace* usage*))
                  :approval
                  (approval-terminal! env approvals approval-needed* step-no
                                      final-messages* trace* usage*)
                  :continue
                  {:next-turn (turn-policy/advance turn
                               {:planner-messages planner-visible-messages
                                :protocol protocol-messages
                                :execution-state (:state executed) :trace-entry trace-entry :usage usage*
                                :doom-loop-state doom-loop-state*
                                :nudge-state (nudge/record-execution nudge-state executable-step receipts*)})})))))))))


(defn run!
  [{:keys [messages context-injectors tools
           planner-fn context-pack-fn event-sink
           cancellation-token request-id session-id agent-id max-steps stream?
           tool-output-max-chars doom-loop-config chat-profile on-thinking-delta result-tool?]
    :as opts
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
        env {:sink event-sink :base base :request-id request-id :stream? stream?*}
        chat-profile* (cond-> (nudge/normalize-profile chat-profile)
                        result-tool? (assoc :respond-tool? false))
        tools (cond-> (vec tools) result-tool? (conj result-tool/tool))
        fallback-messages (atom messages*)
        delta-emitted? (atom false)
        pending-deltas (atom [])
        buffer-deltas? (or result-tool? (nudge/enabled? chat-profile*))
        emit-delta! (fn [chunk]
                      (when (and (string? chunk) (not= "" chunk))
                        (reset! delta-emitted? true)
                        (runtime-events/emit-message-delta! event-sink base chunk)))
        emit-thinking! (fn [chunk]
                         (when (and (string? chunk) (not= "" chunk))
                           (runtime-events/emit-thinking-delta! event-sink base chunk)
                           (when on-thinking-delta
                             (on-thinking-delta chunk))))
        flush-pending-deltas! (fn []
                                (doseq [chunk @pending-deltas]
                                  (emit-delta! chunk))
                                (reset! pending-deltas []))
        discard-pending-deltas! #(reset! pending-deltas [])
        on-content-delta (when stream?*
                           (fn [chunk]
                             (cancel/throw-if-cancelled! cancellation-token)
                             (when (and (string? chunk) (not= "" chunk))
                               (if buffer-deltas?
                                 (swap! pending-deltas conj chunk)
                                 (emit-delta! chunk)))))
        ctx (merge opts {:env env :base base :agent-id* agent-id* :tools tools
                         :planner-fn planner-fn :context-pack-fn context-pack-fn
                         :chat-profile* chat-profile* :doom-loop-config* doom-loop-config*
                         :tool-output-max-chars tool-output-max-chars
                         :fallback-messages fallback-messages :delta-emitted? delta-emitted?
                         :stream?* stream?* :emit-delta! emit-delta! :emit-thinking! emit-thinking!
                         :on-content-delta on-content-delta
                         :flush-pending-deltas! flush-pending-deltas!
                         :discard-pending-deltas! discard-pending-deltas!})]
    (runtime-events/emit! event-sink :agent-start base {:message-count (count messages*) :stream stream?*})
    (try
      (loop [turn (turn-policy/initial-turn messages* (doom-loop/new-state) (nudge/new-state))]
        (cancel/throw-if-cancelled! cancellation-token)
        (let [{:keys [step-no trace final-messages usage nudge-state]} turn]
          (if (>= step-no max-steps)
            (max-steps-terminal! env step-no final-messages trace usage)
            (let [_ (runtime-events/emit! event-sink :turn-start base {:step step-no})
                  _ (reset! delta-emitted? false)
                  _ (discard-pending-deltas!)
                  _ (runtime-events/emit! event-sink :message-start base {:role "assistant" :step step-no})
                  {:keys [step planner-visible-messages allowed-tools]} (plan-turn! (merge ctx turn))
                  _ (cancel/throw-if-cancelled! cancellation-token)
                  llm-response0 (:llm-response step)
                  step* (-> step (turn-policy/strip-respond-when-mixed llm-response0)
                            (turn-policy/attach-allowed-tools allowed-tools))
                  executable-step (select-keys step* [:schema-version :state :directives :receipts])
                  llm-response (:llm-response step*)
                  usage* (usage+ usage (:usage llm-response))
                  max-token? (runtime-events/max-token-stop-reason? (:stop-reason llm-response))
                  ;; Length truncation must not discard complete executable tool calls.
                  max-token-terminal? (and max-token? (empty? (:tool-calls llm-response)))
                  submission (when (and result-tool? (not max-token-terminal?)) (result-tool/check llm-response0))
                  pre-verdict (nudge/check-before-exec chat-profile* nudge-state
                               {:step executable-step :llm-response llm-response
                                :allowed-tools allowed-tools :max-token? max-token?})
                  plan {:step step :step* step* :executable-step executable-step
                        :llm-response llm-response :llm-response0 llm-response0 :usage* usage*
                        :submission submission :planner-visible-messages planner-visible-messages}
                  action (turn-policy/next-action {:submission submission :pre-verdict pre-verdict
                                                   :max-token-terminal? max-token-terminal?})
                  outcome
                  (case action
                    :submission (submit-result! ctx turn plan)
                    :execute (execute-turn! ctx turn plan)
                    :retry (do
                             (discard-pending-deltas!)
                             (retry-events! event-sink base pre-verdict step-no)
                             {:next-turn (turn-policy/advance turn
                                           {:planner-messages planner-visible-messages
                                            :prompt (nudge/nudge-message pre-verdict) :usage usage*
                                            :nudge-state (nudge/record-retry nudge-state pre-verdict)})})
                    :fatal (do
                             (discard-pending-deltas!)
                             (retry-events! event-sink base pre-verdict step-no)
                             (fatal-guardrail! env pre-verdict step-no final-messages trace usage*))
                    :max-tokens (do
                                  (discard-pending-deltas!)
                                  (max-tokens-terminal! env llm-response step-no final-messages trace usage*)))]
              (if-let [next-turn (:next-turn outcome)] (recur next-turn) outcome)))))
      (catch Exception e
        (when (= :chat-persistence-failed (:type (ex-data e))) (throw e))
        (recover! ctx e)))))
