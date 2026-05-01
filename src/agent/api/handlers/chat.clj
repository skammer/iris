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
   [clojure.core.async :as async]
   [org.httpkit.server :as http-kit]))

(defn- complete! [system messages {:keys [session-id]}]
  (chat/run! system {:messages messages :session-id session-id}))

(defn- chat-stream-progress-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (contains? #{"chat.started"
                    "chat.memory.recalled"
                    "chat.planner.step"
                    "chat.tool.approval_required"
                    "chat.fallback_completion"
                    "tool.execution.succeeded"
                    "tool.execution.failed"}
                  (:event-type event))))

(defn- openai-style-completion [system session-id content]
  (let [llm (config/active-provider-config (get-in system [:config :llm]))]
    {:id (str "chatcmpl-" (System/currentTimeMillis))
     :object "chat.completion"
     :session_id session-id
     :provider (name (:provider llm))
     :model (:model llm)
     :choices [{:index 0
                :finish_reason "stop"
                :message {:role "assistant"
                          :content content}}]}))

(defn- stream-response
  [system request messages session-id]
  (let [stream-id (str "chatcmpl-" (System/currentTimeMillis))
        llm (config/active-provider-config (get-in system [:config :llm]))
        provider (name (:provider llm))
        model (:model llm)
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        events-ch (:channel subscription)
        delta-ch (async/chan 16)
        result-ch (async/chan 1)
        open? (atom true)]
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (streaming/send-sse-chunk! channel
                                      {:id stream-id
                                       :object "chat.completion.chunk"
                                       :session_id session-id
                                       :provider provider
                                       :model model
                                       :choices [{:index 0
                                                  :delta {:role "assistant"}
                                                  :finish_reason nil}]})
           (future
             (try
               (async/>!! result-ch
                          {:result (chat/run! system
                                              {:messages messages
                                               :session-id session-id
                                               :on-delta #(async/>!! delta-ch %)})})
               (catch Exception e
                 (async/>!! result-ch {:error e}))))
           (loop []
             (when @open?
               (let [[value port] (async/alts!! [result-ch delta-ch events-ch])]
                 (cond
                   (= port delta-ch)
                   (do
                     (when value
                       (streaming/send-sse-chunk! channel
                                                  {:id stream-id
                                                   :object "chat.completion.chunk"
                                                   :session_id session-id
                                                   :provider provider
                                                   :model model
                                                   :choices [{:index 0
                                                              :delta {:content value}
                                                              :finish_reason nil}]}))
                     (recur))

                   (= port result-ch)
                   (do
                     (if-let [error (:error value)]
                       (streaming/send-sse-chunk! channel
                                                  {:error "stream_error"
                                                   :message (.getMessage error)})
                       (do
                         (when (:error? (:result value))
                           (streaming/send-sse-chunk! channel
                                                      {:id stream-id
                                                       :object "chat.completion.chunk"
                                                       :session_id session-id
                                                       :provider provider
                                                       :model model
                                                       :choices [{:index 0
                                                                  :delta {:content (get-in value [:result :content])}
                                                                  :finish_reason nil}]}))
                         (streaming/send-sse-chunk! channel
                                                    {:id stream-id
                                                     :object "chat.completion.chunk"
                                                     :session_id session-id
                                                     :provider provider
                                                     :model model
                                                     :choices [{:index 0
                                                                :delta {}
                                                                :finish_reason "stop"}]})))
                     (streaming/send-sse-done! channel)
                     (http-kit/close channel))

                   (= port events-ch)
                   (do
                     (when-let [event (:payload value)]
                       (when (chat-stream-progress-event? event session-id)
                         (streaming/send-sse-chunk! channel
                                                    {:id stream-id
                                                     :object "chat.progress"
                                                     :session_id session-id
                                                     :event (ser/event->response event)})))
                     (recur))))))
           (catch Exception e
             (streaming/send-sse-chunk! channel
                                        {:error "stream_error"
                                         :message (.getMessage e)}))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

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
                                 (openai-style-completion system session-id (:content result)))))))

(defn stop-response
  [system request]
  (let [body (h/read-json-body request)
        session-id (:session_id body)]
    (v/ensure-session-exists! system session-id)
    (responses/json-response 200
                             {:data (chat/cancel-session! session-id)})))
