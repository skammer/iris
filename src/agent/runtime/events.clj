(ns agent.runtime.events
  "Runtime event construction helpers for the chat-agent loop."
  (:require
   [agent.llm.messages :as llm-messages]
   [agent.runtime.messages :as runtime-messages]
   [agent.runtime.schema :as runtime-schema]
   [agent.util :as util]
   [clojure.string :as str]))

(defn emit!
  [sink event-type base payload]
  (let [event (runtime-schema/validate-runtime-event!
               (merge base
                      {:event-type event-type
                       :timestamp (util/now-str)}
                      (when (some? payload)
                        {:payload payload})))]
    (util/emit! sink event)))

(defn emit-message-delta!
  [sink base delta]
  (when (and (string? delta) (not= "" delta))
    (emit! sink
           :message-update
           base
           {:role "assistant"
            :delta delta
            :append? true})))

(defn emit-thinking-delta!
  [sink base delta]
  (when (and (string? delta) (not= "" delta))
    (emit! sink
           :message-update
           base
           {:role "assistant"
            :thinking-delta delta
            :append? true})))

(defn emit-terminal-message!
  [sink base content final-payload]
  (when-not (str/blank? (or content ""))
    (emit! sink :message-update base {:role "assistant"
                                      :delta content
                                      :append? true
                                      :synthetic? true}))
  (emit! sink :message-end base (merge {:role "assistant"
                                        :content content
                                        :content-blocks [{:type :text :text (or content "")}]
                                        :final? true}
                                       final-payload)))

(defn max-token-stop-reason? [reason]
  (contains? #{"length" "max_tokens" "max-tokens" "max_tokens_reached" "max-output-tokens"}
             (some-> reason name str/lower-case)))

(defn- llm-response-content-blocks [request-id llm-response]
  (let [text-blocks (if (str/blank? (or (:content llm-response) ""))
                      []
                      [{:type :text :text (:content llm-response)}])
        tool-blocks (mapv (fn [[idx tool-call]]
                            (runtime-messages/normalize-tool-call-block request-id idx tool-call))
                          (map-indexed vector (:tool-calls llm-response)))]
    (vec (concat text-blocks tool-blocks))))

(defn emit-max-token-truncation!
  [sink base request-id llm-response]
  (let [content-blocks (llm-response-content-blocks request-id llm-response)
        content (llm-messages/content-text {:content content-blocks})
        metadata {:truncated true
                  :stop-reason (some-> (:stop-reason llm-response) name)
                  :usage (:usage llm-response)}]
    (when (seq content-blocks)
      (emit! sink :message-end base {:role "assistant"
                                     :content content
                                     :content-blocks content-blocks
                                     :audit? true
                                     :excluded-from-context? true
                                     :metadata metadata
                                     :stop-reason :max-tokens}))
    (emit-terminal-message! sink base runtime-messages/max-tokens-content
                            {:stop-reason :max-tokens
                             :error-type :truncation
                             :metadata {:error-type :truncation}})))

(defn emit-tool-turn!
  [sink base request-id llm-response tool-calls receipts tool-output-max-chars]
  (let [protocol-messages (runtime-messages/tool-protocol-messages request-id
                                                                   (:content llm-response)
                                                                   tool-calls
                                                                   receipts
                                                                   tool-output-max-chars)
        assistant-msg (first protocol-messages)
        tool-msgs (vec (rest protocol-messages))]
    (emit! sink :message-end base (cond-> {:role "assistant"
                                           :content (llm-messages/content-text assistant-msg)
                                           :content-blocks (:content assistant-msg)
                                           :tool-calls (llm-messages/message-tool-calls assistant-msg)
                                           :tool-turn? true}
                                    (:usage llm-response) (assoc :metadata {:usage (:usage llm-response)})))
    (doseq [tool-msg tool-msgs]
      (let [tool-result (runtime-messages/tool-result-block tool-msg)]
        (emit! sink :message-end base {:role "tool"
                                       :content (:content tool-result)
                                       :content-blocks (:content tool-msg)
                                       :tool-call-id (:tool-call-id tool-result)
                                       :tool-turn? true})))
    protocol-messages))
