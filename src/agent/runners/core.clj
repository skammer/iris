(ns agent.runners.core
  "Substrate-independent runner protocol."
  (:require
   [clojure.string :as str])
  (:import
   (java.util UUID)))

(def supported-substrates
  #{:local-unsandboxed :bubblewrap :seatbelt :docker :podman})

(defprotocol IRunner
  (launch [this run-spec])
  (signal [this run-id command])
  (status [this run-id])
  (stop [this run-id]))

(defn random-token []
  (str (UUID/randomUUID)))

(defn normalize-substrate [substrate]
  (let [substrate* (cond
                     (keyword? substrate) substrate
                     (string? substrate) (keyword (str/trim substrate))
                     :else substrate)]
    (when-not (contains? supported-substrates substrate*)
      (throw (ex-info "Unsupported runner substrate"
                      {:type :unsupported-runner-substrate
                       :substrate substrate
                       :supported (vec (sort supported-substrates))})))
    substrate*))

(defn command-vector? [command]
  (and (vector? command)
       (seq command)
       (every? string? command)))

(defn- require-value! [field value]
  (when (or (nil? value)
            (and (string? value) (str/blank? value)))
    (throw (ex-info (str "Run " (name field) " is required")
                    {:type :runner-run-spec-required
                     :field field}))))

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
    :or {capabilities []}}]
  (require-value! :run-id run-id)
  (require-value! :agent-id agent-id)
  {:run-id run-id
   :agent-id agent-id
   :parent-run-id parent-run-id
   :lease-id lease-id
   :name name
   :substrate (normalize-substrate substrate)
   :capabilities (vec capabilities)
   :network-identity network-identity
   :bootstrap-token bootstrap-token
   :bootstrap-spec bootstrap-spec
   :requested-by requested-by
   :runner-options (or runner-options {})})
