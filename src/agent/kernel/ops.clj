(ns agent.kernel.ops
  "Kernel runtime host operations.")

(defprotocol KernelOps
  (spawn-task-worker! [this spec])
  (execute-agent-tool! [this agent-id tool-name input context])
  (send-agent-message! [this agent-id message])
  (patch-agent-state! [this agent-id patch])
  (set-agent-status! [this agent-id status])
  (emit-kernel-event! [this event]))

(defprotocol KernelToolBatchOps
  (execute-agent-tool-batch! [this agent-id calls context opts]))
