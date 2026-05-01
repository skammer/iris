(ns agent.planner
  "Native tool-calling planner facade."
  (:require
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm]
   [agent.prompts :as prompts]
   [agent.telemetry :as telemetry]))

(defn- duration-ms [start-ns]
  (/ (double (- (System/nanoTime) start-ns)) 1000000.0))

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

(defn- build-llm-request [{:keys [messages state tools model] :as request}]
  (let [tool-defs (native-tool-definitions tools)
        messages* (vec (concat [{:role "system" :content (planner-system-prompt)}]
                               (or messages [])))]
    (cond-> (merge
             (select-keys request [:temperature :max-tokens :top-p :cache-control
                                   :on-content-delta])
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
  [provider {:keys [telemetry agent-id] :as request}]
  (let [start-ns (System/nanoTime)
        llm-request (build-llm-request request)]
    (try
      (let [response (llm/invoke provider llm-request)
            step (-> response
                     response->step
                     kernel-schema/normalize-step
                     kernel-schema/validate-step!)]
        (telemetry/record-planner! telemetry
                                   {:agent-id agent-id
                                    :duration-ms (duration-ms start-ns)
                                    :success? true
                                    :directive-count (count (:directives step))})
        (assoc step :llm-response response))
      (catch Exception e
        (telemetry/record-planner! telemetry
                                   {:agent-id agent-id
                                    :duration-ms (duration-ms start-ns)
                                    :success? false
                                    :error e})
        (throw e)))))
