(ns agent.planner
  "Native tool-calling planner facade."
  (:require
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm]
   [agent.prompts :as prompts]
   [agent.runtime.trace :as runtime-trace]
   [agent.telemetry :as telemetry]
   [agent.util :as util]))

(defn planner-system-prompt []
  (prompts/load-prompt "planner-system"))

(defn- native-tool-definition
  [{tool-name :name description :description input-schema :input-schema}]
  {:type "function"
   :function {:name (name tool-name)
              :description description
              :parameters input-schema}})

(defn- native-tool-definitions [tools]
  (mapv native-tool-definition (or tools [])))

(defn- build-llm-request [{:keys [messages state tools model system-prompt] :as request}]
  (let [tool-defs (native-tool-definitions tools)
        messages* (vec (concat [{:role "system" :content (or system-prompt
                                                              (planner-system-prompt))}]
                               (or messages [])))]
    (cond-> (merge
             (select-keys request [:temperature :max-tokens :top-p :cache-control
                                   :tool-choice :on-content-delta :on-thinking-delta
                                   :session-id])
             {:model model
              :messages messages*
              :metadata {:planner true
                         :state state}})
      (seq tool-defs) (assoc :tools tool-defs))))

(defn- response->step [response]
  (if-let [tool-calls (seq (:tool-calls response))]
    {:schema-version kernel-schema/current-step-schema-version
     :state {}
     :directives (vec (llm/tool-calls->directives tool-calls))
     :receipts []}
    {:schema-version kernel-schema/current-step-schema-version
     :state {}
     :directives [{:type :complete
                   :payload {:result (or (:content response) "")}}]
     :receipts []}))

(defn plan-step!
  [provider {:keys [telemetry observer trace agent-id model request-id] :as request}]
  (let [start-ns (System/nanoTime)
        llm-request (build-llm-request request)]
    (try
      (let [response (llm/invoke provider llm-request)
            duration (util/duration-ms start-ns)
            step (-> response
                     response->step
                     kernel-schema/normalize-step
                     kernel-schema/validate-step!)]
        (telemetry/record-planner! telemetry
                                   {:agent-id agent-id
                                    :duration-ms duration
                                    :success? true
                                    :directive-count (count (:directives step))})
        (when observer
          (telemetry/record-event! observer
                                   {:event-type :llm/call
                                    :payload {:agent-id agent-id
                                              :model model
                                              :duration-ms duration
                                              :success? true
                                              :tokens (get-in response [:usage :tokens])
                                              :prompt-tokens (get-in response [:usage :prompt-tokens])
                                              :completion-tokens (get-in response [:usage :completion-tokens])
                                              :cached-tokens (get-in response [:usage :cached-tokens])}}))
        (runtime-trace/record-event! trace
                                     {:event-type :llm.call
                                      :turn-id request-id
                                      :model model
                                      :success true
                                      :payload {:agent-id agent-id
                                                :duration-ms duration
                                                :usage (:usage response)
                                                :stop-reason (:stop-reason response)
                                                :tool-call-count (count (:tool-calls response))}})
        (assoc step :llm-response response))
      (catch Exception e
        (let [duration (util/duration-ms start-ns)]
          (telemetry/record-planner! telemetry
                                     {:agent-id agent-id
                                      :duration-ms duration
                                      :success? false
                                      :error e})
          (when observer
            (telemetry/record-event! observer
                                     {:event-type :llm/call
                                      :payload {:agent-id agent-id
                                                :model model
                                                :duration-ms duration
                                                :success? false
                                                :error e}}))
          (runtime-trace/record-event! trace
                                       {:event-type :llm.call
                                        :turn-id request-id
                                        :model model
                                        :success false
                                        :error-message (.getMessage e)
                                        :payload {:agent-id agent-id
                                                  :duration-ms duration}}))
        (throw e)))))
