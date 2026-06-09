(ns agent.api.handlers.agents
  (:require
   [agent.api.errors :as errors]
   [agent.api.handlers.tools :as tools-h]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.kernel.schema :as kernel-schema]
   [agent.kernel.service :as kernel-service]
   [agent.orchestrator :as orchestrator]
   [clojure.string :as str]))

(defn- normalize-trust-policies-body [policies]
  (reduce-kv (fn [acc peer-ref policy]
               (assoc acc (if (keyword? peer-ref) (name peer-ref) (str peer-ref))
                      {:message-types (vec (:message_types policy []))
                       :routes (vec (:routes policy []))
                       :required-capabilities (vec (:required_capabilities policy []))}))
             {}
             (or policies {})))

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
        trust-policies (normalize-trust-policies-body (:trust_policies body))
        rate-limit-per-minute (:rate_limit_per_minute body)]
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
       {:data (kernel-service/execute-agent-tool!
               system
               agent-id
               tool-key
               input
               (tools-h/execution-context system :agent tool-key input
                                          {:approval-id approval-id
                                           :user (str "agent:" agent-id)
                                           :request-id (:request_id body)
                                           :activity (:activity body)}))})
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
    (try
      (let [{:keys [worker receipts]}
            (kernel-service/orchestrator-spawn-worker!
             system
             agent-id
             {:task task
              :name name
              :role role
              :capabilities capabilities
              :tool-access tool-access
              :memory-scopes memory-scopes
              :budgets budgets
              :system-prompt system-prompt})]
          (responses/json-response 201
                                   {:data {:worker (ser/agent->response worker)
                                           :receipts receipts}}))
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          :validation-failed (throw (errors/api-error 409 "invalid_orchestrator" (.getMessage e)))
          (throw e))))))

(defn- normalize-step-body
  [body]
  (kernel-schema/validate-step!
   {:schema-version (or (:schema-version body) (:schema_version body))
    :state (or (:state body) {})
    :directives (vec (or (:directives body) []))
    :receipts (vec (or (:receipts body) []))}))

(defn- step-options
  [system body]
  (let [explicit-yolo? (or (contains? body :yolo?) (contains? body :yolo))
        yolo-value (if (contains? body :yolo?)
                     (:yolo? body)
                     (:yolo body))]
    {:yolo? (if explicit-yolo?
              (true? yolo-value)
              (true? (get-in system [:config :tools :yolo?])))}))

(defn step-execute [system request agent-id]
  (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)
        body (h/read-json-body request)]
    (when-not agent
      (throw (errors/api-error 404 "agent_not_found" "Agent not found")))
    (try
      (responses/json-response
       200
       {:data (kernel-service/execute-step! system
                                            agent-id
                                            (normalize-step-body body)
                                            (step-options system body))})
      (catch Exception e
        (if (= :validation-failed (:type (ex-data e)))
          (throw (errors/api-error 400 "validation_failed" (.getMessage e) (dissoc (ex-data e) :type)))
          (throw e))))))

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
        trust-policies (normalize-trust-policies-body (:trust_policies body))
        rate-limit-per-minute (:rate_limit_per_minute body)]
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
  (let [{:keys [direction status]} (-> request :parameters :query)
        direction (some-> direction str/lower-case keyword)]
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
