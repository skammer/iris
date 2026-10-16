(ns agent.api.handlers.ui
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.defaults :as defaults]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.trace :as runtime-trace]
   [agent.sessions.service :as session-service]
   [agent.ui :as ui]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [agent.ui.render]
   [agent.api.handlers.ui.approvals :as approvals]
   [agent.api.handlers.ui.cron :as cron]
   [agent.api.handlers.ui.memory :as memory]
   [agent.api.handlers.ui.params :as params])
  (:import
   (java.nio.file Files)
   (java.util Base64)))

(def cron cron/cron)
(def cron-status cron/cron-status)
(def cron-job-detail cron/cron-job-detail)
(def cron-run-detail cron/cron-run-detail)
(def cron-create cron/cron-create)
(def cron-preview cron/cron-preview)
(def cron-action cron/cron-action)
(def memory-workspace memory/memory-workspace)
(def memory-vault-detail memory/memory-vault-detail)
(def memory-update-detail memory/memory-update-detail)
(def memory-search memory/memory-search)
(def memory-tool-run memory/memory-tool-run)
(def memory-vault-reindex memory/memory-vault-reindex)
(def memory-vault-status memory/memory-vault-status)
(def memory-vault-magi memory/memory-vault-magi)
(def memory-vault-magi-update memory/memory-vault-magi-update)
(def memory-proposal-decision memory/memory-proposal-decision)
(def list-tool-approvals approvals/list-tool-approvals)
(def tool-approvals-status approvals/tool-approvals-status)
(def tool-approval-detail approvals/tool-approval-detail)
(def tool-approval-decision approvals/tool-approval-decision)
(def tool-approval-run approvals/tool-approval-run)

(def ^:private max-chat-image-bytes (* 10 1024 1024))

(defonce ^:private ui-session-streams (atom {}))

(defonce ^:private ui-chat-posts (atom {}))

(defn- owns-chat-stream? [client-id session-id]
  (pos? (get @ui-chat-posts [client-id session-id] 0)))

(defn- own-chat-stream! [ctx client-id session-id]
  (when-not (str/blank? client-id)
    (let [key [client-id session-id]]
      (swap! ui-chat-posts update key (fnil inc 0))
      (streaming/register-cleanup!
       ctx
       #(swap! ui-chat-posts
               (fn [owners]
                 (if (> (get owners key 0) 1)
                   (update owners key dec)
                   (dissoc owners key))))))))

(defn- close-ui-session-stream! [client-id]
  (when-let [ctx (and (not (str/blank? client-id))
                      (get @ui-session-streams client-id))]
    (swap! ui-session-streams dissoc client-id)
    (streaming/close! ctx)))

(defn- history-session [client-id]
  (some-> (get @ui-session-streams client-id) :history-session deref))

(defn- set-history-session! [client-id session-id]
  (when-let [state (:history-session (get @ui-session-streams client-id))]
    (reset! state session-id)))

(defn- register-ui-session-stream! [client-id ctx]
  (when-not (str/blank? client-id)
    (let [ctx (assoc ctx :history-session
                     (or (:history-session (get @ui-session-streams client-id)) (atom nil)))]
      (close-ui-session-stream! client-id)
      (swap! ui-session-streams assoc client-id ctx)
      (streaming/register-cleanup!
       ctx
       #(swap! ui-session-streams
               (fn [streams]
                 (if (identical? ctx (get streams client-id))
                   (dissoc streams client-id)
                   streams)))))))

(defn- uploaded-files [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn- image-upload->block [{:keys [filename content-type tempfile size] :as upload}]
  (when (and (map? upload)
             (not (str/blank? (or filename "")))
             tempfile)
    (let [media-type (or content-type "application/octet-stream")
          size* (long (or size (.length tempfile)))]
      (when-not (str/starts-with? (str/lower-case media-type) "image/")
        (throw (errors/api-error 400 "bad_request" "Only image uploads are supported.")))
      (when (> size* max-chat-image-bytes)
        (throw (errors/api-error 400 "bad_request" "Image upload exceeds 10MB.")))
      {:type :image
       :source {:type :base64
                :media-type media-type
                :value (.encodeToString (Base64/getEncoder)
                                        (Files/readAllBytes (.toPath tempfile)))}
       :filename filename
       :alt filename})))

(defn- chat-content [prompt image]
  (let [prompt* (str/trim (or prompt ""))
        image-blocks (keep image-upload->block (uploaded-files image))
        blocks (cond-> []
                 (not (str/blank? prompt*)) (conj {:type :text :text prompt*})
                 (seq image-blocks) (into image-blocks))]
    (when (empty? blocks)
      (throw (errors/api-error 400 "bad_request" "Expected prompt or image.")))
    (if (seq image-blocks)
      blocks
      prompt*)))

(defn shell [system request]
  (responses/html-response 200
                           (let [query (-> request :parameters :query)]
                             (ui/shell-fragment system
                                                {:tab (some-> (:tab query) keyword)
                                                 :session-id (:session_id query)}))))

(defn route [system request]
  (let [query (-> request :parameters :query)]
    (when-not (= "chat" (some-> (:tab query) str/lower-case))
      (close-ui-session-stream! (:client_id query)))
    (responses/html-response 200
                             (ui/route-fragment system
                                                {:tab (some-> (:tab query) keyword)
                                                 :session-id (:session_id query)}))))

(defn dashboard [system _request]
  (responses/html-response 200 (ui/dashboard-fragment system)))

(defn operator-board [system _request]
  (responses/html-response 200 (ui/operator-board-fragment system)))

(defn magi [system request]
  (let [limit (params/request-ui-limit request 25 200)]
    (responses/html-response 200 (ui/magi-fragment system {:limit limit}))))

(defn magi-detail [system _request event-id]
  (responses/html-response
   200
   (ui/magi-detail-fragment
    (when-let [id (parse-long event-id)]
      (sqlite/get-event (:store system) id)))))

(defn sessions [system request]
  (responses/html-response 200
                           (ui/sessions-fragment system
                                                 (-> request :parameters :query :session_id)
                                                 {:offset (-> request :parameters :query :offset)})))

(defn session-projects [system request]
  (responses/html-response 200 (ui/session-projects-fragment system (-> request :parameters :query :prefix))))

(defn create-session [system request]
  (let [{:keys [project_id]} (h/read-form-body request)
        session (session-service/create-session! system nil {:project-id project_id})]
	    (streaming/once-response
	     request
         {:metrics (:sse-metrics system)}
	     (fn [ctx]
       (streaming/send-datastar-patch!
        ctx
        (str (ui/sessions-fragment system (:id session))
             (ui/session-detail-fragment system (:id session))
             (ui/dashboard-fragment system)
             (ui/router-state-fragment (ui/session-route-path system (:id session)))))))))

(defn session-project [system request]
  (let [{:keys [session_id project_id]} (h/read-form-body request)]
    (h/ensure-session-exists! system session_id)
    (session-service/set-project! system session_id project_id)
    (streaming/once-response
     request
     {:metrics (:sse-metrics system)}
     (fn [ctx]
       (streaming/send-datastar-patch!
        ctx
        (str (ui/sessions-fragment system session_id)
             (ui/session-detail-fragment system session_id)))))))

(defn session-detail [system request]
  (let [session-id (-> request :parameters :query :session_id)]
    (responses/html-response 200
                             (str (ui/sessions-fragment system session-id {:offset (-> request :parameters :query :offset)})
                                  (ui/session-detail-fragment system session-id)
                                  (ui/router-state-fragment
                                   (ui/session-route-path system session-id))))))

(defn session-messages [system request]
  (let [query (-> request :parameters :query)
        session-id (:session_id query)]
    (h/ensure-session-exists! system session-id)
    (let [html (ui/session-messages-fragment system session-id (select-keys query [:before :after]))]
      (set-history-session! (:client_id query) (when (or (:before query) (:after query)) session-id))
      (responses/html-response 200 html))))

(defn chat-tool-detail [system request]
  (let [{:keys [session_id message_id tool_call_id]} (-> request :parameters :query)]
    (h/ensure-session-exists! system session_id)
    (responses/html-response
     200
     (agent.ui.render/tool-detail-fragment
      (sqlite/tool-detail-messages (:store system) session_id message_id tool_call_id)
      message_id
      tool_call_id))))

(defn- transcript-changed-event? [event session-id]
  (and (= "session" (:entity-type event)) (= session-id (:entity-id event))
       (contains? #{"agent-start" "message-end" "message.updated" "turn-queued"}
                  (:event-type event))))

(defn- status-changed-event? [event session-id]
  (and (= "session" (:entity-type event)) (= session-id (:entity-id event))
       (contains? #{"session-state-changed" "agent-end"} (:event-type event))))

(defn- terminal-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "agent-end" (:event-type event))))

(defn- message-stream-update-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "message-update" (:event-type event))
       (or (string? (get-in event [:payload :delta]))
           (string? (get-in event [:payload :thinking-delta])))))

(defn- stream-ending-message-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "message-end" (:event-type event))
       (let [payload (:payload event)]
         (or (:final? payload)
             (:tool-turn? payload)))))

(defn- title-updated-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (= "session.title.updated" (:event-type event))))

(defn session-live-response
  [system request]
  (let [query (-> request :parameters :query)
        session-id (:session_id query)
        client-id (:client_id query)
        broker-instance (or (:event-bus system) (:broker system))]
    (h/ensure-session-exists! system session-id)
	    (streaming/managed-response
	     request
	     {:name :ui-session-live
          :metrics (:sse-metrics system)
	      :on-error (fn [_ _] nil)}
     (fn [ctx]
       (register-ui-session-stream! client-id ctx)
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)
             heartbeat-ms 5000]
         ;; Coalesce token bursts into at most 20 paints/s. A fixed deadline
         ;; prevents continuous input from postponing delivery indefinitely.
         (loop [pending nil]
           (when (streaming/open? ctx)
             (let [deadline (or pending (async/timeout heartbeat-ms))
                   [value source] (async/alts!! [deadline ch] :priority true)]
               (cond
                 (= source deadline)
                 (when (if (and pending (not= session-id (history-session client-id)) (not (owns-chat-stream? client-id session-id)))
                         (streaming/send-datastar-patch!
                          ctx (ui/session-streaming-fragment system session-id))
                         (streaming/send-sse-text! ctx ":\n\n"))
                   (recur nil))

                 value
                 (let [event (:payload value)]
                   (cond
                     (message-stream-update-event? event session-id)
                     (recur (if (or (= session-id (history-session client-id))
                                    (owns-chat-stream? client-id session-id))
                              pending (or pending (async/timeout 50))))

                     (and (or (title-updated-event? event session-id)
                              (and (not= session-id (history-session client-id)) (transcript-changed-event? event session-id))
                              (status-changed-event? event session-id))
                          (not (owns-chat-stream? client-id session-id)))
                     (when (streaming/send-datastar-patch!
                            ctx
                            (cond
                              (title-updated-event? event session-id) (ui/session-title-fragments system session-id)
                              (transcript-changed-event? event session-id) (ui/session-messages-fragment system session-id)
                              (status-changed-event? event session-id) (ui/session-status-fragment system session-id)
                              :else ""))
                       (recur nil))

                     :else (recur pending))))))))))))

(defn chat-action [system request]
  (let [body (h/read-form-body request)
        {:keys [session_id client_id prompt image]} body
        content (try
                  (chat-content prompt image)
                  (catch Exception e
                    ;; Forensics for empty-prompt 400s: record what the form
                    ;; actually delivered (keys + sizes, never content).
                    (when-let [event-sink (:event-sink system)]
                      (event-sink {:event-type :ui.chat.bad-request
                                   :entity-type :session
                                   :entity-id (str session_id)
                                   :payload {:form-keys (mapv name (keys body))
                                             :prompt-chars (count (str prompt))
                                             :content-type (get-in request [:headers "content-type"])}}))
                    (throw e)))]
    (h/ensure-session-exists! system session_id)
	    (streaming/managed-response
	     request
	     {:name :ui-chat
          :metrics (:sse-metrics system)
	      :on-error (fn [ctx _]
                  (streaming/send-datastar-patch!
                   ctx
                   (if (= session_id (history-session client_id))
                     (ui/session-status-fragment system session_id)
                     (ui/session-messages-fragment system session_id))))}
     (fn [ctx]
       (own-chat-stream! ctx client_id session_id)
       (set-history-session! client_id nil)
       (let [broker-instance (or (:event-bus system) (:broker system))
             final-fallback-ms 1000
             subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)
             streaming-state (atom {})
             push! (fn
                     ([]
                      (when-not (= session_id (history-session client_id))
                        (streaming/send-datastar-patch! ctx (ui/session-messages-fragment system session_id))))
                     ([streaming]
                      (when-not (= session_id (history-session client_id))
                        (streaming/send-datastar-patch! ctx (ui/session-streaming-fragment system session_id {:streaming streaming})))))
             final-pushed? (atom false)
             push-final! (fn []
                           (when-not @final-pushed?
                             (reset! final-pushed? true)
                             (push!)))
             result-ch (streaming/run-task!
                        ctx
                        #(chat/run! system
                                    {:messages [{:role "user" :content content}]
                                     :session-id session_id
                                     :stream? true}))]
         (streaming/send-datastar-patch! ctx (ui/session-status-fragment system session_id))
         (loop [done? false
                result-ch* result-ch
                terminal? false
                fallback-ch nil]
           (when-not (and done? terminal?)
             (let [ports (cond-> [ch]
                           result-ch* (conj result-ch*)
                           fallback-ch (conj fallback-ch))
                   [value port] (async/alts!! ports)]
               (cond
                 (= port result-ch*)
                 (if-let [error (:error value)]
                   (throw error)
                   (if terminal?
                     (push-final!)
                     (recur true nil terminal? (async/timeout final-fallback-ms))))

                 (= port fallback-ch)
                 (push-final!)

                 (= port ch)
                 (if-let [event (:payload value)]
                   (let [terminal?* (or terminal?
                                        (terminal-session-event? event session_id))]
                     (cond
                       (message-stream-update-event? event session_id)
                       (let [{:keys [delta thinking-delta]} (:payload event)]
                         (reset! final-pushed? false)
                         (push! (swap! streaming-state
                                       (fn [state]
                                         (cond-> state
                                           (string? delta) (update :content (fnil str "") delta)
                                           (string? thinking-delta) (update :thinking (fnil str "") thinking-delta))))))

                       (stream-ending-message-event? event session_id)
                       (do
                         (reset! streaming-state {})
                         (push-final!))

                       (title-updated-event? event session_id)
                       (streaming/send-datastar-patch!
                        ctx
                        (ui/session-title-fragments system session_id))

                       (transcript-changed-event? event session_id)
                       (push!)

                       (status-changed-event? event session_id)
                       (streaming/send-datastar-patch! ctx (ui/session-status-fragment system session_id)))
                     (if (and done? terminal?*)
                       (push-final!)
                       (recur done? result-ch* terminal?* fallback-ch)))
                   (when done?
                     (push-final!))))))))))))

(defn chat-stop [system request]
  (let [{:keys [session_id client_id]} (h/read-form-body request)]
    (h/ensure-session-exists! system session_id)
    (set-history-session! client_id nil)
    (chat/cancel-session! system session_id)
    (responses/html-response 200
                             (str (ui/session-messages-fragment system session_id)
                                  "<div id=\"chat-status\" class=\"meta chat-status\">stopping...</div>"))))

(defn system-reload [system request]
  (let [body (h/read-form-body request)
        mode (keyword (or (:mode body) "soft"))
        control (:system-control system)]
    ((:reload! control) system {:mode mode :source "ui"})
    (responses/html-response 200 (ui/dashboard-fragment ((:current-system control) system)))))

(defn events [system _request]
  (responses/html-response 200 (ui/events-fragment system)))

(defn logs [system request]
  (let [limit (params/request-ui-limit request 20 200)]
    (responses/html-response 200 (ui/logs-fragment system {:limit limit}))))

(defn log-detail [system _request source entry-id]
  (let [source* (keyword source)
        record (case source*
                 :event (when-let [id (parse-long entry-id)]
                          (sqlite/get-event (:store system) id))
                 :trace (->> (runtime-trace/load-events
                              (:trace system)
                              {:limit (or (get-in system [:trace :rolling-max-entries]) 1000)})
                             (some #(when (= entry-id (:id %)) %)))
                 nil)]
    (responses/html-response 200 (ui/log-detail-fragment source* record))))

(defn events-live-response
  [system request]
  (let [broker-instance (or (:event-bus system) (:broker system))]
	    (streaming/managed-response
	     request
	     {:name :ui-events-live
          :metrics (:sse-metrics system)
	      :on-error (fn [_ _] nil)}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)]
         (streaming/send-datastar-patch! ctx (ui/events-fragment system))
         (loop []
           (when (streaming/take! ctx ch)
             (streaming/send-datastar-patch! ctx (ui/events-fragment system))
            (recur))))))))
