(ns agent.api.handlers.agents
  (:require
   [agent.api.errors :as errors]
   [agent.api.handlers.tools :as tools-h]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.validation :as v]
   [agent.kernel :as kernel]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.orchestrator :as orchestrator]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(defn create [system request]
  (let [body (h/read-json-body request)
        name (or (:name body) "Subagent")
        kind (:kind body)
        role (or (:role body) "worker")
        parent-id (:parent_id body)
        system-prompt (:system_prompt body)
        capabilities (or (:capabilities body) [])
        tool-access (or (:tool_access body) [])
        memory-scopes (or (:memory_scopes body) [])
        budgets (or (:budgets body) {})
        task (:task body)
        allow-direct? (boolean (:allow_direct body))
        trusted-peers (or (:trusted_peers body) [])
        trust-policies (v/normalize-trust-policies-body (:trust_policies body))
        rate-limit-per-minute (:rate_limit_per_minute body)]
    (when kind (v/ensure-string! :kind kind))
    (when parent-id (v/ensure-string! :parent_id parent-id))
    (when system-prompt (v/ensure-string! :system_prompt system-prompt))
    (when task
      (when-not (map? task)
        (throw (errors/api-error 400 "bad_request" "task must be an object"))))
    (v/ensure-string-vec! :capabilities capabilities)
    (v/ensure-string-vec! :tool_access tool-access)
    (v/ensure-string-vec! :memory_scopes memory-scopes)
    (v/ensure-string-vec! :trusted_peers trusted-peers)
    (responses/json-response 201
                             (ser/agent->response
                              (orchestrator/spawn-agent! (:orchestrator system)
                                                         {:name name
                                                          :kind kind
                                                          :role role
                                                          :parent-id parent-id
                                                          :system-prompt system-prompt
                                                          :capabilities capabilities
                                                          :tool-access tool-access
                                                          :memory-scopes memory-scopes
                                                          :budgets budgets
                                                          :task task
                                                          :allow-direct? allow-direct?
                                                          :trusted-peers trusted-peers
                                                          :trust-policies trust-policies
                                                          :interop-rate-limit-per-minute rate-limit-per-minute})))))

(defn list-agents [system _request]
  (responses/json-response 200
                           {:data (mapv ser/agent->response
                                        (orchestrator/list-agents (:orchestrator system)))}))

(defn list-messages [system _request agent-id]
  (try
    (responses/json-response 200
                             {:data (mapv ser/message->response
                                          (orchestrator/list-agent-messages (:orchestrator system) agent-id))})
    (catch Exception e
      (if (= :agent-not-found (:type (ex-data e)))
        (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
        (throw e)))))

(defn send-message [system request agent-id]
  (let [body (h/read-json-body request)
        role (or (:role body) "user")
        content (:content body)]
    (try
      (let [result (orchestrator/send-agent-message! (:orchestrator system)
                                                     (:llm-provider system)
                                                     agent-id
                                                     {:role role :content content})]
        (responses/json-response 200
                                 {:agent (ser/agent->response (:agent result))
                                  :input (ser/message->response (:input result))
                                  :response (ser/message->response (:response result))}))
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          (throw e))))))

(defn tool-execute [system request agent-id tool-name]
  (let [body (h/read-json-body request)
        input (:input body)
        approval-id (:approval_id body)
        tool-key (keyword tool-name)]
    (try
      (responses/json-response
       200
       {:data (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)]
                (when-not agent
                  (throw (ex-info "Agent not found"
                                  {:type :agent-not-found :agent-id agent-id})))
                (tools/execute-tool (:tool-registry system)
                                    tool-key
                                    input
                                    (merge (tools-h/execution-context system :agent tool-key input
                                                                      {:approval-id approval-id
                                                                       :user (str "agent:" agent-id)
                                                                       :request-id (:request_id body)
                                                                       :activity (:activity body)})
                                           {:allowed-tools (set (:tool-access agent))})))})
      (catch Exception e
        (let [data (ex-data e)]
          (case (:type data)
            :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
            :tool-blocked (throw (errors/api-error 403 "tool_blocked" (.getMessage e) (dissoc data :type)))
            (throw (errors/tool-error->api-error e))))))))

(defn orchestrator-spawn-worker [system request agent-id]
  (let [body (h/read-json-body request)
        name (:name body)
        role (:role body)
        task (:task body)
        capabilities (or (:capabilities body) [])
        tool-access (or (:tool_access body) [])
        memory-scopes (or (:memory_scopes body) [])
        budgets (or (:budgets body) {})
        system-prompt (:system_prompt body)]
    (when name (v/ensure-string! :name name))
    (when role (v/ensure-string! :role role))
    (when-not (map? task)
      (throw (errors/api-error 400 "bad_request" "task must be an object")))
    (v/ensure-string-vec! :capabilities capabilities)
    (v/ensure-string-vec! :tool_access tool-access)
    (v/ensure-string-vec! :memory_scopes memory-scopes)
    (try
      (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)]
        (when-not agent
          (throw (ex-info "Agent not found" {:type :agent-not-found :agent-id agent-id})))
        (when-not (= "orchestrator" (:kind agent))
          (throw (ex-info "Agent is not an orchestrator"
                          {:type :validation-failed :agent-id agent-id})))
        (let [step (kernel/orchestrator-spawn-worker-step
                    {:task task
                     :worker-name (or name "Task Worker")
                     :worker-role (or role "worker")
                     :capability-bundle {:capabilities capabilities
                                         :tool-access tool-access}
                     :memory-scopes memory-scopes
                     :budgets budgets
                     :system-prompt system-prompt})
              spawn (-> step :directives first :payload)
              worker (orchestrator/spawn-agent! (:orchestrator system)
                                                {:name (:name spawn)
                                                 :kind "worker"
                                                 :role (:role spawn)
                                                 :parent-id agent-id
                                                 :system-prompt (:system-prompt spawn)
                                                 :capabilities capabilities
                                                 :tool-access tool-access
                                                 :memory-scopes memory-scopes
                                                 :budgets budgets
                                                 :task task})
              receipt {:directive :spawn-worker
                       :status :ok
                       :worker-id (:id worker)}]
          ((:event-sink system)
           {:event-type :agent.kernel.step.executed
            :entity-type :agent
            :entity-id agent-id
            :payload {:directive-count 2
                      :receipt-count 1
                      :receipts [receipt]}})
          (responses/json-response 201
                                   {:data {:worker (ser/agent->response worker)
                                           :receipts [receipt]}})))
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          :validation-failed (throw (errors/api-error 409 "invalid_orchestrator" (.getMessage e)))
          (throw e))))))

(defn- normalize-step-body [body]
  (let [directives (or (:directives body) [])]
    (when-not (vector? directives)
      (throw (errors/api-error 400 "bad_request" "directives must be a vector")))
    {:schema-version (or (:schema-version body) (:schema_version body))
     :state (or (:state body) {})
     :directives (mapv (fn [directive]
                         (when-not (map? directive)
                           (throw (errors/api-error 400 "bad_request" "directive must be an object")))
                         (kernel/directive (keyword (:type directive))
                                           (or (:payload directive) {})))
                       directives)
     :receipts (vec (or (:receipts body) []))}))

(defrecord ApiKernelOps [system]
  kernel-ops/KernelOps
  (spawn-task-worker! [_ {:keys [task name role capability-bundle memory-scopes budgets system-prompt parent-id]}]
    (orchestrator/spawn-agent! (:orchestrator system)
                               {:name name
                                :kind "worker"
                                :role role
                                :parent-id parent-id
                                :system-prompt system-prompt
                                :capabilities (vec (or (:capabilities capability-bundle) []))
                                :tool-access (vec (or (:tool-access capability-bundle) []))
                                :memory-scopes (vec memory-scopes)
                                :budgets budgets
                                :task task}))
  (execute-agent-tool! [_ target-agent-id tool-name input context]
    (let [target-agent (orchestrator/get-agent (:orchestrator system) target-agent-id)]
      (when-not target-agent
        (throw (ex-info "Agent not found" {:type :agent-not-found :agent-id target-agent-id})))
      (tools/execute-tool (:tool-registry system)
                          tool-name
                          input
                          (merge context
                                 {:allowed-tools (set (:tool-access target-agent))
                                  :permissions (tools-h/configured-tool-permissions system :agent)
                                  :yolo? (true? (get-in system [:config :tools :yolo?]))
                                  :user (or (:user context) (str "agent:" target-agent-id))}))))
  (send-agent-message! [_ agent-id message]
    (orchestrator/send-agent-message! (:orchestrator system)
                                      (:llm-provider system)
                                      agent-id
                                      message))
  (patch-agent-state! [_ agent-id patch]
    (orchestrator/patch-agent-state! (:orchestrator system) agent-id patch))
  (set-agent-status! [_ agent-id status]
    (orchestrator/set-agent-status! (:orchestrator system) agent-id status))
  (emit-kernel-event! [_ event]
    ((:event-sink system) event)))

(defn step-execute [system request agent-id]
  (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)
        body (h/read-json-body request)
        step (normalize-step-body body)
        yolo-override (if (contains? body :yolo?)
                        (:yolo? body)
                        (:yolo body))
        opts {:yolo? (if (or (contains? body :yolo?) (contains? body :yolo))
                       (true? yolo-override)
                       (true? (get-in system [:config :tools :yolo?])))}]
    (when-not agent
      (throw (errors/api-error 404 "agent_not_found" "Agent not found")))
    (let [ops (->ApiKernelOps system)]
      (responses/json-response 200
                               {:data (kernel-runtime/execute-step! ops agent-id step opts)}))))

(defn consume-inbox [system _request agent-id]
  (try
    (let [result (orchestrator/consume-agent-inbox! (:orchestrator system)
                                                    (:llm-provider system)
                                                    agent-id)]
      (responses/json-response 200
                               {:agent (ser/agent->response (:agent result))
                                :consumed (:consumed result)
                                :response (some-> (:response result) ser/message->response)}))
    (catch Exception e
      (if (= :agent-not-found (:type (ex-data e)))
        (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
        (throw e)))))

(defn interop [system _request agent-id]
  (try
    (responses/json-response 200
                             {:data (orchestrator/describe-agent-interop (:orchestrator system) agent-id)})
    (catch Exception e
      (if (= :agent-not-found (:type (ex-data e)))
        (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
        (throw e)))))

(defn interop-capabilities [system request agent-id]
  (let [body (h/read-json-body request)
        capabilities (or (:capabilities body) [])
        tool-access (or (:tool_access body) [])
        memory-scopes (or (:memory_scopes body) [])
        budgets (or (:budgets body) {})
        allow-direct? (boolean (:allow_direct body))
        trusted-peers (or (:trusted_peers body) [])
        trust-policies (v/normalize-trust-policies-body (:trust_policies body))
        rate-limit-per-minute (:rate_limit_per_minute body)]
    (v/ensure-string-vec! :capabilities capabilities)
    (v/ensure-string-vec! :tool_access tool-access)
    (v/ensure-string-vec! :memory_scopes memory-scopes)
    (v/ensure-string-vec! :trusted_peers trusted-peers)
    (try
      (responses/json-response
       200
       {:data (orchestrator/register-agent-capabilities! (:orchestrator system)
                                                         agent-id
                                                         {:capabilities capabilities
                                                          :tool-access tool-access
                                                          :memory-scopes memory-scopes
                                                          :budgets budgets
                                                          :allow-direct? allow-direct?
                                                          :trusted-peers trusted-peers
                                                          :trust-policies trust-policies
                                                          :interop-rate-limit-per-minute rate-limit-per-minute})})
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          (throw e))))))

(defn interop-message-post [system request agent-id]
  (let [body (h/read-json-body request)
        from-agent-id (:from_agent_id body)
        to-agent-ref (or (:to_agent_ref body) agent-id)
        content (:content body)
        message-type (or (:message_type body) "request")
        route (:route body)
        delivery-mode (or (:delivery_mode body) "at-most-once")
        request-id (:request_id body)]
    (try
      (responses/json-response
       201
       {:data (ser/interop->response
               (orchestrator/send-interop-message! (:orchestrator system)
                                                   from-agent-id
                                                   to-agent-ref
                                                   {:message-type message-type
                                                    :route route
                                                    :delivery-mode delivery-mode
                                                    :request-id request-id
                                                    :content content}))})
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          :permission-denied (throw (errors/api-error 403 "permission_denied" "Direct interop denied"))
          :rate-limited (throw (errors/api-error 429 "rate_limited" "Interop rate limit exceeded"))
          :validation-failed (throw (errors/api-error 400 "validation_failed" (.getMessage e)))
          (throw e))))))

(defn interop-messages-list [system request agent-id]
  (let [params (h/query-params request)
        direction (some-> (:direction params) str/lower-case keyword)
        status (:status params)]
    (try
      (responses/json-response 200
                               {:data (mapv ser/interop->response
                                            (orchestrator/list-interop-messages (:orchestrator system)
                                                                                agent-id
                                                                                (cond-> {}
                                                                                  direction (assoc :direction direction)
                                                                                  status (assoc :status status))))})
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          (throw e))))))

(defn interop-ack [system request agent-id message-id]
  (let [body (h/read-json-body request)
        ack-type (or (:ack_type body) "ack")]
    (try
      (responses/json-response
       200
       {:data (ser/interop->response
               (orchestrator/acknowledge-interop-message! (:orchestrator system)
                                                          agent-id
                                                          message-id
                                                          {:ack-type ack-type}))})
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          :interop-message-not-found (throw (errors/api-error 404 "interop_message_not_found" "Interop message not found"))
          :permission-denied (throw (errors/api-error 403 "permission_denied" "Interop ack denied"))
          :validation-failed (throw (errors/api-error 400 "validation_failed" (.getMessage e)))
          (throw e))))))

(defn interop-retry [system _request agent-id message-id]
  (try
    (responses/json-response 200
                             {:data (ser/interop->response
                                     (orchestrator/retry-interop-message! (:orchestrator system)
                                                                          agent-id
                                                                          message-id))})
    (catch Exception e
      (case (:type (ex-data e))
        :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
        :interop-message-not-found (throw (errors/api-error 404 "interop_message_not_found" "Interop message not found"))
        :permission-denied (throw (errors/api-error 403 "permission_denied" "Interop retry denied"))
        :validation-failed (throw (errors/api-error 400 "validation_failed" (.getMessage e)))
        :rate-limited (throw (errors/api-error 429 "rate_limited" "Interop rate limit exceeded"))
        (throw e)))))
