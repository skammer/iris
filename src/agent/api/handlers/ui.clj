(ns agent.api.handlers.ui
  (:require
   [agent.api.errors :as errors]
   [agent.runs.service :as runs]
   [agent.api.handlers.tool-approvals :as approvals]
   [agent.api.handlers.tools :as tools-h]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.streaming :as streaming]
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.defaults :as defaults]
   [agent.memory.core :as memory]
   [agent.sessions.service :as session-service]
   [agent.system.events :as events]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.nio.file Files)
   (java.util Base64)))

(defn- ui-tool-input [body]
  (tools-h/tool-input-from-map (keyword (:tool body)) body))

(def ^:private max-chat-image-bytes (* 10 1024 1024))

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
                                                 :session-id (:session_id query)
                                                 :run-id (:run_id query)}))))

(defn dashboard [system _request]
  (responses/html-response 200 (ui/dashboard-fragment system)))

(defn operator-board [system _request]
  (responses/html-response 200 (ui/operator-board-fragment system)))

(defn sessions [system request]
  (responses/html-response 200
                           (ui/sessions-fragment system
                                                 (-> request :parameters :query :session_id))))

(defn create-session [system request]
  (let [body (h/read-form-body request)
        title (:title body)
        session (session-service/create-session! system (not-empty title))]
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

(defn session-detail [system request]
  (let [session-id (-> request :parameters :query :session_id)]
    (responses/html-response 200
                             (str (ui/sessions-fragment system session-id)
                                  (ui/session-detail-fragment system session-id)
                                  (ui/router-state-fragment
                                   (ui/session-route-path system session-id))))))

(defn session-messages [system request]
  (let [session-id (-> request :parameters :query :session_id)]
    (h/ensure-session-exists! system session-id)
    (responses/html-response 200
                             (ui/session-messages-fragment system session-id))))

(defn- relevant-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (or (contains? #{"message.updated"
                        "session.created"
                        "session-state-changed"
                        "turn-queued"}
                      (:event-type event))
           (contains? #{"agent-start"
                        "agent-end"
                        "turn-start"
                        "turn-end"
                        "message-start"
                        "message-update"
                        "message-end"
                        "tool-execution-start"
                        "tool-execution-update"
                        "tool-execution-end"}
                      (:event-type event)))))

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

(defn session-live-response
  [system request]
  (let [session-id (-> request :parameters :query :session_id)
        broker-instance (or (:event-bus system) (:broker system))]
    (h/ensure-session-exists! system session-id)
	    (streaming/managed-response
	     request
	     {:name :ui-session-live
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
         (streaming/send-datastar-patch! ctx (ui/session-messages-fragment system session-id))
         (loop []
           (when-let [event (some-> (streaming/take! ctx ch) :payload)]
             (when (relevant-session-event? event session-id)
               (streaming/send-datastar-patch! ctx
                                               (ui/session-messages-fragment system session-id)))
            (recur))))))))

(defn chat-action [system request]
  (let [{:keys [session_id prompt image]} (h/read-form-body request)
        content (chat-content prompt image)]
    (h/ensure-session-exists! system session_id)
	    (streaming/managed-response
	     request
	     {:name :ui-chat
          :metrics (:sse-metrics system)
	      :on-error (fn [ctx _]
                  (streaming/send-datastar-patch!
                   ctx
                   (ui/session-messages-fragment system session_id)))}
     (fn [ctx]
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
                      (streaming/send-datastar-patch!
                       ctx
                       (ui/session-messages-fragment system session_id)))
                     ([streaming]
                      (streaming/send-datastar-patch!
                       ctx
                       (ui/session-messages-fragment system
                                                     session_id
                                                     {:streaming streaming}))))
             final-pushed? (atom false)
             push-final! (fn []
                           (when-not @final-pushed?
                             (reset! final-pushed? true)
                             (push!)))
             push-delta! (fn [delta]
                           (when-not (str/blank? (str delta))
                             (push! (swap! streaming-state
                                           update :content (fnil str "") delta))))
             push-thinking! (fn [delta]
                              (when-not (str/blank? (str delta))
                                (push! (swap! streaming-state
                                              update :thinking (fnil str "") delta))))
             result-ch (streaming/run-task!
                        ctx
                        #(chat/run! system
                                    {:messages [{:role "user" :content content}]
                                     :session-id session_id
                                     :stream? true
                                     :on-delta push-delta!
                                     :on-thinking-delta push-thinking!}))]
         (push!)
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
                       nil

                       (stream-ending-message-event? event session_id)
                       (do
                         (reset! streaming-state {})
                         (push-final!))

                       (relevant-session-event? event session_id)
                       (push!))
                     (if (and done? terminal?*)
                       (push-final!)
                       (recur done? result-ch* terminal?* fallback-ch)))
                   (when done?
                     (push-final!))))))))))))

(defn chat-stop [system request]
  (let [{:keys [session_id]} (h/read-form-body request)]
    (h/ensure-session-exists! system session_id)
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

(defn logs [system _request]
  (responses/html-response 200 (ui/logs-fragment system)))

(defn- relevant-run-detail-event? [event run-id]
  (and (= "agent_run" (:entity-type event))
       (= run-id (:entity-id event))))

(defn run-detail-live-response
  [system request]
  (let [run-id (-> request :parameters :query :run_id)
        broker-instance (or (:event-bus system) (:broker system))]
	    (streaming/managed-response
	     request
	     {:name :ui-run-detail-live
          :metrics (:sse-metrics system)
	      :on-error (fn [_ _] nil)}
     (fn [ctx]
       (let [subscription (streaming/subscribe! ctx
                                                broker-instance
                                                (broker/all-events-subject)
                                                {:buffer-size defaults/event-stream-buffer-size
                                                 :buffer-strategy :sliding
                                                 :slow-client :drop-new})
             ch (:channel subscription)
             patch! #(streaming/send-datastar-patch!
                      ctx
                      (str "<div id=\"run-detail-body\">"
                           (ui/run-detail-body system run-id)
                           "</div>"))]
         (patch!)
         (loop []
           (when-let [event (some-> (streaming/take! ctx ch) :payload)]
             (when (relevant-run-detail-event? event run-id)
               (patch!))
            (recur))))))))

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

(defn memory-prompt [system _request]
  (responses/html-response 200 (ui/memory-prompt-fragment system)))

(defn memory-search [system request]
  (let [{:keys [query]} (h/read-form-body request)]
    (responses/html-response 200
                             (ui/memory-search-results-fragment
                              (memory/search-memory (:memory-service system) query)))))

(defn- parse-int-form [value]
  (when-not (str/blank? (str value))
    (Integer/parseInt (str value))))

(defn- form-scope [{:keys [scope_type scope_id]}]
  (when-not (str/blank? (str scope_type))
    (cond-> {:type scope_type}
      (not (str/blank? (str scope_id))) (assoc :id scope_id))))

(defn- memory-tool-input [{:keys [action query subject predicate object path content] :as body}]
  (cond-> {:action action}
    (not (str/blank? (str query))) (assoc :query query)
    (parse-int-form (:limit body)) (assoc :limit (parse-int-form (:limit body)))
    (form-scope body) (assoc :scope (form-scope body))
    (not (str/blank? (str subject))) (assoc :subject subject)
    (not (str/blank? (str predicate))) (assoc :predicate predicate)
    (not (str/blank? (str object))) (assoc :object object)
    (not (str/blank? (str path))) (assoc :path path)
    (contains? body :content) (assoc :content content)))

(defn- memory-tool-target [{:keys [action] :as input}]
  (case (keyword (str/lower-case (str action)))
	    :search [:memory_search (dissoc input :action)]
	    :save-fact [:memory_save_fact (dissoc input :action)]
	    :remove-fact [:memory_remove_fact (dissoc input :action)]
	    :read-vault [:memory_read_vault (dissoc input :action)]
	    :write-vault [:memory_write_vault (dissoc input :action)]))

(defn- memory-search-source-json [system {:keys [action query limit scope]}]
  (when (and (= :search (keyword (str/lower-case (str action))))
             (not (str/blank? (str query))))
    (memory/search-memory (:memory-service system)
                          query
                          (cond-> {}
                            limit (assoc :limit limit)
                            scope (assoc :scope scope)))))

(defn memory-tool-run [system request]
  (try
    (let [body (h/read-form-body request)
          input (memory-tool-input body)
          [tool-name tool-input] (memory-tool-target input)
          result (tools/execute-tool (:tool-registry system)
                                     tool-name
                                     tool-input
                                     (assoc
                                      (tools-h/execution-context system :ui tool-name tool-input
                                                                 {:user "ui-memory"})
                                      :permissions #{:memory-read :memory-write}))]
      (responses/html-response
       200
       (ui/memory-tool-result-fragment
        {:ok? true
         :input input
         :result result
         :source-json (memory-search-source-json system input)})))
    (catch Exception e
      (responses/html-response
       200
       (ui/memory-tool-result-fragment
        {:ok? false
         :error (.getMessage e)
         :details (ex-data e)})))))

(defn- memory-reset-response [system surface reset!]
  (try
    (responses/html-response
     200
     (ui/memory-workspace-fragment system
                                   {:ok? true
                                    :surface surface
                                    :result (reset!)}))
    (catch Exception e
      (responses/html-response
       200
       (ui/memory-workspace-fragment system
                                     {:ok? false
                                      :surface surface
                                      :error (.getMessage e)
                                      :details (ex-data e)})))))

(defn memory-facts-reset [system _request]
  (memory-reset-response system
                         "Facts"
                         #(memory/reset-facts! (:memory-service system))))

(defn list-runs [system _request]
  (responses/html-response 200 (ui/runs-fragment system)))

(defn run-detail [system request]
  (responses/html-response 200
                           (let [run-id (-> request :parameters :query :run_id)]
                             (str (ui/run-detail-fragment system run-id)
                                  (ui/router-state-fragment
                                   (ui/run-route-path system run-id))))))

(defn run-detail-body [system request]
  (responses/html-response 200
                           (ui/run-detail-body system (-> request :parameters :query :run_id))))

(defn create-run [system request]
  (let [body (h/read-form-body request)
        run (runs/request-run! system
                               {:agent-id (not-empty (:agent_id body))
                                :name (not-empty (:name body))
                                :substrate :external
                                :requested-by "ui"})
        run-id (:id run)]
    (responses/html-response 201
                             (str (ui/runs-fragment system)
                                  (ui/run-detail-fragment system run-id)
                                  (ui/router-state-fragment
                                   (ui/run-route-path system run-id))))))

(defn list-tools [system _request]
  (responses/html-response 200 (ui/tools-fragment system)))

(defn list-tool-approvals [system _request]
  (responses/html-response 200
                           (ui/tool-approvals-fragment
                            (tool-approvals/list-requests (:store system) {:limit 50}))))

(defn tool-approval-request [system request]
  (let [body (h/read-form-body request)
        tool-name (keyword (:tool body))
        input (ui-tool-input body)
        approval (tool-approvals/create-request!
                  (:store system)
                  {:tool-name tool-name
                   :input input
                   :requested-by "ui"
                   :reason (:reason body)
                   :expires-at (approvals/approval-expires-at system)})]
    (events/log-event! system
                       {:event-type :tool.approval.requested
                        :entity-type :tool_approval
                        :entity-id (:id approval)
                        :payload {:tool-name (name tool-name)
                                  :requested-by (:requested-by approval)
                                  :requested-permissions (mapv name (:requested-permissions approval))
                                  :expires-at (:expires-at approval)}})
    (responses/html-response 201
                             (str (ui/tool-approvals-fragment
                                   (tool-approvals/list-requests (:store system) {:limit 50}))
                                  (ui/tool-results-fragment
                                   tool-name
                                   201
                                   {:approval_id (:id approval)
                                    :status (:status approval)})))))

(defn tool-approval-decision [system request approval-id status]
  (let [body (h/read-form-body request)
        actor (or (:actor body) "operator")
        reason (:reason body)
        updated (case status
                  :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                  :denied (tool-approvals/deny! (:store system) approval-id actor reason))]
    (events/log-event! system
                       {:event-type (keyword (str "tool.approval." (name status)))
                        :entity-type :tool_approval
                        :entity-id approval-id
                        :payload {:tool-name (:tool-name updated)
                                  :actor actor
                                  :decision status
                                  :reason reason}})
    (responses/html-response 200
                             (str (ui/tool-approvals-fragment
                                   (tool-approvals/list-requests (:store system) {:limit 50}))
                                  (ui/tool-results-fragment
                                   (keyword (:tool-name updated))
                                   200
                                   {:approval_id approval-id
                                    :status (:status updated)})))))

(defn tool-approval-run [system _request approval-id]
  (let [{:keys [tool-name input permissions approval]} (tool-approvals/resolve-approved-request (:store system) approval-id)
        user (or (not-empty (:requested-by approval)) "ui")]
    (try
      (responses/html-response
       200
       (str (ui/tool-approvals-fragment
             (tool-approvals/list-requests (:store system) {:limit 50}))
            (ui/tool-results-fragment
             tool-name
             200
             {:result (tools/execute-tool (:tool-registry system)
                                          tool-name
                                          input
                                          {:permissions permissions
                                           :approval-id approval-id
                                           :user user
                                           :activity (:activity input)})})))
      (catch Exception e
        (let [api-e (errors/tool-error->api-error e)]
          (responses/html-response
           (:status (ex-data api-e))
           (ui/tool-results-fragment
            tool-name
            (:status (ex-data api-e))
            {:error (:error (ex-data api-e))
             :message (.getMessage api-e)
             :details (:details (ex-data api-e))})))))))
