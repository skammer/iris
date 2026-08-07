(ns agent.api
  "HTTP API entry point. Routes are defined in agent.api.routes; handlers live
   under agent.api.handlers.*. This namespace wires the route data to handlers,
   composes middleware, and starts/stops the http-kit server."
  (:require
   [agent.api.handlers.channel-adapters :as channel-adapters]
   [agent.api.handlers.a2a :as a2a]
   [agent.api.handlers.chat :as chat]
   [agent.api.handlers.cron :as cron]
   [agent.api.handlers.events :as events]
   [agent.api.handlers.health :as health]
   [agent.api.handlers.memory :as memory]
   [agent.api.handlers.public :as public]
   [agent.api.handlers.providers :as providers]
   [agent.api.handlers.sessions :as sessions]
   [agent.api.handlers.skills :as skills]
   [agent.api.handlers.telemetry :as telemetry]
   [agent.api.handlers.tool-approvals :as tool-approvals]
   [agent.api.handlers.tools :as tools]
   [agent.api.handlers.ui :as ui]
   [agent.api.helpers :as h]
   [agent.api.middleware :as middleware]
   [agent.api.responses :as responses]
   [agent.api.routes :as routes]
   [agent.logging :as logging]
   [agent.ui :as ui-views]
   [agent.ui.catalog :as ui-catalog]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [muuntaja.core :as m]
   [org.httpkit.server :as http-kit]
   [reitit.coercion.malli :as malli-coercion]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as ring-coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.middleware.multipart-params :refer [wrap-multipart-params]]))

(defn- path-param [request k]
  (get-in request [:path-params k]))

(defn- current-system [system]
  (if-let [system-ref (:system-ref system)]
    (or @system-ref system)
    system))

(defn- reload-mode [body]
  (keyword (or (:mode body) "soft")))

(defn- reload-response [system request]
  (let [reload! (get-in system [:system-control :reload!])
        result (reload! system
                        {:mode (reload-mode (h/read-json-body request))
                         :source "api"})]
    (responses/json-response (if (= :scheduled (:status result)) 202 200)
                             {:data result})))

(defn- handler-map
  "Map of {handler-id → ring handler fn}. Each fn receives a ring request and
   returns a ring response map (or an http-kit channel for streaming)."
  [system]
  (let [sys #(current-system system)]
  {:health (fn [r] (health/handle (sys) r))

   :ui-index (fn [r] (responses/html-response 200 (ui-views/index-page (:uri r))))
   :ui-catalog (fn [_] (responses/html-response 200 (ui-catalog/page)))
   :ui-shell (fn [r] (ui/shell (sys) r))
   :ui-route (fn [r] (ui/route (sys) r))
   :ui-dashboard (fn [r] (ui/dashboard (sys) r))
   :ui-cron (fn [r] (ui/cron (sys) r))
   :ui-cron-create (fn [r] (ui/cron-create (sys) r))
   :ui-cron-preview (fn [r] (ui/cron-preview (sys) r))
   :ui-cron-action (fn [r] (ui/cron-action (sys) r))
   :ui-operator-board (fn [r] (ui/operator-board (sys) r))
   :ui-sessions (fn [r] (ui/sessions (sys) r))
   :ui-create-session (fn [r] (ui/create-session (sys) r))
   :ui-session-detail (fn [r] (ui/session-detail (sys) r))
   :ui-session-messages (fn [r] (ui/session-messages (sys) r))
   :ui-session-live (fn [r] (ui/session-live-response (sys) r))
   :ui-chat (fn [r] (ui/chat-action (sys) r))
   :ui-chat-stop (fn [r] (ui/chat-stop (sys) r))
   :ui-events (fn [r] (ui/events (sys) r))
   :ui-logs (fn [r] (ui/logs (sys) r))
   :ui-magi (fn [r] (ui/magi (sys) r))
   :ui-events-live (fn [r] (ui/events-live-response (sys) r))
   :ui-memory-search (fn [r] (ui/memory-search (sys) r))
   :ui-memory-tool (fn [r] (ui/memory-tool-run (sys) r))
   :ui-memory-vault-status (fn [r] (ui/memory-vault-status (sys) r))
   :ui-memory-vault-magi (fn [r] (ui/memory-vault-magi (sys) r))
   :ui-memory-vault-magi-update (fn [r] (ui/memory-vault-magi-update (sys) r))
   :ui-memory-vault-move (fn [r] (ui/memory-vault-move (sys) r))
   :ui-memory-vault-reindex (fn [r] (ui/memory-vault-reindex (sys) r))
   :ui-system-reload (fn [r] (ui/system-reload (sys) r))
   :ui-tool-approvals (fn [r] (ui/list-tool-approvals (sys) r))
   :ui-tool-approval-detail (fn [r] (ui/tool-approval-detail (sys) r (path-param r :approval-id)))
   :ui-tool-approval-approve (fn [r] (ui/tool-approval-decision (sys) r (path-param r :approval-id) :approved))
   :ui-tool-approval-deny (fn [r] (ui/tool-approval-decision (sys) r (path-param r :approval-id) :denied))
   :ui-tool-approval-run (fn [r] (ui/tool-approval-run (sys) r (path-param r :approval-id)))

   :public-file public/file-response

   :a2a-agent-card (fn [r] (a2a/agent-card (sys) r))
   :a2a-send-message (fn [r] (a2a/send-message (sys) r))
   :a2a-get-task (fn [r] (a2a/get-task (sys) r (path-param r :task-id)))
   :a2a-list-tasks (fn [r] (a2a/list-tasks (sys) r))
   :a2a-task-operation (fn [r] (a2a/task-operation (sys) r (path-param r :task-id)))

   :list-sessions (fn [r] (sessions/list-sessions (sys) r))
   :create-session (fn [r] (sessions/create (sys) r))
   :get-session (fn [r] (sessions/get-session (sys) r (path-param r :session-id)))
   :set-session-mode (fn [r] (sessions/set-mode (sys) r (path-param r :session-id)))
   :list-session-messages (fn [r] (sessions/list-messages (sys) r (path-param r :session-id)))
   :append-session-entry (fn [r] (sessions/append-entry (sys) r (path-param r :session-id)))
   :list-session-entries (fn [r] (sessions/list-entries (sys) r (path-param r :session-id)))
   :session-current-path (fn [r] (sessions/current-path (sys) r (path-param r :session-id)))
   :session-tree (fn [r] (sessions/tree (sys) r (path-param r :session-id)))
   :select-session-leaf (fn [r] (sessions/select-leaf (sys) r (path-param r :session-id)))
   :compact-session (fn [r] (sessions/compact (sys) r (path-param r :session-id)))

   :chat-completions (fn [r] (chat/completions-response (sys) r))
   :chat-stop (fn [r] (chat/stop-response (sys) r))

   :list-cron-jobs (fn [r] (cron/list-jobs (sys) r))
   :create-cron-job (fn [r] (cron/create-job (sys) r))
   :get-cron-job (fn [r] (cron/get-job (sys) r))
   :update-cron-job (fn [r] (cron/update-job (sys) r))
   :delete-cron-job (fn [r] (cron/delete-job (sys) r))
   :pause-cron-job (fn [r] (cron/set-status (sys) r :paused))
   :resume-cron-job (fn [r] (cron/set-status (sys) r :active))
   :run-cron-job (fn [r] (cron/run-job (sys) r))
   :list-cron-runs (fn [r] (cron/list-runs (sys) r))
   :get-cron-run (fn [r] (cron/get-run (sys) r))
   :cron-status (fn [r] (cron/status (sys) r))
   :preview-cron-job (fn [r] (cron/preview (sys) r))

   :list-providers (fn [r] (providers/list-providers (sys) r))
   :provider-health (fn [r] (providers/provider-health (sys) r (path-param r :provider-key)))
   :provider-models (fn [r] (providers/provider-models (sys) r (path-param r :provider-key)))

   :list-tools (fn [r] (tools/list-tools (sys) r))
   :execute-tool (fn [r] (tools/execute-tool (sys) r (path-param r :tool-name)))
   :system-reload (fn [r] (reload-response (sys) r))
   :list-tool-approvals (fn [r] (tool-approvals/list-approvals (sys) r))
   :create-tool-approval (fn [r] (tool-approvals/create (sys) r))
   :approve-tool-approval (fn [r] (tool-approvals/decide (sys) r (path-param r :approval-id) :approved))
   :deny-tool-approval (fn [r] (tool-approvals/decide (sys) r (path-param r :approval-id) :denied))
   :list-skills (fn [r] (skills/list-skills (sys) r))
   :slash-commands (fn [r] (skills/slash-commands (sys) r))
   :list-channel-adapters (fn [r] (channel-adapters/list-adapters (sys) r))
   :list-events (fn [r] (events/list-events (sys) r))
   :events-stream (fn [r] (events/stream-response (sys) r))
   :telemetry (fn [r] (telemetry/snapshot (sys) r))

   :memory-surfaces (fn [r] (memory/surfaces (sys) r))
   :memory-recall (fn [r] (memory/recall (sys) r))
   :memory-vault-read (fn [r] (memory/vault-read (sys) r))
   :memory-vault-propose-update (fn [r] (memory/vault-propose-update (sys) r))
   :memory-vault-reindex (fn [r] (memory/vault-reindex (sys) r))}))

(defn- route-handler-ids [route-data]
  (let [ids (atom [])]
    (walk/postwalk
     (fn [node]
       (when (and (map? node) (contains? node :handler/id))
         (swap! ids conj (:handler/id node)))
       node)
     route-data)
    @ids))

(defn- assert-route-bindings! [handlers route-data]
  (let [route-ids (set (route-handler-ids route-data))
        handler-ids (set (keys handlers))
        missing-handlers (sort (remove handler-ids route-ids))
        extra-handlers (sort (remove route-ids handler-ids))]
    (when (or (seq missing-handlers) (seq extra-handlers))
      (throw (ex-info "API route handler binding mismatch"
                      {:missing-handlers missing-handlers
                       :extra-handlers extra-handlers})))))

(defn- bind-route-handlers
  [system]
  (let [handlers (handler-map system)]
    (assert-route-bindings! handlers routes/routes)
    (walk/postwalk
     (fn [node]
       (if (and (map? node) (contains? node :handler/id))
         (let [handler-id (:handler/id node)]
           (-> node
               (dissoc :handler/id)
               (assoc :handler (get handlers handler-id))))
         node))
     routes/routes)))

(defn create-handler
  [system]
  (middleware/wrap-defaults
   (ring/ring-handler
    (ring/router (bind-route-handlers system)
                 {:conflicts nil
                  :data {:muuntaja m/instance
                         :coercion malli-coercion/coercion
                         :middleware [parameters/parameters-middleware
                                      wrap-multipart-params
                                      muuntaja/format-negotiate-middleware
                                      muuntaja/format-request-middleware
                                      ring-coercion/coerce-request-middleware]}})
    (fn [_] (responses/not-found-response)))
   (:api (:config system))))

(defn- loopback-bind-host?
  [host]
  (let [host* (str/trim (or host ""))]
    (or (str/blank? host*)
        (= "localhost" (str/lower-case host*))
        (try
          (let [addr (java.net.InetAddress/getByName host*)]
            (and (not (.isAnyLocalAddress addr))
                 (.isLoopbackAddress addr)))
          (catch Exception _
            false)))))

(defn- assert-api-auth-safe!
  [{:keys [host key]}]
  (when (and (not (loopback-bind-host? host))
             (str/blank? (some-> key str)))
    (throw (ex-info "Refusing to bind API to a non-loopback host without :api :key"
                    {:type :api-auth-required
                     :host host
                     :config-path [:api :key]}))))

(defn start-server!
  [system {:keys [host port]}]
  (assert-api-auth-safe! (merge (:api (:config system))
                                {:host host
                                 :port port}))
  (let [system-ref (or (:system-ref system) (atom nil))
        system* (assoc system :system-ref system-ref)
        _ (reset! system-ref system*)
        server (http-kit/run-server (create-handler system*)
                                    {:ip host
                                     :port (int port)})]
    (logging/log! :agent.http/server-started
                  {:host host
                   :port port})
    server))

(defn stop-server!
  [server]
  (when server
    (logging/log! :agent.http/server-stopping {})
    (server :timeout 100)))
