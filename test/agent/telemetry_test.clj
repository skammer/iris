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

(defrecord CountingObserver [events metrics]
  telemetry/IObserver
  (record-event! [_ event] (swap! events conj event))
  (record-metric! [_ metric] (swap! metrics conj metric))
  (flush! [_] nil)
  (observer-name [_] "counting"))

(defrecord FailingObserver []
  telemetry/IObserver
  (record-event! [_ _] (throw (ex-info "observer boom" {})))
  (record-metric! [_ _] (throw (ex-info "observer boom" {})))
  (flush! [_] (throw (ex-info "observer boom" {})))
  (observer-name [_] "failing"))

(defrecord ToolCallProvider []
  llm-core/ILLMProvider
  (complete [_ _ _] "ignored")
  (stream [_ _ _] nil)
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {})
  (estimate-cost [_ _ model] {:model model :tokens 0 :prompt-tokens 0 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    {:role "assistant"
     :content "calling a tool"
     :tool-calls [{:id "call-1" :function {:name "list_dir"}}]
     :usage {:prompt-tokens 11 :completion-tokens 4 :tokens 15}
     :stop-reason :tool_use})
  (generate [this messages opts] (llm-core/invoke this (assoc opts :messages messages))))

(deftest complete-with-telemetry-captures-tool-calls-and-real-usage
  (let [events (atom [])
        observer (->CountingObserver events (atom []))
        content (telemetry/complete-with-telemetry! (telemetry/create-collector {:enabled true})
                                                    (->ToolCallProvider)
                                                    [{:role "user" :content "hi"}]
                                                    {:model "m"}
                                                    {:agent-id "agent-1"
                                                     :observer observer})
        llm-event (first (filter #(= :llm/call (:event-type %)) @events))
        payload (:payload llm-event)]
    (is (= "calling a tool" content) "returns string content for callers")
    (is (= [{:id "call-1" :function {:name "list_dir"}}] (:tool-calls payload))
        "tool calls reach the observer instead of being dropped")
    (is (= 15 (:tokens payload)) "real provider token count, not estimate")
    (is (= 11 (:prompt-tokens payload)))
    (is (= 4 (:completion-tokens payload)))))

(deftest multi-observer-fans-out-and-is-best-effort
  (let [events (atom [])
        metrics (atom [])
        observer (telemetry/->MultiObserver [(->FailingObserver)
                                             (->CountingObserver events metrics)]
                                            true)]
    (telemetry/record-event! observer {:event-type :turn/start :payload {:id "t1"}})
    (telemetry/record-metric! observer {:metric-type :queue-depth :value 2})
    (telemetry/flush! observer)
    (is (= [{:event-type :turn/start :payload {:id "t1"}}] @events))
    (is (= [{:metric-type :queue-depth :value 2}] @metrics))))

(deftest telemetry-collector-observer-records-system-events
  (let [collector (telemetry/create-collector {:enabled true})
        observer (telemetry/create-observer collector {:sinks [:telemetry]
                                                       :best-effort? true})]
    (telemetry/record-event! observer
                             {:event-type :system/event
                              :payload {:event-type "agent.run.requested"
                                        :entity-type "agent_run"
                                        :entity-id "run-1"
                                        :payload {:agent-id "agent-1"}
                                        :created-at "2026-04-21T00:00:00Z"}})
    (is (= 1 (get-in (telemetry/snapshot collector) [:runs :count])))))
