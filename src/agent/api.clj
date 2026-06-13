(ns agent.api
  "HTTP API entry point. Routes are defined in agent.api.routes; handlers live
   under agent.api.handlers.*. This namespace wires the route data to handlers,
   composes middleware, and starts/stops the http-kit server."
  (:require
   [agent.api.handlers.channel-adapters :as channel-adapters]
   [agent.api.handlers.chat :as chat]
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
   :ui-shell (fn [r] (ui/shell (sys) r))
   :ui-dashboard (fn [r] (ui/dashboard (sys) r))
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
   :ui-events-live (fn [r] (ui/events-live-response (sys) r))
   :ui-memory-search (fn [r] (ui/memory-search (sys) r))
   :ui-memory-tool (fn [r] (ui/memory-tool-run (sys) r))
   :ui-memory-vault-status (fn [r] (ui/memory-vault-status (sys) r))
   :ui-memory-vault-move (fn [r] (ui/memory-vault-move (sys) r))
   :ui-memory-vault-reindex (fn [r] (ui/memory-vault-reindex (sys) r))
   :ui-tools (fn [r] (ui/list-tools (sys) r))
   :ui-system-reload (fn [r] (ui/system-reload (sys) r))
   :ui-tool-approvals (fn [r] (ui/list-tool-approvals (sys) r))
   :ui-tool-approval-request (fn [r] (ui/tool-approval-request (sys) r))
   :ui-tool-approval-approve (fn [r] (ui/tool-approval-decision (sys) r (path-param r :approval-id) :approved))
   :ui-tool-approval-deny (fn [r] (ui/tool-approval-decision (sys) r (path-param r :approval-id) :denied))
   :ui-tool-approval-run (fn [r] (ui/tool-approval-run (sys) r (path-param r :approval-id)))

   :public-file public/file-response

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
   :memory-vault-write (fn [r] (memory/vault-write (sys) r))
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
