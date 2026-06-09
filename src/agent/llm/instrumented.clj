(ns agent.llm.instrumented
  "LLM invocation wrapped with telemetry/observer/runtime-trace emission.
   Sits above agent.llm.core and agent.telemetry so neither depends on the
   other: llm.core stays provider-only, telemetry stays a passive collector."
  (:require
   [agent.llm.core :as llm-core]
   [agent.runtime.trace :as runtime-trace]
   [agent.telemetry :as telemetry]
   [agent.telemetry.observer :as telemetry-observer]
   [agent.util :as util]))

(defn- safe-estimate-cost [provider messages model]
  (try
    (llm-core/estimate-cost provider messages model)
    (catch Exception _ nil)))

(defn usage-estimate [provider messages completion opts]
  (let [model (:model opts)
        estimate (safe-estimate-cost provider messages model)
        prompt-tokens (or (:prompt-tokens estimate)
                          (:prompt_tokens estimate)
                          (llm-core/count-tokens-estimate messages))
        completion-tokens (llm-core/count-tokens-estimate [{:role "assistant"
                                                            :content (or completion "")}])
        cached-tokens (or (:cached-tokens estimate) 0)
        total-tokens (or (:tokens estimate)
                         (+ prompt-tokens completion-tokens))]
    {:model model
     :prompt-tokens prompt-tokens
     :completion-tokens completion-tokens
     :cached-tokens cached-tokens
     :tokens total-tokens
     :cost-usd (:cost-usd estimate)}))

(defn- turn-usage->observation
  "Map the provider-reported usage on a normalized turn to the keys
   record-llm-call! consumes. Returns only the keys that are actually present,
   so callers can merge it over an estimate and keep the estimate for any gaps.
   Tolerates kebab- and snake-case provider variants."
  [usage]
  (when (map? usage)
    (let [prompt (or (:prompt-tokens usage) (:prompt_tokens usage))
          completion (or (:completion-tokens usage) (:completion_tokens usage))
          total (or (:tokens usage) (:total-tokens usage) (:total_tokens usage))
          cached (or (:cached-tokens usage)
                     (:cached_tokens usage)
                     (get-in usage [:prompt-tokens-details :cached-tokens])
                     (get-in usage [:prompt_tokens_details :cached_tokens])
                     (get-in usage [:input_tokens_details :cached_tokens])
                     (get-in usage [:cache_tokens_details :cached_tokens])
                     (:cache_read_input_tokens usage)
                     (:prompt_cache_read_tokens usage)
                     (:prompt_cache_hit_tokens usage))]
      (cond-> {}
        prompt (assoc :prompt-tokens prompt)
        completion (assoc :completion-tokens completion)
        total (assoc :tokens total)
        cached (assoc :cached-tokens cached)))))

(defn complete-with-telemetry!
  [collector provider messages opts attrs]
  (let [start-ns (System/nanoTime)
        observer (:observer attrs)
        trace (:trace attrs)
        attrs* (dissoc attrs :observer :trace)
        opts* (merge (select-keys attrs* [:model :user :session-id :session_id]) opts)
        observe! (fn [observation]
                   (if observer
                     (do
                       (telemetry-observer/record-event! observer {:event-type :llm/call
                                                                   :payload observation})
                       (telemetry-observer/record-metric! observer {:metric-type :request-latency-ms
                                                                    :component :llm
                                                                    :value (:duration-ms observation)})
                       (when (:tokens observation)
                         (telemetry-observer/record-metric! observer {:metric-type :tokens
                                                                      :component :llm
                                                                      :value (:tokens observation)})))
                     (telemetry/record-llm-call! collector observation))
                   (runtime-trace/record-event!
                    trace
                    {:event-type :llm.call
                     :turn-id (:request-id attrs*)
                     :provider (:provider attrs*)
                     :model (:model observation)
                     :success (not (false? (:success? observation)))
                     :error-message (some-> (:error observation) .getMessage)
                     :payload (select-keys observation
                                           [:agent-id :duration-ms :tokens
                                            :prompt-tokens :completion-tokens
                                            :cached-tokens :cost-usd
                                            :tool-calls :stop-reason])}))]
    (try
      ;; Route through invoke (not complete) so tool calls and provider-reported
      ;; usage survive. complete returns string-only content, dropping both.
      (let [turn (llm-core/invoke provider (assoc opts* :messages messages))
            content (or (:content turn) "")
            tool-calls (or (:tool-calls turn) [])
            usage (merge (usage-estimate provider messages content opts*)
                         (turn-usage->observation (:usage turn)))
            observation (merge attrs*
                               usage
                               {:duration-ms (util/duration-ms start-ns)
                                :success? true
                                :tool-calls tool-calls
                                :stop-reason (:stop-reason turn)})]
        (observe! observation)
        ;; Preserve the string-content contract every caller relies on.
        content)
      (catch Exception e
        (let [observation (merge attrs*
                                 (usage-estimate provider messages "" opts*)
                                 {:duration-ms (util/duration-ms start-ns)
                                  :success? false
                                  :error e})]
          (observe! observation))
        (throw e)))))
