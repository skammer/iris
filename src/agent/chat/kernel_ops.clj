(ns agent.chat.kernel-ops
  "ChatKernelOps: the chat loop's implementation of the kernel ops protocols.
   Routes tool execution through the chat permission/allow-list context."
  (:require
   [agent.chat.util :as util]
   [agent.kernel.ops :as kernel-ops]
   [agent.runtime.calls :as runtime-calls]
   [agent.runtime.tools :as runtime-tools]
   [agent.tools.core :as tools]))

(defn- profile-permissions [system profile]
  (set (get-in system [:config :tools :permissions profile] #{})))

(defn chat-tool-context [system session-id request-id extra-context context]
  (merge (or extra-context {})
         context
         {:user (or session-id "chat")
          :session-id session-id
          :request-id request-id
          :permissions (or (:permissions extra-context)
                           (profile-permissions system (or (:permission-profile extra-context) :chat)))
          :yolo? (true? (get-in system [:config :tools :yolo?]))}
         (select-keys extra-context [:allowed-tools :allowed-actions :permissions])
         (select-keys context [:allowed-tools :allowed-actions])))

(defn- normalize-action [tool-name input]
  (case tool-name
    :http (some-> (:method input) name keyword)
    :homeassistant (some-> (:action input) name keyword)
    nil))

(defn- enforce-action! [tool-name input context]
  (when-let [allowed (get (:allowed-actions context) tool-name)]
    (let [action (normalize-action tool-name input)]
      (when-not (contains? (set allowed) action)
        (throw (tools/tool-error :tool-blocked
                                 "Tool action not allowed in this capability bundle"
                                 {:tool-name tool-name
                                  :action action
                                  :allowed-actions (vec allowed)}))))))

(defrecord ChatKernelOps [system session-id request-id extra-context]
  kernel-ops/KernelCapabilities
  (supported-directives [_]
    #{:tool-call :complete :await})

  kernel-ops/KernelOps
  (execute-agent-tool! [_ _ tool-name input context]
    (let [context* (chat-tool-context system session-id request-id extra-context context)]
      (enforce-action! tool-name input context*)
      (tools/execute-tool (:tool-registry system) tool-name input context*)))
  (emit-kernel-event! [_ event] (util/emit! system event))

  kernel-ops/KernelToolBatchOps
  (execute-agent-tool-batch! [_ _ calls context opts]
    (let [calls* (mapv (fn [call]
                         (let [tool-name (or (:tool-name call) (:name call))
                               tool-name* (if (keyword? tool-name) tool-name (keyword tool-name))
                               input (runtime-calls/call-input call)
                               context* (chat-tool-context system session-id request-id
                                                           extra-context (merge context (or (:context call) {})))]
                           (enforce-action! tool-name* input context*)
                           (assoc call :context context*)))
                       calls)]
      (runtime-tools/execute-batch! (:tool-registry system)
                                    calls*
                                    {}
                                    (select-keys opts [:mode
                                                       :tool-execution-modes
                                                       :max-parallelism
                                                       :cancellation-token
                                                       :event-sink
                                                       :cancelled?])))))
