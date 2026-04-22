(ns agent.planner
  "Schema-constrained planner facade."
  (:require
   [agent.kernel.schema :as kernel-schema]
   [agent.llm.core :as llm]
   [agent.telemetry :as telemetry]
   [cheshire.core :as json]))

(defn- duration-ms [start-ns]
  (/ (double (- (System/nanoTime) start-ns)) 1000000.0))

(defn- parse-content [content]
  (cond
    (map? content) content
    (string? content) (json/parse-string content true)
    :else (throw (ex-info "Planner returned unsupported content"
                          {:type :validation-failed
                           :content content}))))

(defn planner-system-prompt []
  (str "Return one JSON object matching supplied schema. "
       "Use directives array for batch executor work. "
       "No prose."))

(defn plan-step!
  [provider {:keys [messages state tools telemetry agent-id model] :as request}]
  (let [start-ns (System/nanoTime)
        messages* (vec (concat [{:role "system" :content (planner-system-prompt)}]
                               (or messages [])))
        llm-request (merge
                     (select-keys request [:temperature :max-tokens :top-p :cache-control])
                     {:model model
                      :messages messages*
                      :tools tools
                      :structured-output {:name "agent_planner_step"
                                          :strict? true
                                          :schema (kernel-schema/planner-json-schema)}
                      :metadata {:planner true
                                 :state state}})]
    (try
      (let [response (llm/invoke provider llm-request)
            step (-> (:content response)
                     parse-content
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
