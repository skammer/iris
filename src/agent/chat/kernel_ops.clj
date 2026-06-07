(ns agent.chat.kernel-ops
  "ChatKernelOps: the chat loop's implementation of the kernel ops protocols.
   Routes tool execution through the chat permission/allow-list context."
  (:require
   [agent.chat.util :as util]
   [agent.kernel.ops :as kernel-ops]
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
          :permissions (profile-permissions system :chat)
          :yolo? (true? (get-in system [:config :tools :yolo?]))}
         (select-keys extra-context [:allowed-tools])
         (select-keys context [:allowed-tools])))

(defrecord ChatKernelOps [system session-id request-id extra-context]
  kernel-ops/KernelCapabilities
  (supported-directives [_]
    #{:tool-call :complete})

  kernel-ops/KernelOps
  (spawn-task-worker! [_ _]
    (throw (ex-info "Chat loop cannot spawn workers yet"
                    {:type :unsupported-directive})))
  (execute-agent-tool! [_ _ tool-name input context]
    (tools/execute-tool (:tool-registry system)
                        tool-name
                        input
                        (chat-tool-context system session-id request-id extra-context context)))
  (send-agent-message! [_ _ _]
    (throw (ex-info "Chat loop cannot send agent messages yet"
                    {:type :unsupported-directive})))
  (patch-agent-state! [_ _ patch] patch)
  (set-agent-status! [_ _ _] nil)
  (emit-kernel-event! [_ event] (util/emit! system event))

  kernel-ops/KernelToolBatchOps
  (execute-agent-tool-batch! [_ _ calls context opts]
    (let [calls* (mapv (fn [call]
                         (update call :context #(chat-tool-context system
                                                                   session-id
                                                                   request-id
                                                                   extra-context
                                                                   (merge context (or % {})))))
                       calls)]
      (runtime-tools/execute-batch! (:tool-registry system)
                                    calls*
                                    {}
                                    (select-keys opts [:mode
                                                       :tool-execution-modes
                                                       :max-parallelism
                                                       :cancellation-token
                                                       :cancelled?])))))
