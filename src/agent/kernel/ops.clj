(ns agent.kernel.ops
  "Kernel runtime host operations.")

(defprotocol KernelOps
  (execute-agent-tool! [this agent-id tool-name input context])
  (emit-kernel-event! [this event]))

(defprotocol KernelCapabilities
  (supported-directives [this]))

(defprotocol KernelToolBatchOps
  (execute-agent-tool-batch! [this agent-id calls context opts]))
