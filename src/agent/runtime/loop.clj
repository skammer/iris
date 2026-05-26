(ns agent.runtime.loop
  "Evented chat-agent loop. No persistence or transport concerns live here."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.llm.messages :as llm-messages]
   [agent.planner :as planner]
   [agent.runtime.doom-loop :as doom-loop]
   [agent.runtime.nudge :as nudge]
   [agent.runtime.schema :as runtime-schema]
   [agent.runtime.tool-router :as tool-router]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(def stopped-content "Stopped.")
(def max-steps-content "Stopped: max chat tool steps reached.")
(def doom-loop-content "Stopped: repeated identical tool call detected.")
(def max-tokens-content
  "[SYSTEM ERROR: Assistant response was truncated because it exceeded max output tokens. Truncated output was saved for audit but will not be reused as context. Retry with smaller, incremental changes.]")
(def guardrail-exhausted-content "Stopped: guardrail retry budget exhausted.")
(def synthetic-tool-result-content "not executed; retry possible")
(def empty-assistant-content "(no response)")

(defn- internal-stop-content? [content]
  (let [content* (str/trim (llm-messages/content-text content))]
    (or (str/starts-with? content* "Stopped:")
        (str/starts-with? content* "I couldn't complete this after guardrail retries."))))

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

(defn- message-tool-result-blocks [message]
  (filterv #(= :tool-result (:type %))
           (runtime-schema/normalize-content (:content message))))

(defn- synthetic-tool-result-message [tool-call]
  {:role "tool"
   :content [{:type :tool-result
              :tool-call-id (:id tool-call)
              :name (:name tool-call)
              :status :error
              :content synthetic-tool-result-content}]})

(defn- empty-assistant? [message]
  (and (= "assistant" (:role message))
       (empty? (runtime-schema/normalize-content (:content message)))))

(defn- placeholder-assistant [message]
  (assoc message :content [{:type :text :text empty-assistant-content}]))

(defn- append-missing-tool-results [acc pending]
  (if (seq (:order pending))
    (-> acc
        (update :messages into (map synthetic-tool-result-message (:order pending)))
        (update-in [:repairs :inserted-tool-results] (fnil + 0) (count (:order pending))))
    acc))

(defn- tool-result-id [block]
  (:tool-call-id block))

(defn- consume-tool-results [acc message pending]
  (let [pending-set (:ids pending)
        blocks (message-tool-result-blocks message)
        grouped (group-by #(contains? pending-set (tool-result-id %)) blocks)
        valid (vec (get grouped true))
        removed (count (get grouped false))
        consumed (set (map tool-result-id valid))
        pending* {:ids (apply disj pending-set consumed)
                  :order (vec (remove #(contains? consumed (:id %)) (:order pending)))}]
    [(cond-> acc
       (seq valid) (update :messages conj (assoc message :content valid))
       (pos? removed) (update-in [:repairs :removed-tool-results] (fnil + 0) removed))
     pending*]))

(defn normalize-chat-history
  "Repair provider tool protocol in transient LLM context. Does not persist."
  [messages]
  (letfn [(finish-pending [acc pending]
            [(append-missing-tool-results acc pending) nil])]
    (loop [remaining (mapv llm-messages/message->internal (or messages []))
           acc {:messages [] :repairs {}}
           pending nil]
      (if-let [message (first remaining)]
        (let [role (:role message)
              rest-messages (subvec remaining 1)]
          (case role
            "assistant"
            (let [[acc* _] (if pending (finish-pending acc pending) [acc nil])
                  message* (if (empty-assistant? message)
                             (placeholder-assistant message)
                             message)
                  tool-calls (llm-messages/message-tool-calls message*)
                  acc** (cond-> (update acc* :messages conj message*)
                          (and (not= message message*) (seq rest-messages))
                          (update-in [:repairs :placeholder-assistant-messages] (fnil inc 0)))]
              (if (and (internal-stop-content? (:content message*))
                       (empty? tool-calls))
                (recur rest-messages
                       (update-in acc* [:repairs :removed-internal-stop-messages] (fnil inc 0))
                       nil)
                (recur rest-messages
                       acc**
                       (when (seq tool-calls)
                         {:ids (set (keep :id tool-calls))
                          :order (vec (filter :id tool-calls))}))))

            "tool"
            (if pending
              (let [[acc* pending*] (consume-tool-results acc message pending)]
                (recur rest-messages acc* (when (seq (:order pending*)) pending*)))
              (recur rest-messages
                     (update-in acc [:repairs :removed-tool-results] (fnil + 0)
                                (max 1 (count (message-tool-result-blocks message))))
                     nil))

            (let [[acc* _] (if pending (finish-pending acc pending) [acc nil])]
              (recur rest-messages (update acc* :messages conj message) nil))))
        (let [[acc* _] (if pending (finish-pending acc pending) [acc nil])]
          acc*)))))

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

(defn- max-token-stop-reason? [reason]
  (contains? #{"length" "max_tokens" "max-tokens" "max_tokens_reached" "max-output-tokens"}
             (some-> reason name str/lower-case)))

(defn- llm-response-content-blocks [request-id llm-response]
  (let [text-blocks (if (str/blank? (or (:content llm-response) ""))
                      []
                      [{:type :text :text (:content llm-response)}])
        tool-blocks (mapv (fn [[idx tool-call]]
                            (normalize-tool-call-block request-id idx tool-call))
                          (map-indexed vector (:tool-calls llm-response)))]
    (vec (concat text-blocks tool-blocks))))

(defn- emit-max-token-truncation! [sink base request-id llm-response]
  (let [content-blocks (llm-response-content-blocks request-id llm-response)
        content (llm-messages/content-text {:content content-blocks})
        metadata {:truncated true
                  :stop-reason (some-> (:stop-reason llm-response) name)
                  :usage (:usage llm-response)}]
    (when (seq content-blocks)
      (event! sink :message-end base {:role "assistant"
                                      :content content
                                      :content-blocks content-blocks
                                      :audit? true
                                      :excluded-from-context? true
                                      :metadata metadata
                                      :stop-reason :max-tokens}))
    (emit-terminal-message! sink base max-tokens-content {:stop-reason :max-tokens
                                                          :error-type :truncation
                                                          :metadata {:error-type :truncation}})))

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
            keep-tools (remove #(= :respond (keyword (:name (normalize-tool-call-block "respond" 0 %))))
                               (:tool-calls llm-response))]
        (-> step
            (assoc :directives (vec keep-directives))
            (assoc :llm-response (assoc llm-response :tool-calls (vec keep-tools)))))

      :else step)))

(defn- attach-allowed-tools [step allowed-tools]
  (if (seq allowed-tools)
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

(defn- fatal-guardrail! [sink base verdict step-no final-messages trace usage stream? request-id]
  (let [content (or (:content verdict) guardrail-exhausted-content)]
    (emit-terminal-message! sink base content {:stop-reason :guardrail-exhausted
                                               :metadata {:reason (some-> (:reason verdict) name)}})
    (event! sink :agent-end base {:steps (inc step-no)
                                  :stop-reason :guardrail-exhausted
                                  :stream stream?})
    {:content content
     :request-id request-id
     :final-messages (conj final-messages {:role "assistant"
                                           :content content})
     :trace trace
     :usage usage
     :stop-reason :guardrail-exhausted
     :stream? stream?
     :guardrail? true}))

(defn run!
  [{:keys [messages context-injectors system-prompt tools model provider-config
           telemetry observer trace planner-fn context-pack-fn execute-step-fn approval-fn fallback-fn event-sink
           cancellation-token request-id session-id agent-id max-steps stream?
           tool-output-max-chars doom-loop-config chat-profile]
    :or {planner-fn planner/plan-step!
         context-pack-fn identity
         max-steps 6
         tool-output-max-chars 8000
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
            {:content max-steps-content
             :request-id request-id
             :final-messages (conj final-messages {:role "assistant" :content max-steps-content})
             :trace trace
             :usage usage
             :stop-reason :max-steps
             :stream? stream?*})
          (let [_ (event! event-sink :turn-start base {:step step-no})
                _ (reset! delta-emitted? false)
                _ (discard-pending-deltas!)
                _ (event! event-sink :message-start base {:role "assistant" :step step-no})
                {planner-messages* :messages repairs :repairs} (normalize-chat-history planner-messages)
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
                                  :model model
                                  :system-prompt system-prompt
                                  :context-pack context-pack
                                  :tool-choice (when (and (:force-tool-choice? chat-profile*) (seq routed-tools))
                                                 "required")
                                  :on-content-delta on-content-delta})
                _ (throw-if-cancelled! cancellation-token)
                llm-response0 (:llm-response step)
                step* (-> step
                          (strip-respond-when-mixed llm-response0)
                          (attach-allowed-tools allowed-tools))
                executable-step (select-keys step* [:schema-version :state :directives :receipts])
                llm-response (:llm-response step*)
                usage* (usage+ usage (:usage llm-response))
                max-token? (max-token-stop-reason? (:stop-reason llm-response))
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

              max-token?
              (do
                (discard-pending-deltas!)
                (emit-max-token-truncation! event-sink base request-id llm-response)
                (event! event-sink :agent-end base {:steps (inc step-no)
                                                    :stop-reason :max-tokens
                                                    :stream stream?*})
                {:content max-tokens-content
                 :request-id request-id
                 :final-messages [{:role "assistant" :content max-tokens-content}]
                 :trace trace
                 :usage usage*
                 :stop-reason :max-tokens
                 :stream? stream?*
                 :error? true})

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
                                 :window-size (:window-size doom-loop-config*)
                                 :action (:action doom-loop-config*)}]
                    (discard-pending-deltas!)
                    (event! event-sink :tool-execution-update base payload)
                    (emit-terminal-message! event-sink base doom-loop-content {:stop-reason :doom-loop
                                                                               :metadata payload})
                    (event! event-sink :agent-end base {:steps (inc step-no)
                                                        :stop-reason :doom-loop
                                                        :stream stream?*})
                    {:content doom-loop-content
                     :request-id request-id
                     :final-messages (conj final-messages {:role "assistant" :content doom-loop-content})
                     :trace trace
                     :usage usage*
                     :stop-reason :doom-loop
                     :stream? stream?*
                     :guardrail? true
                     :doom-loop payload})
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
                        (event! event-sink :turn-end base {:step step-no
                                                           :directives (:directives step*)
                                                           :receipts receipts})
                        (let [provider-tool-calls (seq (:tool-calls llm-response))
                              protocol-messages (when provider-tool-calls
                                                  (emit-tool-turn! event-sink base request-id llm-response
                                                                   provider-tool-calls receipts tool-output-max-chars))
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
                               :final-messages (conj final-messages* {:role "assistant" :content content})
                               :trace trace*
                               :usage usage*
                               :stop-reason :completed
                               :stream? stream?*})
                            (let [approval-needed (vec (approval-receipts receipts))]
                              (if (seq approval-needed)
                                (let [approvals (if approval-fn (approval-fn approval-needed) approval-needed)
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
                                   :final-messages (conj final-messages* {:role "assistant" :content content})
                                   :trace trace*
                                   :usage usage*
                                   :stop-reason :approval-required
                                   :approvals approvals
                                   :stream? stream?*})
                                (recur (inc step-no)
                                       (merge state (:state executed))
                                       (into planner-messages* protocol-messages)
                                       trace*
                                       final-messages*
                                       usage*
                                       doom-loop-state*
                                       (nudge/record-execution nudge-state executable-step receipts)))))))))))))))
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
