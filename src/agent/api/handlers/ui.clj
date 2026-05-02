(ns agent.api.handlers.ui
  (:require
   [agent.api.errors :as errors]
   [agent.api.handlers.runs :as runs]
   [agent.api.handlers.tool-approvals :as approvals]
   [agent.api.handlers.tools :as tools-h]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.streaming :as streaming]
   [agent.api.validation :as v]
   [agent.broker.core :as broker]
   [agent.chat :as chat]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [org.httpkit.server :as http-kit]))

(defn- form-bool [value]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str value))))

(defn- ui-tool-input [body]
  (tools-h/tool-input-from-map (keyword (:tool body)) body))

(defn shell [system request]
  (responses/html-response 200
                           (ui/shell-fragment system (some-> request :parameters :query :tab keyword))))

(defn dashboard [system _request]
  (responses/html-response 200 (ui/dashboard-fragment system)))

(defn operator-board [system _request]
  (responses/html-response 200 (ui/operator-board-fragment system)))

(defn sessions [system _request]
  (responses/html-response 200 (ui/sessions-fragment system)))

(defn create-session [system request]
  (let [body (h/read-form-body request)
        title (:title body)
        session (sqlite/create-session! (:store system) (not-empty title))]
    (v/emit-system-event! system
                          {:event-type :session.created
                           :entity-type :session
                           :entity-id (:id session)
                           :payload {:title (not-empty title)
                                     :source :ui}})
    (streaming/sse-response
     request
     (fn [channel]
       (streaming/send-datastar-patch!
        channel
        (str (ui/sessions-fragment system (:id session))
             (ui/session-detail-fragment system (:id session))
             (ui/dashboard-fragment system)))
       (http-kit/close channel))
     (fn [_ _] nil))))

(defn session-detail [system request]
  (let [session-id (-> request :parameters :query :session_id)]
    (responses/html-response 200
                             (str (ui/sessions-fragment system session-id)
                                  (ui/session-detail-fragment system session-id)))))

(defn session-messages [system request]
  (let [session-id (-> request :parameters :query :session_id)]
    (v/ensure-session-exists! system session-id)
    (responses/html-response 200
                             (ui/session-messages-fragment system session-id))))

(defn- relevant-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (or (contains? #{"message.appended" "completion.completed" "session.created"}
                      (:event-type event))
           (str/starts-with? (or (:event-type event) "") "chat."))))

(defn session-live-response
  [system request]
  (let [session-id (-> request :parameters :query :session_id)
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (v/ensure-session-exists! system session-id)
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (streaming/send-datastar-patch! channel (ui/session-messages-fragment system session-id))
           (loop []
             (when @open?
               (when-let [event (some-> (async/<!! ch) :payload)]
                 (when (relevant-session-event? event session-id)
                   (streaming/send-datastar-patch! channel
                                                   (ui/session-messages-fragment system session-id)))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn chat-action [system request]
  (let [{:keys [session_id prompt]} (h/read-form-body request)]
    (v/ensure-session-exists! system session_id)
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (let [push! (fn []
                         (streaming/send-datastar-patch!
                          channel
                          (ui/session-messages-fragment system session_id)))]
             (chat/run! system
                        {:messages [{:role "user" :content prompt}]
                         :session-id session_id
                         :on-delta (fn [_] (push!))})
             (push!))
           (catch Throwable t
             (println "chat/run! failed:" (.getMessage t)))
           (finally
             (http-kit/close channel)))))
     (fn [_ _] nil))))

(defn chat-stop [system request]
  (let [{:keys [session_id]} (h/read-form-body request)]
    (v/ensure-session-exists! system session_id)
    (chat/cancel-session! session_id)
    (responses/html-response 200
                             (str (ui/session-messages-fragment system session_id)
                                  "<div id=\"chat-status\" class=\"meta chat-status\">stopping...</div>"))))

(defn system-reload [system request]
  (let [body (h/read-form-body request)
        mode (keyword (or (:mode body) "soft"))]
    ((requiring-resolve 'agent.system/reload!)
     system
     {:mode mode
      :source "ui"})
    (responses/html-response 200 (ui/dashboard-fragment ((requiring-resolve 'agent.system/current-system) system)))))

(defn events [system _request]
  (responses/html-response 200 (ui/events-fragment system)))

(defn- relevant-run-detail-event? [event run-id]
  (and (= "agent_run" (:entity-type event))
       (= run-id (:entity-id event))))

(defn run-detail-live-response
  [system request]
  (let [run-id (-> request :parameters :query :run_id)
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (streaming/send-datastar-patch! channel
                                           (str "<div id=\"run-detail-body\">"
                                                (ui/run-detail-body system run-id)
                                                "</div>"))
           (loop []
             (when @open?
               (when-let [event (some-> (async/<!! ch) :payload)]
                 (when (relevant-run-detail-event? event run-id)
                   (streaming/send-datastar-patch! channel
                                                   (str "<div id=\"run-detail-body\">"
                                                        (ui/run-detail-body system run-id)
                                                        "</div>")))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn events-live-response
  [system request]
  (let [broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (streaming/sse-response
     request
     (fn [channel]
       (future
         (try
           (streaming/send-datastar-patch! channel (ui/events-fragment system))
           (loop []
             (when @open?
               (when (async/<!! ch)
                 (streaming/send-datastar-patch! channel (ui/events-fragment system))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn memory-prompt [system _request]
  (responses/html-response 200 (ui/memory-prompt-fragment system)))

(defn memory-search [system request]
  (let [{:keys [query]} (h/read-form-body request)]
    (responses/html-response 200
                             (ui/memory-search-results-fragment
                              (memory/search-memory (:memory-service system) query)))))

(defn list-runs [system _request]
  (responses/html-response 200 (ui/runs-fragment system)))

(defn run-detail [system request]
  (responses/html-response 200
                           (ui/run-detail-fragment system (-> request :parameters :query :run_id))))

(defn run-detail-body [system request]
  (responses/html-response 200
                           (ui/run-detail-body system (-> request :parameters :query :run_id))))

(defn create-run [system request]
  (let [body (h/read-form-body request)
        run (runs/request-run! system
                               {:agent-id (not-empty (:agent_id body))
                                :name (not-empty (:name body))
                                :substrate (keyword (or (:substrate body) "local-unsandboxed"))
                                :runner-options (cond-> {:working-dir (or (:working_dir body) ".")}
                                                  (tools-h/split-command-optional (:command body))
                                                  (assoc :command (tools-h/split-command-optional (:command body)))
                                                  (not-empty (:image body))
                                                  (assoc :image (:image body))
                                                  (form-bool (:share_network body))
                                                  (assoc :share-network? true))
                                :requested-by "ui"})
        _ (runs/launch-run! system (:id run))]
    (responses/html-response 201
                             (str (ui/runs-fragment system)
                                  (ui/run-detail-fragment system (:id run))))))

(defn run-launch [system _request run-id]
  (runs/launch-run! system run-id)
  (responses/html-response 200
                           (str (ui/runs-fragment system)
                                (ui/run-detail-fragment system run-id))))

(defn run-signal [system _request run-id]
  (runs/signal-run! system run-id {:command-type "cancel"})
  (responses/html-response 200
                           (str (ui/runs-fragment system)
                                (ui/run-detail-fragment system run-id))))

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
    (v/emit-system-event! system
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
    (v/emit-system-event! system
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
  (let [{:keys [tool-name input]} (tool-approvals/resolve-approved-request (:store system) approval-id)]
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
                                          (tools-h/execution-context system :ui tool-name input
                                                                     {:approval-id approval-id
                                                                      :user "ui"
                                                                      :activity (:activity input)}))})))
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
