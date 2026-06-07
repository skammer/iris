(ns agent.tools.service
  "Tool registry factory, policy, and execution facade."
  (:require
   [agent.runs.registry :as runtime]
   [agent.runtime.tools :as runtime-tools]
   [agent.runtime.trace :as runtime-trace]
   [agent.orchestrator :as orchestrator]
   [agent.telemetry :as telemetry]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.common.http :as http-tool]
   [agent.tools.common.memory :as memory-tool]
   [agent.tools.common.shell :as shell-tool]
   [agent.tools.common.telegram :as telegram-tool]
   [agent.tools.common.todo :as todo-tool]
   [agent.tools.core :as tools]
   [clojure.set :as set]))

(defn- normalize-tool-name [tool]
  (cond
    (keyword? tool) tool
    (string? tool) (keyword tool)
    :else tool))

(def ^:private tool-family-aliases
  {:fs #{:fs_read :fs_write :fs_create :fs_replace :fs_list :fs_delete :fs_mkdir}
   :memory #{:memory_search :memory_save_fact :memory_remove_fact
             :memory_read_vault :memory_write_vault :message_search}
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

(defn- reload-tool
  [system-control]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :system_reload
     "Reload Iris runtime configuration from disk. Soft reload applies hot-safe runtime config; full reload schedules a process-local rebuild."
     :category :system
     :required-permissions #{:system-reload}
     :input-schema [:map
                    [:mode {:optional true} [:enum "soft" "full"]]]
     :operation :act)
    :execute-fn
    (fn [input context]
      (let [system (or (some-> (:system-ref system-control) deref)
                       (:system context))]
        ((:reload! system-control)
         system
         {:mode (keyword (or (:mode input) "soft"))
          :source (or (:user context) "tool")})))
    :health-fn
    (fn [] {:healthy true})}))

(defn create-tool-registry
  ([cfg event-sink store]
   (create-tool-registry cfg event-sink store nil nil nil nil nil nil))
  ([cfg event-sink store telemetry-collector]
   (create-tool-registry cfg event-sink store telemetry-collector nil nil nil nil nil))
  ([cfg event-sink store telemetry-collector memory-service]
   (create-tool-registry cfg event-sink store telemetry-collector memory-service nil nil nil nil))
  ([cfg event-sink store telemetry-collector memory-service channel-adapters-cfg]
   (create-tool-registry cfg event-sink store telemetry-collector memory-service channel-adapters-cfg nil nil nil))
  ([cfg event-sink store telemetry-collector memory-service channel-adapters-cfg system-control]
   (create-tool-registry cfg event-sink store telemetry-collector memory-service channel-adapters-cfg system-control nil nil))
  ([cfg event-sink store telemetry-collector memory-service channel-adapters-cfg system-control observer trace]
   (let [http-cfg (get cfg :http)
         fs-cfg (get cfg :fs)
         shell-cfg (get cfg :shell)
         todo-cfg (get cfg :todo)
         telegram-cfg (get channel-adapters-cfg :telegram)
         policy-hook (create-tool-policy-hook cfg)
         registry (tools/create-registry
                   {:event-sink event-sink
                    :approval-check (tool-approvals/create-policy-hook store)
                    :before-execute policy-hook
                    :activity-executor (when store
                                         (fn [activity f]
                                           (:result (runtime/execute-activity!
                                                     (runtime/create-runtime-service {:store store})
                                                     activity
                                                     f))))
                    :after-execute (fn [{:keys [tool context duration-ms is-error error input result] :as hook}]
                                     (let [observation {:tool-name (:name tool)
                                                        :duration-ms duration-ms
                                                        :success? (not is-error)
                                                        :error error
                                                        :user (:user context)}]
                                       (if observer
                                         (do
                                           (telemetry/record-event! observer {:event-type :tool/call
                                                                              :payload observation})
                                           (telemetry/record-metric! observer {:metric-type :request-latency-ms
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
                     (fs-tool/create-fs-tools fs-cfg)))

       memory-service
       (as-> registry*
             (reduce tools/register-tool
                     registry*
                     (conj (memory-tool/create-memory-tools memory-service)
                           (memory-tool/create-message-search-tool memory-service))))

       (and store (not= false (:enabled todo-cfg)))
       (as-> registry*
             (reduce tools/register-tool
                     registry*
                     (todo-tool/create-todo-tools store)))

       (not= false (:enabled shell-cfg))
       (tools/register-tool (shell-tool/create-shell-tool shell-cfg))

       (telegram-tool/enabled? telegram-cfg)
       (-> (tools/register-tool (telegram-tool/create-send-photo-tool telegram-cfg))
           (tools/register-tool (telegram-tool/create-send-document-tool telegram-cfg)))

       system-control
       (tools/register-tool (reload-tool system-control))))))

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

(defn agent-tool-context
  [system agent agent-id context]
  (let [profile-permissions (tool-permissions system :agent)
        context-permissions (set (:permissions context))]
    (merge context
           {:permissions (set/union profile-permissions context-permissions)
            :yolo? (true? (get-in system [:config :tools :yolo?]))
            :user (or (:user context) agent-id)
            :allowed-tools (tool-name-set (:tool-access agent))})))

(defn execute-agent-tool!
  ([system agent-id tool-name input]
   (execute-agent-tool! system agent-id tool-name input {}))
  ([system agent-id tool-name input context]
   (let [agent (or (orchestrator/get-agent (:orchestrator system) agent-id)
                   (throw (ex-info "Agent not found"
                                   {:type :agent-not-found
                                    :agent-id agent-id})))]
     (execute-tool system tool-name input
                   (agent-tool-context system agent agent-id context)))))

(defn execute-agent-tool-batch!
  [system agent-id calls context opts]
  (let [agent (or (orchestrator/get-agent (:orchestrator system) agent-id)
                  (throw (ex-info "Agent not found"
                                  {:type :agent-not-found
                                   :agent-id agent-id})))
        calls* (mapv (fn [call]
                       (update call :context #(agent-tool-context system
                                                                  agent
                                                                  agent-id
                                                                  (merge context (or % {})))))
                     calls)]
    (runtime-tools/execute-batch! (:tool-registry system)
                                  calls*
                                  {}
                                  (select-keys opts [:mode
                                                     :tool-execution-modes
                                                     :max-parallelism
                                                     :cancellation-token
                                                     :cancelled?]))))
