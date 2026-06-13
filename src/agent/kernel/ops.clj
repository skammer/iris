(ns agent.kernel.ops
  "Protocols the pure kernel uses to call host capabilities: tool execution,
   event emission, supported directives, and batched tool execution.")

(defprotocol KernelOps
  (execute-agent-tool! [this agent-id tool-name input context])
  (emit-kernel-event! [this event]))

(defprotocol KernelCapabilities
  (supported-directives [this]))

(defprotocol KernelToolBatchOps
  (execute-agent-tool-batch! [this agent-id calls context opts]))
