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
       "Set schema-version to " kernel-schema/current-step-schema-version ". "
       "Use directives array for batch executor work. "
       "Never call tools directly; emit tool-call directives instead. "
       "No prose."))

(defn- tool-inventory-message [tools]
  (when (seq tools)
    {:role "system"
     :content (str "Available tools JSON. To use one, emit a tool-call directive: "
                   (json/generate-string tools))}))

(defn- build-llm-request [{:keys [messages state tools model] :as request}]
  (let [messages* (vec (concat [{:role "system" :content (planner-system-prompt)}]
                               (when-let [tool-message (tool-inventory-message tools)]
                                 [tool-message])
                               (or messages [])))]
    (merge
     (select-keys request [:temperature :max-tokens :top-p :cache-control])
     {:model model
      :messages messages*
      :structured-output {:name "agent_planner_step"
                          :strict? true
                          :schema (kernel-schema/planner-json-schema)}
      :metadata {:planner true
                 :state state}})))

(defn- response->step [response]
  (if (seq (:tool-calls response))
    {:schema-version kernel-schema/current-step-schema-version
     :state {}
     :directives (llm/tool-calls->directives (:tool-calls response))
     :receipts []}
    (parse-content (:content response))))

(defn- repair-messages [messages response error]
  (conj messages
        {:role "assistant" :content (or (:content response) "")}
        {:role "user"
         :content (str "Previous output failed validation. Return only corrected JSON for schema "
                       kernel-schema/current-step-schema-version
                       ". Error: "
                       (.getMessage ^Throwable error))}))

(defn plan-step!
  [provider {:keys [messages state tools telemetry agent-id model] :as request}]
  (let [start-ns (System/nanoTime)
        repair-attempts (long (or (:repair-attempts request) 1))
        llm-request (build-llm-request request)]
    (try
      (loop [attempt 0
             request* llm-request
             last-error nil]
        (let [response (llm/invoke provider request*)]
          (let [result (try
                         {:step (-> response
                                    response->step
                                    kernel-schema/normalize-step
                                    kernel-schema/validate-step!)}
                         (catch Exception e
                           {:error e}))]
            (if-let [step (:step result)]
              (do
                (telemetry/record-planner! telemetry
                                           {:agent-id agent-id
                                            :duration-ms (duration-ms start-ns)
                                            :success? true
                                            :directive-count (count (:directives step))})
                (cond-> (assoc step :llm-response response)
                  last-error (assoc :repair-error last-error)))
              (let [error (:error result)]
                (if (< attempt repair-attempts)
                  (recur (inc attempt)
                         (assoc request* :messages (repair-messages (:messages request*) response error))
                         error)
                  (throw error)))))))
      (catch Exception e
        (telemetry/record-planner! telemetry
                                   {:agent-id agent-id
                                    :duration-ms (duration-ms start-ns)
                                    :success? false
                                    :error e})
        (throw e)))))
