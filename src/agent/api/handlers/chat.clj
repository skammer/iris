(ns agent.api.handlers.chat
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.streaming :as streaming]
   [agent.api.validation :as v]
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.config :as config]
   [agent.defaults :as defaults]
   [clojure.core.async :as async]))

(defn- complete! [system messages {:keys [session-id]}]
  (chat/run! system {:messages messages :session-id session-id}))

(defn- chat-stream-progress-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (contains? #{"agent-start"
                    "turn-start"
                    "turn-end"
                    "message-start"
                    "message-end"
                    "session-state-changed"
                    "turn-queued"
                    "tool-execution-start"
                    "tool-execution-update"
                    "tool-execution-end"}
                  (:event-type event))))

(defn- chat-stream-delta-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "message-update" (:event-type event))
       (string? (get-in event [:payload :delta]))))

(defn- chat-stream-terminal-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "agent-end" (:event-type event))))

(defn- openai-style-usage [usage]
  (when (map? usage)
    (let [prompt (long (or (:prompt-tokens usage) 0))
          completion (long (or (:completion-tokens usage) 0))]
      {:prompt_tokens prompt
       :completion_tokens completion
       :total_tokens (long (or (:tokens usage) (+ prompt completion)))
       :prompt_tokens_details {:cached_tokens (long (or (:cached-tokens usage) 0))}})))

(defn- openai-style-completion [system session-id content usage]
  (let [llm (config/active-provider-config (get-in system [:config :llm]))]
    (cond-> {:id (str "chatcmpl-" (System/currentTimeMillis))
             :object "chat.completion"
             :session_id session-id
             :provider (name (:provider llm))
             :model (:model llm)
             :choices [{:index 0
                        :finish_reason "stop"
                        :message {:role "assistant"
                                  :content content}}]}
      (map? usage) (assoc :usage (openai-style-usage usage)))))

(defn- stream-response
  [system request messages session-id]
  (let [stream-id (str "chatcmpl-" (System/currentTimeMillis))
        final-fallback-ms 1000
        llm (config/active-provider-config (get-in system [:config :llm]))
        provider (name (:provider llm))
        model (:model llm)
        broker-instance (or (:event-bus system) (:broker system))
        send-delta! (fn [channel delta]
                      (streaming/send-sse-chunk! channel
                                                 {:id stream-id
                                                  :object "chat.completion.chunk"
                                                  :session_id session-id
                                                  :provider provider
                                                  :model model
                                                  :choices [{:index 0
                                                             :delta {:content delta}
                                                             :finish_reason nil}]}))
        send-event! (fn [channel event]
                      (cond
                        (chat-stream-delta-event? event session-id)
                        (send-delta! channel (get-in event [:payload :delta]))

                        (chat-stream-progress-event? event session-id)
                        (streaming/send-sse-chunk! channel
                                                   {:id stream-id
                                                    :object "chat.progress"
                                                    :session_id session-id
                                                    :event (ser/event->response event)})))
        finish! (fn [ctx result-value]
                  (when (:error? (:result result-value))
                    (send-delta! ctx (get-in result-value [:result :content])))
                  (streaming/send-sse-chunk!
                   ctx
                   (cond-> {:id stream-id
                            :object "chat.completion.chunk"
                            :session_id session-id
                            :provider provider
                            :model model
                            :choices [{:index 0
                                       :delta {}
                                       :finish_reason "stop"}]}
                     (map? (get-in result-value [:result :usage]))
                     (assoc :usage (openai-style-usage (get-in result-value [:result :usage])))))
                  (streaming/send-sse-done! ctx))]
    (streaming/managed-response
	     request
	     {:name :chat-completions-stream
          :metrics (:sse-metrics system)
	      :on-error (fn [ctx _error]
                  (streaming/send-sse-error! ctx "stream_error" "Stream failed")
                  (streaming/send-sse-done! ctx))}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                               broker-instance
                                               (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             events-ch (:channel subscription)
             result-ch (streaming/run-task!
                        ctx
                        #(chat/run! system
                                    {:messages messages
                                     :session-id session-id
                                     :stream? true}))]
         (streaming/send-sse-chunk! ctx
                                    {:id stream-id
                                     :object "chat.completion.chunk"
                                     :session_id session-id
                                     :provider provider
                                     :model model
                                     :choices [{:index 0
                                                :delta {:role "assistant"}
                                                :finish_reason nil}]})
         (loop [result-value nil
                result-ch* result-ch
                terminal? false
                fallback-ch nil]
           (when (streaming/open? ctx)
             (let [ports (cond-> [events-ch]
                           result-ch* (conj result-ch*)
                           fallback-ch (conj fallback-ch))
                   [value port] (async/alts!! ports)]
               (cond
                 (= port result-ch*)
                 (if (:error value)
                   (do
                     (streaming/send-sse-error! ctx "stream_error" "Stream failed")
                     (streaming/send-sse-done! ctx))
                   (if terminal?
                     (finish! ctx value)
                     (recur value nil terminal? (async/timeout final-fallback-ms))))

                 (= port fallback-ch)
                 (finish! ctx result-value)

                 (= port events-ch)
                 (if-let [event (:payload value)]
                   (do
                     (send-event! ctx event)
                     (let [terminal?* (or terminal?
                                          (chat-stream-terminal-event? event session-id))]
                       (if (and result-value terminal?*)
                         (finish! ctx result-value)
                         (recur result-value result-ch* terminal?* fallback-ch))))
                   (when result-value
                     (finish! ctx result-value))))))))))))

(defn completions-response
  "Ring-style handler for POST /v1/chat/completions."
  [system request]
  (let [{:keys [messages session-id stream?]}
        (v/normalize-chat-request (h/read-json-body request))]
    (v/ensure-session-exists! system session-id)
    (if stream?
      (stream-response system request messages session-id)
      (let [result (complete! system messages {:session-id session-id})]
        (responses/json-response 200
                                 (openai-style-completion system
                                                          session-id
                                                          (:content result)
                                                          (:usage result)))))))

(defn stop-response
  [system request]
  (let [body (h/read-json-body request)
        session-id (:session_id body)]
    (v/ensure-session-exists! system session-id)
    (responses/json-response 200
                             {:data (chat/cancel-session! system session-id)})))
