(ns agent.llm.core
  "Provider-neutral LLM contracts. Defines normalized invoke/stream/embed/model
   APIs, shared request/response helpers, provider errors, retries, and token
   estimation used above concrete provider implementations."
  (:require
   [agent.llm.messages :as llm-messages]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   [java.time ZonedDateTime]
   [java.time.format DateTimeFormatter]))

;; ======================
;; Extended LLM Protocol
;; ======================

(defprotocol ILLMProvider
  "Protocol for LLM providers with extended capabilities.
  `complete` and `stream` are convenience surfaces; providers should route
  them through ILLMProviderInvoke `invoke` via `complete-via-invoke` and
  `stream-via-invoke`."

  (complete [this messages opts]
    "Send messages to LLM and get completion.
    messages: vector of message maps with :role and :content
    opts: map with :model, :temperature, :max-tokens, etc.
    Returns: string completion")

  (stream [this messages opts]
    "Stream completion from LLM.
    Returns: core.async channel that will receive streaming chunks")
  
  (embed [this text opts]
    "Get embeddings for text.
    text: string or vector of strings
    opts: map with :model, etc.
    Returns: vector of embeddings")
  
  (list-models [this]
    "List available models from this provider.
    Returns: vector of model information maps")
  
  (get-capabilities [this model]
    "Get capabilities of a specific model.
    model: model identifier string
    Returns: map with :max-tokens, :supports-embedding, etc.")
  
  (estimate-cost [this messages model]
    "Estimate cost for completing messages with model.
    Returns: map with :tokens, :cost-usd, etc."))

(defprotocol ILLMProviderInvoke
  "Normalized expandable LLM request/response API."
  (invoke [this request]
    "Execute normalized request map.
    request keys: :messages, :model, :tools, :tool-choice, :structured-output,
    :cache-control, :modalities, :metadata.
    Returns normalized assistant turn map with :content, :tool-calls, :usage, :raw.")
  (generate [this messages opts]
    "Generate one assistant turn from messages and opts. Returns normalized response map."))

(defprotocol ILLMProviderWithHealth
  "Protocol for providers that support health checking."

  (health-check [this]
    "Check provider health.
    Returns: map with :healthy boolean and :details"))

;; ======================
;; Common Utilities
;; ======================

(defn count-tokens-estimate
  "Estimate token count for messages (rough approximation).
  Uses 4 chars per token as a rough estimate."
  [messages]
  (let [total-chars (reduce + (map #(count (llm-messages/content-text %)) messages))]
    (int (/ total-chars 4))))

(defn request->completion-opts
  [request]
  (merge
   (:opts request)
   (select-keys request
                [:model :api :temperature :max-tokens :top-p :frequency-penalty
                 :presence-penalty :tools :tool-choice :structured-output
                 :response-format :cache-control :cache_control :modalities
                 :metadata :extra-body :user :session-id :session_id
                 :stream? :stream :stream-structured-output?
                 :on-content-delta :on-thinking-delta])))

(defn normalize-llm-response
  [response opts]
  (let [response* (cond
                    (map? response) response
                    (string? response) {:content response}
                    :else {:content (str response)})
        turn (llm-messages/provider-response->assistant-turn
              (:provider opts)
              (:model opts)
              (cond-> response*
                (:usage opts) (assoc :usage (:usage opts))))
        tool-calls (llm-messages/tool-call-blocks (:content turn))]
    (-> response*
        (assoc :role "assistant")
        (assoc :content (llm-messages/content-text (:content turn)))
        (assoc :content-blocks (:content turn))
        (assoc :tool-calls tool-calls)
        (assoc :usage (:usage turn))
        (assoc :stop-reason (:stop-reason turn))
        (assoc :assistant-turn turn)
        (assoc :raw (or (:raw response*) response)))))

(declare llm-error stream-error-event)

(defn- parse-json-arguments [arguments]
  (if (string? arguments)
    (try
      (json/parse-string arguments true)
      (catch Exception _ arguments))
    arguments))

(defn- unwrap-arguments-envelope [value]
  (let [argument-key (cond
                       (and (map? value) (contains? value :arguments)) :arguments
                       (and (map? value) (contains? value "arguments")) "arguments")]
    (if (and argument-key (= 1 (count value)))
      (let [nested (parse-json-arguments (get value argument-key))]
        (if (map? nested) nested value))
      value)))

(defn- parse-tool-arguments [arguments]
  (let [parsed (parse-json-arguments arguments)
        unwrapped (unwrap-arguments-envelope parsed)]
    (cond
      (nil? unwrapped) {}
      (map? unwrapped) unwrapped
      :else {:arguments unwrapped})))

(defn tool-call->directive
  [tool-call]
  (let [block (llm-messages/provider-tool-call->internal tool-call)
        tool-name (:name block)
        input (parse-tool-arguments (:arguments block))]
    (when-not tool-name
      (throw (llm-error :invalid-tool-call
                        "Provider tool call missing tool name"
                        {:tool-call tool-call})))
    {:type :tool-call
     :payload {:tool-name tool-name
               :input input
               :context (cond-> {:provider-tool-call (or (:raw block) tool-call)}
                          (:id block) (assoc :provider-tool-call-id (:id block)))}}))

(defn tool-calls->directives
  [tool-calls]
  (mapv tool-call->directive (or tool-calls [])))

(defn default-invoke
  [provider {:keys [messages] :as request}]
  (let [opts (request->completion-opts request)
        result (complete provider messages opts)]
    (normalize-llm-response result opts)))

(extend-protocol ILLMProviderInvoke
  Object
  (invoke [this request]
    (default-invoke this request))
  (generate [this messages opts]
    (invoke this (assoc opts :messages messages))))

(defn stream-channel
  "Run f on a worker thread, passing it an emit! callback.
  Returns a core.async channel that receives emitted values, receives an
  :error event if f throws, and closes when f returns."
  [f]
  (let [ch (async/chan)]
    (async/thread
      (try
        (f #(async/>!! ch %))
        (catch Exception e
          (async/>!! ch (stream-error-event e)))
        (finally
          (async/close! ch))))
    ch))

(defn complete-via-invoke
  "Canonical `complete` implementation: routes through `invoke` and returns
  the assistant content string."
  [provider messages opts]
  (:content (invoke provider (assoc opts :messages messages :opts opts))))

(defn stream-via-invoke
  "Canonical `stream` implementation: routes through `invoke`, emitting
  content deltas onto the returned core.async channel."
  [provider messages opts]
  (stream-channel
   (fn [emit!]
     (invoke provider (assoc opts
                             :messages messages
                             :opts opts
                             :on-content-delta emit!)))))

;; ======================
;; Error Handling
;; ======================

(defn llm-error
  "Create an LLM error."
  ([type message] (llm-error type message {}))
  ([type message details]
   (ex-info message (merge {:type type} details))))

(defn stream-error-event
  [error]
  (let [details (when (instance? clojure.lang.ExceptionInfo error)
                  (select-keys (ex-data error)
                               [:type :status :retry-after :provider :model]))]
    (cond-> {:type :error
             :error (if (instance? Throwable error)
                      (.getMessage ^Throwable error)
                      (str error))}
      (seq details) (assoc :details details))))

(defn- retry-after-ms [error-data]
  (when-let [value (or (:retry-after error-data)
                       (get error-data "Retry-After")
                       (get error-data "retry-after")
                       (get-in error-data [:headers "Retry-After"])
                       (get-in error-data [:headers "retry-after"]))]
    (or (try
          (* 1000 (Long/parseLong (str/trim value)))
          (catch Exception _ nil))
        (try
          (let [retry-at (ZonedDateTime/parse value DateTimeFormatter/RFC_1123_DATE_TIME)
                now (ZonedDateTime/now)]
            (max 0 (.toMillis (java.time.Duration/between now retry-at))))
          (catch Exception _ nil)))))

(defn retryable-status?
  [status]
  (contains? #{429 503} status))

(defn- retryable-exception? [e]
  (retryable-status? (:status (ex-data e))))

(defn retry-with-backoff
  "Retry function with exponential backoff and Retry-After support."
  [f & {:keys [max-retries initial-delay max-delay]
        :or {max-retries 3 initial-delay 1000 max-delay 60000}}]
  (loop [retry 0
         delay initial-delay]
    (let [result (try
                   (f)
                   (catch Exception e
                     (if (or (>= retry max-retries)
                             (not (retryable-exception? e)))
                       (throw e)
                       e)))]
      (cond
        (not (instance? Exception result)) result
        :else (do
                (Thread/sleep (or (retry-after-ms (ex-data result))
                                  delay))
                (recur (inc retry)
                       (min (* delay 2) max-delay)))))))
