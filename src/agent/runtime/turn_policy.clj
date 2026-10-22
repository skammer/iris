(ns agent.runtime.turn-policy
  "Pure normalization and execution decisions for a planned turn."
  (:require [agent.runtime.messages :as runtime-messages]))

(defn- directive-tool-name [directive]
  (keyword (get-in directive [:payload :tool-name])))

(defn- respond-directive? [directive]
  (and (= :tool-call (keyword (:type directive)))
       (= :respond (directive-tool-name directive))))

(defn- respond-content [directive]
  (or (get-in directive [:payload :input :content])
      (get-in directive [:payload :input "content"])
      ""))

(defn strip-respond-when-mixed [step llm-response]
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

(defn attach-allowed-tools [step allowed-tools]
  (if (some? allowed-tools)
    (update step :directives
            (fn [directives]
              (mapv (fn [directive]
                      (if (= :tool-call (keyword (:type directive)))
                        (update-in directive [:payload :context] merge {:allowed-tools allowed-tools})
                        directive))
                    directives)))
    step))

(defn next-action [{:keys [submission pre-verdict max-token-terminal?]}]
  (cond
    submission :submission
    (= :retry (:action pre-verdict)) :retry
    (= :fatal (:action pre-verdict)) :fatal
    max-token-terminal? :max-tokens
    :else :execute))

(defn initial-turn [messages doom-state nudge-state]
  {:step-no 0 :state {} :planner-messages messages :trace []
   :final-messages [] :usage {} :doom-loop-state doom-state :nudge-state nudge-state})

(defn advance
  "Pure next-turn construction, shared by pre/post retries and successful tool turns.
   Only emitted protocol enters final history; retry prompts remain planner-only."
  [turn {:keys [planner-messages protocol prompt trace-entry execution-state usage
                doom-loop-state nudge-state] :as changes}]
  (cond-> (-> turn
              (update :step-no inc)
              (update :state merge execution-state)
              (assoc :planner-messages (cond-> (into (or planner-messages (:planner-messages turn)) protocol)
                                         prompt (conj prompt)))
              (update :trace #(cond-> % trace-entry (conj trace-entry)))
              (update :final-messages into protocol))
    (contains? changes :usage) (assoc :usage usage)
    (contains? changes :doom-loop-state) (assoc :doom-loop-state doom-loop-state)
    (contains? changes :nudge-state) (assoc :nudge-state nudge-state)))

(defn result-verdict [nudge-state error]
  {:action (if (< (get nudge-state :result-retries 0) 2) :retry :fatal)
   :reason :invalid-result :content error})

(defn post-action [verdict]
  (case (:action verdict) :retry :retry :fatal :fatal :accept))

(defn terminal-action [complete-receipt pending-approvals]
  (cond complete-receipt :complete (seq pending-approvals) :approval :else :continue))
