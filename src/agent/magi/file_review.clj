(ns agent.magi.file-review
  "Bounded read-only tool loop for MAGI triumvirate participants."
  (:refer-clojure :exclude [run!])
  (:require
   [agent.llm.core :as llm]
   [agent.tools.core :as tools]
   [agent.util :as util]
   [cheshire.core :as json]))

(def allowed-tools #{:fs_read :fs_list :fs_search})

(def ^:private verdict-nudge
  (str "File-review budget exhausted. Do not call more tools. "
       "Return the required verdict JSON now. If evidence is insufficient, "
       "use conditional or no and name the missing evidence."))

(defn- tool-definition [{:keys [name description input-schema]}]
  {:type "function"
   :function {:name (clojure.core/name name)
              :description description
              :parameters input-schema}})

(defn- tool-definitions [registry]
  (->> (when registry (tools/list-tools registry))
       (filter #(contains? allowed-tools (:name %)))
       (mapv tool-definition)))

(defn- parse-output [response]
  (let [content (:content response)]
    (cond
      (map? content) content
      (string? content) (json/parse-string content true)
      :else (throw (ex-info "MAGI response is not JSON"
                            {:type :magi-invalid-json
                             :content content})))))

(defn- invoke! [provider role messages schema timeout-ms tool-defs]
  (llm/invoke
   provider
   (cond-> {:messages messages
            :structured-output {:name (str "magi_" (name role))
                                :schema schema}
            :timeout-ms timeout-ms
            :stream? false}
     (seq tool-defs) (assoc :tools tool-defs))))

(defn- call-data [role round idx call]
  (let [directive (llm/tool-call->directive call)
        payload (:payload directive)]
    {:id (or (get-in payload [:context :provider-tool-call-id])
             (str "magi_" (name role) "_" round "_" idx))
     :tool-name (keyword (:tool-name payload))
     :input (:input payload)}))

(defn- assistant-tool-message [response calls]
  {:role "assistant"
   :content (or (:content response) "")
   :tool-calls (mapv (fn [{:keys [id tool-name input]}]
                       {:id id
                        :name tool-name
                        :arguments input})
                     calls)})

(defn- tool-result-message [{:keys [id tool-name]} content]
  {:role "tool"
   :name (name tool-name)
   :tool-call-id id
   :content content})

(defn- bounded-content [value available per-result-limit]
  (let [raw (if (string? value) value (json/generate-string value))
        limit (max 0 (min (long available) (long per-result-limit)))
        content (if (zero? limit)
                  "Evidence budget exhausted; result omitted."
                  (util/truncate raw limit #(str " [truncated " % " chars]")))]
    {:content content
     :chars (if (zero? limit) 0 (count content))
     :truncated? (< limit (count raw))}))

(defn- error-value [message details]
  {:error message
   :details details})

(defn- execute-call
  [registry role request-id call state config executable?]
  (let [{:keys [max-evidence-chars max-tool-result-chars]} config
        start-ns (System/nanoTime)
        available (- max-evidence-chars (:evidence-chars state))
        budget-blocked? (or (not executable?) (not (pos? available)))
        base-trace {:tool (name (:tool-name call))
                    :input (:input call)}]
    (if budget-blocked?
      (let [reason (if executable?
                     "evidence budget exhausted"
                     "tool-call budget exhausted")
            bounded (bounded-content (error-value reason {:type "budget-exhausted"})
                                     available
                                     max-tool-result-chars)]
        (-> state
            (update :messages conj (tool-result-message call (:content bounded)))
            (update :trace conj (assoc base-trace
                                       :status "budget-exhausted"
                                       :duration-ms (util/duration-ms start-ns)))
            (update :evidence-chars + (:chars bounded))))
      (try
        (when-not (contains? allowed-tools (:tool-name call))
          (throw (tools/tool-error :tool-blocked
                                   "Tool is not allowed in MAGI file review"
                                   {:tool-name (:tool-name call)})))
        (when-not registry
          (throw (tools/tool-error :tool-unavailable
                                   "MAGI file-review tool registry is unavailable"
                                   {})))
        (let [result (tools/execute-tool
                      registry
                      (:tool-name call)
                      (:input call)
                      {:permissions #{:filesystem-read}
                       :allowed-tools allowed-tools
                       :user (str "magi:" (name role))
                       :request-id request-id})
              bounded (bounded-content result available max-tool-result-chars)]
          (-> state
              (update :messages conj (tool-result-message call (:content bounded)))
              (update :trace conj (assoc base-trace
                                         :status "succeeded"
                                         :duration-ms (util/duration-ms start-ns)
                                         :truncated? (:truncated? bounded)))
              (update :evidence-chars + (:chars bounded))))
        (catch Exception e
          (let [details (select-keys (ex-data e) [:type :path :tool-name])
                bounded (bounded-content (error-value (.getMessage e) details)
                                         available
                                         max-tool-result-chars)]
            (-> state
                (update :messages conj (tool-result-message call (:content bounded)))
                (update :trace conj (assoc base-trace
                                           :status "failed"
                                           :duration-ms (util/duration-ms start-ns)
                                           :error (.getMessage e)))
                (update :evidence-chars + (:chars bounded)))))))))

(defn- execute-batch [registry role request-id calls state config]
  (let [remaining (- (:max-tool-calls config) (:call-count state))]
    (reduce-kv
     (fn [result idx call]
       (execute-call registry role request-id call result config (< idx remaining)))
     (-> state
         (update :call-count + (min remaining (count calls)))
         (update :requested-call-count + (count calls)))
     calls)))

(defn run!
  [{:keys [provider role system-prompt payload schema timeout-ms config
           tool-registry-fn]}]
  (let [registry (when tool-registry-fn (tool-registry-fn))
        tool-defs (tool-definitions registry)
        request-id (str "magi-file-review-" (name role) "-" (java.util.UUID/randomUUID))
        initial-messages [{:role "system" :content system-prompt}
                          {:role "user" :content (json/generate-string payload)}]]
    (loop [state {:messages initial-messages
                  :trace []
                  :call-count 0
                  :requested-call-count 0
                  :round-count 0
                  :evidence-chars 0}]
      (let [response (invoke! provider role (:messages state) schema timeout-ms tool-defs)
            raw-calls (vec (:tool-calls response))]
        (if (empty? raw-calls)
          {:output (parse-output response)
           :trace (:trace state)
           :budget {:calls (:call-count state)
                    :requested-calls (:requested-call-count state)
                    :rounds (:round-count state)
                    :evidence-chars (:evidence-chars state)
                    :exhausted? false}}
          (let [round (inc (:round-count state))
                calls (mapv #(call-data role round %1 %2) (range) raw-calls)
                state* (-> state
                           (update :messages conj (assistant-tool-message response calls))
                           (assoc :round-count round))
                executed (execute-batch registry role request-id calls state* config)
                exhausted? (or (>= (:call-count executed) (:max-tool-calls config))
                               (>= round (:max-tool-rounds config))
                               (>= (:evidence-chars executed) (:max-evidence-chars config)))]
            (if exhausted?
              (let [final-response
                    (invoke! provider
                             role
                             (conj (:messages executed)
                                   {:role "user" :content verdict-nudge})
                             schema
                             timeout-ms
                             [])]
                (when (seq (:tool-calls final-response))
                  (throw (ex-info "MAGI returned tool calls after file-review budget exhaustion"
                                  {:type :magi-file-review-verdict-required})))
                {:output (parse-output final-response)
                 :trace (:trace executed)
                 :budget {:calls (:call-count executed)
                          :requested-calls (:requested-call-count executed)
                          :rounds (:round-count executed)
                          :evidence-chars (:evidence-chars executed)
                          :exhausted? true}})
              (recur executed))))))))
