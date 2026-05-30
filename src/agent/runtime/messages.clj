(ns agent.runtime.messages
  "LLM message repair and tool-protocol message construction."
  (:require
   [agent.defaults :as defaults]
   [agent.llm.messages :as llm-messages]
   [agent.runtime.schema :as runtime-schema]
   [cheshire.core :as json]
   [clojure.string :as str]))

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

(defn normalize-tool-call-block [request-id idx tool-call]
  (let [block (llm-messages/provider-tool-call->internal tool-call)]
    (cond-> block
      (not (:id block)) (assoc :id (str "call_" request-id "_" idx)))))

(defn assistant-tool-call-message [request-id content tool-calls]
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
  ([receipt] (tool-output-content receipt defaults/tool-output-max-chars))
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
   (tool-protocol-messages request-id content tool-calls receipts defaults/tool-output-max-chars))
  ([request-id content tool-calls receipts tool-output-max-chars]
   (let [tool-calls* (mapv (fn [[idx tool-call]]
                             (normalize-tool-call-block request-id idx tool-call))
                           (map-indexed vector tool-calls))]
     (into [(assistant-tool-call-message request-id content tool-calls)]
           (map #(tool-output-message %1 %2 tool-output-max-chars)
                tool-calls*
                receipts)))))

(defn tool-result-block [message]
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
