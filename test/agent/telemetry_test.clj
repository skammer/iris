(ns agent.telemetry-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.telemetry :as telemetry]
   [clojure.core.async :as async]
   [clojure.test :refer :all]))

(defrecord TelemetryProvider []
  llm-core/ILLMProvider
  (complete [_ _ _] "hello world")
  (stream [_ _ _]
    (let [ch (async/chan)]
      (async/close! ch)
      ch))
  (embed [_ _ _] [0.1])
  (list-models [_] [])
  (get-capabilities [_ _] {})
  (estimate-cost [_ _ model]
    {:model model
     :tokens 20
     :prompt-tokens 7
     :cost-usd 0.02}))

(deftest run-latency-percentiles
  (let [collector (telemetry/create-collector {:enabled true})]
    (telemetry/record-system-event! collector
                                    {:event-type "agent.run.requested"
                                     :entity-type "agent_run"
                                     :entity-id "run-1"
                                     :payload {:agent-id "agent-1"}
                                     :created-at "2026-04-21T00:00:00Z"})
    (telemetry/record-system-event! collector
                                    {:event-type "agent.run.completed"
                                     :entity-type "agent_run"
                                     :entity-id "run-1"
                                     :payload {:status "completed"}
                                     :created-at "2026-04-21T00:00:00.150Z"})
    (is (= 150 (get-in (telemetry/snapshot collector) [:runs :latency-ms :p50-ms])))
    (is (= 150 (get-in (telemetry/snapshot collector) [:runs :latency-ms :p95-ms])))))

(deftest llm-token-spend-by-agent
  (let [collector (telemetry/create-collector {:enabled true})
        provider (->TelemetryProvider)
        content (telemetry/complete-with-telemetry! collector
                                                   provider
                                                   [{:role "user" :content "hello"}]
                                                   {:model "model-a"}
                                                   {:agent-id "agent-1"})
        snapshot (telemetry/snapshot collector)]
    (is (= "hello world" content))
    (is (= 1 (get-in snapshot [:agents "agent-1" :calls])))
    (is (= 20 (get-in snapshot [:agents "agent-1" :tokens])))
    (is (= 7 (get-in snapshot [:agents "agent-1" :prompt-tokens])))
    (is (= 0.02 (get-in snapshot [:agents "agent-1" :cost-usd])))))

(deftest tool-error-rate
  (let [collector (telemetry/create-collector {:enabled true})]
    (telemetry/record-tool! collector {:tool-name :shell
                                       :duration-ms 10
                                       :success? true
                                       :user "agent-1"})
    (telemetry/record-tool! collector {:tool-name :shell
                                       :duration-ms 20
                                       :success? false
                                       :error (ex-info "failed" {:type :boom})
                                       :user "agent-1"})
    (is (= 2 (get-in (telemetry/snapshot collector) [:tools "shell" :calls])))
    (is (= 1 (get-in (telemetry/snapshot collector) [:tools "shell" :errors])))
    (is (= 0.5 (get-in (telemetry/snapshot collector) [:tools "shell" :error-rate])))))
