(ns agent.tools.service
  "Production tool registry assembly. Applies configured policy, wires telemetry
   hooks, registers built-in tools and MCP tools, and exposes lookup/execution
   helpers used by the runtime."
  (:require
   [agent.logging :as logging]
   [agent.magi.core :as magi]
   [agent.mcp.core :as mcp]
   [agent.runtime.trace :as runtime-trace]
   [agent.restart-handoff :as restart-handoff]
   [agent.telemetry :as telemetry]
   [agent.telemetry.observer :as telemetry-observer]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.common.cron :as cron-tool]
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.common.homeassistant :as homeassistant-tool]
   [agent.tools.common.http :as http-tool]
   [agent.tools.common.magi :as magi-tool]
   [agent.tools.common.memory :as memory-tool]
   [agent.tools.common.shell :as shell-tool]
   [agent.tools.common.skills :as skills-tool]
   [agent.tools.common.telegram :as telegram-tool]
   [agent.tools.common.todo :as todo-tool]
   [agent.tools.common.wasm :as wasm-tool]
   [agent.tools.core :as tools]
   [agent.wasm.bundles :as wasm-bundles]
   [clojure.set :as set]
   [clojure.string :as str]))

(defn- normalize-tool-name [tool]
  (cond
    (keyword? tool) tool
    (string? tool) (keyword tool)
    :else tool))

(def ^:private tool-family-aliases
  {:fs #{:fs_read :fs_write :fs_create :fs_replace :fs_list :fs_delete :fs_mkdir}
   :memory #{:memory_recall :vault_search
             :scratchpad_read :scratchpad_search :scratchpad_replace
             :memory_propose_create :memory_propose_update :memory_extract_session
             :message_search :message_get}
   :skills #{:skills_list :skills_read}
   :todo #{:todo_write :todo_get :todo_list :todo_search}})

(defn- expand-tool-name [tool]
  (let [tool* (normalize-tool-name tool)]
    (or (tool-family-aliases tool*) #{tool*})))

(defn- tool-name-set [tools]
  (set (mapcat expand-tool-name tools)))

(defn create-tool-policy-hook
  [cfg]
  (let [policy (:policy cfg)
        allowlist (tool-name-set (:allowlist policy))
        blocklist (tool-name-set (:blocklist policy))
        tool-scopes (into {}
                          (mapcat (fn [[tool scopes]]
                                    (map (fn [tool-name]
                                           [tool-name (set (map keyword scopes))])
                                         (expand-tool-name tool))))
                          (:tool-scopes policy))]
    (fn [{:keys [tool context]}]
      (let [tool-name (:name tool)
            context-scopes (set (map keyword (or (:tool-scopes context) (:scopes context) [])))
            required-scopes (get tool-scopes tool-name)]
        (cond
          (or (contains? blocklist :*) (contains? blocklist tool-name))
          {:block true
           :reason "Tool blocked by startup policy"}

          (and (seq allowlist)
               (not (or (contains? allowlist :*) (contains? allowlist tool-name))))
          {:block true
           :reason "Tool not in startup allowlist"}

          (and (seq required-scopes)
               (empty? (set/intersection required-scopes context-scopes)))
          {:block true
           :reason "Tool scope missing"}

          :else nil)))))

(defn- fs-cfg-with-vault-roots [fs-cfg memory-service]
  (let [fs-cfg* (or fs-cfg {})
        roots (vec (distinct (concat (or (:roots fs-cfg*) ["."])
                                     (:vault-roots memory-service))))]
    (assoc fs-cfg* :roots roots)))

(defn- schedule-handoff-from-context!
  [system message context]
  (restart-handoff/schedule!
   system
   {:session-id (:session-id context)
    :message message
    :permission-profile (or (:permission-profile context) :chat)}))

(defn- handoff-tool
  [system-control]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :system_handoff
     "Persist a message that will automatically start the next turn in this session after Iris restarts. Call this in a completed tool step before restarting the process. A later call replaces this session's pending handoff."
     :category :system
     :required-permissions #{:system-reload}
     :input-schema [:map
                    [:message [:string {:min 1 :max restart-handoff/max-message-chars}]]]
     :operation :act)
    :execute-fn
    (fn [{:keys [message]} context]
      (let [system (or (some-> (:system-ref system-control) deref)
                       (:system context))
            handoff (schedule-handoff-from-context! system message context)]
        {:status :scheduled
         :handoff-id (:id handoff)
         :session-id (:session-id handoff)}))
    :health-fn
    (fn [] {:healthy true})}))

(defn- reload-tool
  [system-control]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :system_reload
     "Reload Iris runtime configuration from disk. Soft reload applies hot-safe runtime config. Full reload schedules a process-local rebuild; resume_message atomically schedules the next automatic turn before that rebuild."
     :category :system
     :required-permissions #{:system-reload}
     :input-schema [:map
                    [:mode {:optional true} [:enum "soft" "full"]]
                    [:resume_message {:optional true}
                     [:maybe [:string {:min 1 :max restart-handoff/max-message-chars}]]]]
     :operation :act)
    :execute-fn
    (fn [{:keys [mode resume_message]} context]
      (let [system (or (some-> (:system-ref system-control) deref)
                       (:system context))
            mode* (keyword (or mode "soft"))]
        (when (and resume_message (not= :full mode*))
          (throw (ex-info "resume_message requires mode=full"
                          {:type :validation-failed})))
        (let [handoff (when resume_message
                        (schedule-handoff-from-context! system resume_message context))
              result ((:reload! system-control)
                      system
                      {:mode mode*
                       :source (or (:user context) "tool")})]
          (cond-> result
            handoff (assoc :handoff-id (:id handoff))))))
    :health-fn
    (fn [] {:healthy true})}))

(defn- mcp-tool-name-prefix [server-name]
  (str (str/replace (str server-name) #"[^a-zA-Z0-9_-]" "_") "__"))

(defn- register-mcp-server-tools
  [registry server telemetry-collector]
  (let [client (-> (mcp/create-http-client
                    (cond-> {:endpoint-url (:url server)
                             :headers (:headers server)
                             :telemetry telemetry-collector}
                      (:timeout-ms server) (assoc :timeout-ms (:timeout-ms server))))
                   mcp/initialize!)]
    (mcp/register-remote-tools! registry client
                                :name-prefix (mcp-tool-name-prefix (:name server)))))

(defn- register-mcp-tools
  "Register tools from each configured MCP server. A server that fails to
   initialize or list tools is logged and skipped so registry construction
   never fails because of a remote peer."
  [registry mcp-cfg event-sink telemetry-collector]
  (reduce
   (fn [registry* server]
     (try
       (register-mcp-server-tools registry* server telemetry-collector)
       (catch Exception e
         (logging/log-error! :agent.tools/mcp-server-skipped e
                             {:mcp/server (:name server)
                              :mcp/url (:url server)})
         (when event-sink
           (event-sink {:event-type :mcp-server-skipped
                        :entity-type :tool
                        :entity-id (str (:name server))
                        :payload {:server (:name server)
                                  :url (:url server)
                                  :error (.getMessage e)}}))
         registry*)))
   registry
   (:servers mcp-cfg)))

(defn create-tool-registry
  "Build the production tool registry from a dependency map:
   {:cfg <:tools config> :event-sink :store :telemetry :memory-service
    :skills-registry :channel-adapters-cfg :system-control :observer :trace}"
  [{:keys [cfg event-sink store memory-service skills-registry channel-adapters-cfg cron-service
           system-control observer trace magi-service note-llm-provider llm-provider
           user-profile-service]
    telemetry-collector :telemetry}]
   (let [http-cfg (get cfg :http)
         fs-cfg (get cfg :fs)
         homeassistant-cfg (get cfg :homeassistant)
         wasm-cfg (get cfg :wasm)
         shell-cfg (get cfg :shell)
         todo-cfg (get cfg :todo)
         mcp-cfg (get cfg :mcp)
         telegram-cfg (get channel-adapters-cfg :telegram)
         wasm-bundles-cfg (get cfg :wasm-bundles)
         policy-hook (create-tool-policy-hook cfg)
         registry (tools/create-registry
                   {:event-sink event-sink
                    :approval-check (tool-approvals/create-policy-hook
                                     {:store store
                                      :magi-service magi-service
                                      :event-sink event-sink
                                      :approval-ttl-seconds (get-in cfg [:approvals :ttl-seconds])})
                    :before-execute policy-hook
                    :after-execute (fn [{:keys [tool context duration-ms is-error error input result] :as hook}]
                                     (let [observation {:tool-name (:name tool)
                                                        :duration-ms duration-ms
                                                        :success? (not is-error)
                                                        :error error
                                                        :user (:user context)}]
                                       (if observer
                                         (do
                                           (telemetry-observer/record-event! observer {:event-type :tool/call
                                                                                       :payload observation})
                                           (telemetry-observer/record-metric! observer {:metric-type :request-latency-ms
                                                                                        :component :tool
                                                                                        :tool-name (:name tool)
                                                                                        :value duration-ms}))
                                         (telemetry/record-tool! telemetry-collector observation))
                                       (runtime-trace/record-event!
                                        trace
                                        {:event-type :tool.call
                                         :turn-id (:request-id context)
                                         :success (not is-error)
                                         :error-message (some-> error .getMessage)
                                         :payload {:tool-name (some-> (:name tool) name)
                                                   :duration-ms duration-ms
                                                   :input input
                                                   :result result}}))
                                     hook)})]
     (cond-> registry
       (not= false (:enabled http-cfg))
       (tools/register-tool (http-tool/create-http-tool http-cfg))

       (not= false (:enabled fs-cfg))
       (as-> registry*
             (reduce tools/register-tool
                     registry*
                     (fs-tool/create-fs-tools (fs-cfg-with-vault-roots fs-cfg memory-service))))

       (true? (:enabled homeassistant-cfg))
       (tools/register-tool (homeassistant-tool/create-homeassistant-tool homeassistant-cfg))

       (true? (:enabled wasm-cfg))
       (tools/register-tool (wasm-tool/create-wasm-tool wasm-cfg))

       (true? (:enabled? wasm-bundles-cfg))
       (as-> registry*
             (reduce tools/register-tool
                     registry*
                     (try
                       (wasm-bundles/create-bundle-tools wasm-bundles-cfg)
                       (catch Exception e
                         (logging/log-error! :agent.tools/wasm-bundles-skipped e {})
                         []))))

       skills-registry
       (as-> registry*
             (-> registry*
                 (tools/register-tool (skills-tool/create-skills-list-tool skills-registry))
                 (tools/register-tool (skills-tool/create-skills-read-tool skills-registry))))

       memory-service
       (as-> registry*
             (reduce tools/register-tool
                     registry*
                     (conj (memory-tool/create-memory-tools memory-service
                                                            (or note-llm-provider llm-provider)
                                                            user-profile-service)
                           (memory-tool/create-message-search-tool memory-service)
                           (memory-tool/create-message-get-tool memory-service))))

       (and store (not= false (:enabled todo-cfg)))
       (as-> registry*
             (reduce tools/register-tool
                     registry*
                     (todo-tool/create-todo-tools store)))

       cron-service
       (-> (tools/register-tool (cron-tool/create-cronjob-tool cron-service))
           (tools/register-tool (cron-tool/create-cron-notify-tool cron-service)))

       (not= false (:enabled shell-cfg))
       (tools/register-tool (shell-tool/create-shell-tool shell-cfg))

       (telegram-tool/enabled? telegram-cfg)
       (-> (tools/register-tool (telegram-tool/create-send-photo-tool telegram-cfg))
           (tools/register-tool (telegram-tool/create-send-document-tool telegram-cfg))
           (tools/register-tool (telegram-tool/create-ask-tool telegram-cfg)))

       system-control
       (-> (tools/register-tool (handoff-tool system-control))
           (tools/register-tool (reload-tool system-control)))

       (and magi-service (magi/tool-enabled? magi-service))
       (tools/register-tool (magi-tool/create-magi-tool magi-service))

       (and (:enabled mcp-cfg) (seq (:servers mcp-cfg)))
       (register-mcp-tools mcp-cfg event-sink telemetry-collector))))

(defn list-tools
  [system]
  (tools/list-tools (:tool-registry system)))

(defn tool-permissions
  [system profile]
  (set (get-in system [:config :tools :permissions profile] #{})))

(defn execute-tool
  ([system tool-name input]
   (execute-tool system tool-name input {}))
  ([system tool-name input context]
   (tools/execute-tool (:tool-registry system)
                       tool-name
                       input
                       (assoc context :yolo? (true? (get-in system [:config :tools :yolo?]))))))
