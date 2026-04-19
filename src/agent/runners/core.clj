(ns agent.runners.core
  "Substrate-independent runner protocol."
  (:import
   (java.util UUID)))

(defprotocol IRunner
  (launch [this run-spec])
  (signal [this run-id command])
  (status [this run-id])
  (stop [this run-id]))

(defn random-token []
  (str (UUID/randomUUID)))

(defn create-bootstrap-spec
  [{:keys [run-id agent-id parent-run-id lease-id capabilities network-identity
           checkpoint-seq command-poll-interval-ms heartbeat-interval-ms]
    :or {capabilities []
         checkpoint-seq 0
         command-poll-interval-ms 5000
         heartbeat-interval-ms 10000}}]
  {:run-id run-id
   :agent-id agent-id
   :parent-run-id parent-run-id
   :lease-id lease-id
   :capabilities (vec capabilities)
   :network-identity network-identity
   :checkpoint-seq checkpoint-seq
   :command-poll-interval-ms command-poll-interval-ms
   :heartbeat-interval-ms heartbeat-interval-ms})

(defn create-run-spec
  [{:keys [run-id agent-id parent-run-id lease-id name substrate capabilities
           network-identity bootstrap-token bootstrap-spec requested-by runner-options]
    :or {substrate :local-unsandboxed
         capabilities []}}]
  {:run-id run-id
   :agent-id agent-id
   :parent-run-id parent-run-id
   :lease-id lease-id
   :name name
   :substrate substrate
   :capabilities (vec capabilities)
   :network-identity network-identity
   :bootstrap-token bootstrap-token
   :bootstrap-spec bootstrap-spec
   :requested-by requested-by
   :runner-options (or runner-options {})})
