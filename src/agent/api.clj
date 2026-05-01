(ns agent.api
  "HTTP API entry point. Routes are defined in agent.api.routes; handlers live
   under agent.api.handlers.*. This namespace wires the route data to handlers,
   composes middleware, and starts/stops the http-kit server."
  (:require
   [agent.api.handlers.agents :as agents]
   [agent.api.handlers.channel-adapters :as channel-adapters]
   [agent.api.handlers.channels :as channels]
   [agent.api.handlers.chat :as chat]
   [agent.api.handlers.events :as events]
   [agent.api.handlers.federation :as federation]
   [agent.api.handlers.health :as health]
   [agent.api.handlers.memory :as memory]
   [agent.api.handlers.public :as public]
   [agent.api.handlers.runs :as runs]
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
   [clojure.walk :as walk]
   [muuntaja.core :as m]
   [org.httpkit.server :as http-kit]
   [reitit.coercion.malli :as malli-coercion]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as ring-coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(defn- path-param [request k]
  (get-in request [:path-params k]))

(defn- current-system [system]
  (if-let [system-ref (:system-ref system)]
    (or @system-ref system)
    system))

(defn- reload-mode [body]
  (keyword (or (:mode body) "soft")))

(defn- reload-response [system request]
  (let [result ((requiring-resolve 'agent.system/reload!)
                system
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

   :ui-index (fn [_] (responses/html-response 200 (ui-views/index-page)))
   :ui-shell (fn [r] (ui/shell (sys) r))
   :ui-dashboard (fn [r] (ui/dashboard (sys) r))
   :ui-operator-board (fn [r] (ui/operator-board (sys) r))
   :ui-sessions (fn [r] (ui/sessions (sys) r))
   :ui-create-session (fn [r] (ui/create-session (sys) r))
   :ui-runs (fn [r] (ui/list-runs (sys) r))
   :ui-create-run (fn [r] (ui/create-run (sys) r))
   :ui-run-detail (fn [r] (ui/run-detail (sys) r))
   :ui-run-detail-body (fn [r] (ui/run-detail-body (sys) r))
   :ui-run-detail-live (fn [r] (ui/run-detail-live-response (sys) r))
   :ui-run-launch (fn [r] (ui/run-launch (sys) r (path-param r :run-id)))
   :ui-run-signal (fn [r] (ui/run-signal (sys) r (path-param r :run-id)))
   :ui-session-detail (fn [r] (ui/session-detail (sys) r))
   :ui-session-messages (fn [r] (ui/session-messages (sys) r))
   :ui-session-live (fn [r] (ui/session-live-response (sys) r))
   :ui-chat (fn [r] (ui/chat-action (sys) r))
   :ui-chat-stop (fn [r] (ui/chat-stop (sys) r))
   :ui-events (fn [r] (ui/events (sys) r))
   :ui-events-live (fn [r] (ui/events-live-response (sys) r))
   :ui-memory-prompt (fn [r] (ui/memory-prompt (sys) r))
   :ui-memory-search (fn [r] (ui/memory-search (sys) r))
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
   :list-session-messages (fn [r] (sessions/list-messages (sys) r (path-param r :session-id)))

   :chat-completions (fn [r] (chat/completions-response (sys) r))
   :chat-stop (fn [r] (chat/stop-response (sys) r))

   :list-runs (fn [r] (runs/list-runs (sys) r))
   :create-run (fn [r] (runs/create (sys) r))
   :get-run (fn [r] (runs/get-run (sys) r (path-param r :run-id)))
   :launch-run (fn [r] (runs/launch (sys) r (path-param r :run-id)))
   :signal-run (fn [r] (runs/signal (sys) r (path-param r :run-id)))
   :run-heartbeats (fn [r] (runs/heartbeats (sys) r (path-param r :run-id)))
   :run-checkpoints (fn [r] (runs/checkpoints (sys) r (path-param r :run-id)))
   :run-commands (fn [r] (runs/commands (sys) r (path-param r :run-id)))
   :run-control-register (fn [r] (runs/control-register (sys) r (path-param r :run-id)))
   :run-control-heartbeat (fn [r] (runs/control-heartbeat (sys) r (path-param r :run-id)))
   :run-control-checkpoint (fn [r] (runs/control-checkpoint (sys) r (path-param r :run-id)))
   :run-control-commands (fn [r] (runs/control-commands (sys) r (path-param r :run-id)))
   :run-control-command-ack (fn [r] (runs/control-command-ack (sys) r (path-param r :run-id) (path-param r :command-id)))
   :run-control-command-complete (fn [r] (runs/control-command-complete (sys) r (path-param r :run-id) (path-param r :command-id)))
   :run-control-transition (fn [r] (runs/control-transition (sys) r (path-param r :run-id)))
   :run-events (fn [r] (runs/run-events (sys) r (path-param r :run-id)))
   :run-events-stream (fn [r] (runs/events-stream-response (sys) (path-param r :run-id) r))
   :run-wait (fn [r] (runs/wait (sys) r (path-param r :run-id)))
   :run-recover (fn [r] (runs/recover (sys) r (path-param r :run-id)))
   :reclaim-stale-runs (fn [r] (runs/reclaim-stale (sys) r))

   :list-tools (fn [r] (tools/list-tools (sys) r))
   :execute-tool (fn [r] (tools/execute-tool (sys) r (path-param r :tool-name)))
   :system-reload (fn [r] (reload-response (sys) r))
   :list-tool-approvals (fn [r] (tool-approvals/list-approvals (sys) r))
   :create-tool-approval (fn [r] (tool-approvals/create (sys) r))
   :approve-tool-approval (fn [r] (tool-approvals/decide (sys) r (path-param r :approval-id) :approved))
   :deny-tool-approval (fn [r] (tool-approvals/decide (sys) r (path-param r :approval-id) :denied))
   :list-skills (fn [r] (skills/list-skills (sys) r))
   :list-channel-adapters (fn [r] (channel-adapters/list-adapters (sys) r))
   :list-events (fn [r] (events/list-events (sys) r))
   :events-stream (fn [r] (events/stream-response (sys) r))
   :telemetry (fn [r] (telemetry/snapshot (sys) r))

   :memory-surfaces (fn [r] (memory/surfaces (sys) r))
   :memory-prompt (fn [r] (memory/prompt (sys) r))
   :memory-search (fn [r] (memory/search (sys) r))
   :memory-fact-save (fn [r] (memory/fact-save (sys) r))
   :memory-fact-search (fn [r] (memory/fact-search (sys) r))
   :memory-vault-read (fn [r] (memory/vault-read (sys) r))
   :memory-vault-write (fn [r] (memory/vault-write (sys) r))
   :memory-graph-save (fn [r] (memory/graph-save (sys) r))
   :memory-graph-query (fn [r] (memory/graph-query (sys) r))

   :list-agents (fn [r] (agents/list-agents (sys) r))
   :create-agent (fn [r] (agents/create (sys) r))
   :agent-messages (fn [r] (agents/list-messages (sys) r (path-param r :agent-id)))
   :agent-message (fn [r] (agents/send-message (sys) r (path-param r :agent-id)))
   :agent-tool-execute (fn [r] (agents/tool-execute (sys) r (path-param r :agent-id) (path-param r :tool-name)))
   :orchestrator-spawn-worker (fn [r] (agents/orchestrator-spawn-worker (sys) r (path-param r :agent-id)))
   :agent-step-execute (fn [r] (agents/step-execute (sys) r (path-param r :agent-id)))
   :consume-agent-inbox (fn [r] (agents/consume-inbox (sys) r (path-param r :agent-id)))
   :agent-interop (fn [r] (agents/interop (sys) r (path-param r :agent-id)))
   :agent-interop-capabilities (fn [r] (agents/interop-capabilities (sys) r (path-param r :agent-id)))
   :agent-interop-message (fn [r] (agents/interop-message-post (sys) r (path-param r :agent-id)))
   :agent-interop-messages (fn [r] (agents/interop-messages-list (sys) r (path-param r :agent-id)))
   :agent-interop-ack (fn [r] (agents/interop-ack (sys) r (path-param r :agent-id) (path-param r :message-id)))
   :agent-interop-retry (fn [r] (agents/interop-retry (sys) r (path-param r :agent-id) (path-param r :message-id)))

   :list-federated-peers (fn [r] (federation/list-peers (sys) r))
   :create-federated-peer (fn [r] (federation/create-peer (sys) r))
   :federation-inbox (fn [r] (federation/inbox (sys) r))

   :list-channels (fn [r] (channels/list-channels (sys) r))
   :create-channel (fn [r] (channels/create (sys) r))
   :channel-messages (fn [r] (channels/list-messages (sys) r (path-param r :channel-id)))
   :channel-message (fn [r] (channels/post-message (sys) r (path-param r :channel-id)))}))

(defn- bind-route-handlers
  [system]
  (let [handlers (handler-map system)]
    (walk/postwalk
     (fn [node]
       (if (and (map? node) (contains? node :handler/id))
         (-> node
             (dissoc :handler/id)
             (assoc :handler (get handlers (:handler/id node))))
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
                                      muuntaja/format-negotiate-middleware
                                      muuntaja/format-request-middleware
                                      ring-coercion/coerce-request-middleware]}})
    (fn [_] (responses/not-found-response)))
   (:api (:config system))))

(defn start-server!
  [system {:keys [host port]}]
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
