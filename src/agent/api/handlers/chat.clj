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
        subscription (broker/subscribe! broker-instance
                                        (broker/all-events-subject)
                                        {:buffer-size 256
                                         :buffer-strategy :sliding
                                         :slow-client :drop-new})
        events-ch (:channel subscription)
        result-ch (async/chan 1)
        open? (atom true)
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
                                                    :event (ser/event->response event)})))]
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
                                               :stream? true})})
               (catch Exception e
                 (async/>!! result-ch {:error e}))))
           (loop [result-value nil
                  terminal? false]
             (when @open?
               (let [[value port] (async/alts!! [result-ch events-ch])]
                 (cond
                   (= port result-ch)
                   (if-let [error (:error value)]
                     (do
                       (streaming/send-sse-chunk! channel
                                                  {:error "stream_error"
                                                   :message (.getMessage error)})
                       (streaming/send-sse-done! channel)
                       (http-kit/close channel))
                     (if terminal?
                       (do
                         (when (:error? (:result value))
                           (send-delta! channel (get-in value [:result :content])))
                         (streaming/send-sse-chunk! channel
                                                    {:id stream-id
                                                     :object "chat.completion.chunk"
                                                     :session_id session-id
                                                     :provider provider
                                                     :model model
                                                     :choices [{:index 0
                                                                :delta {}
                                                                :finish_reason "stop"}]})
                         (streaming/send-sse-done! channel)
                         (http-kit/close channel))
                       (recur value terminal?)))

                   (= port events-ch)
                   (do
                     (when-let [event (:payload value)]
                       (send-event! channel event)
                       (let [terminal?* (or terminal?
                                            (chat-stream-terminal-event? event session-id))]
                         (if (and result-value terminal?*)
                           (do
                             (when (:error? (:result result-value))
                               (send-delta! channel (get-in result-value [:result :content])))
                             (streaming/send-sse-chunk! channel
                                                        {:id stream-id
                                                         :object "chat.completion.chunk"
                                                         :session_id session-id
                                                         :provider provider
                                                         :model model
                                                         :choices [{:index 0
                                                                    :delta {}
                                                                    :finish_reason "stop"}]})
                             (streaming/send-sse-done! channel)
                             (http-kit/close channel))
                           (recur result-value terminal?*)))))))))
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
                             {:data (chat/cancel-session! system session-id)})))
