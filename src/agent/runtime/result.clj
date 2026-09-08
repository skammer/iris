(ns agent.runtime.result
  "Terminal result submission for scheduled tasks. No transport side effects."
  (:require [agent.runtime.calls :as calls]
            [clojure.string :as str]))

(def tool
  {:name :return_result
   :description "Submit the final Markdown result and finish the task. Only md is delivered. Multiple calls in one response are joined in call order with a blank line. Do not mix with other tools."
   :version "1.0.0"
   :category :respond
   :operation :read
   :routing-categories #{:respond}
   :input-schema {:type "object" :additionalProperties false :required ["md"]
                  :properties {"md" {:type "string" :minLength 1}}}
   :required-permissions #{}
   :source :synthetic
   :sensitive false})

(defn check
  "Returns nil for work turns, or a submission/validation error for terminal turns."
  [response]
  (let [tool-calls (:tool-calls response)
        submissions (filter #(= :return_result (calls/tool-name-of %)) tool-calls)
        inputs (map #(calls/call-input % ::malformed) submissions)
        valid? (fn [input]
                 (and (map? input)
                      (= #{:md} (set (keys input)))
                      (string? (:md input))
                      (not (str/blank? (:md input)))))]
    (cond
      (empty? tool-calls)
      {:error "Finish by calling return_result with the complete final Markdown in md. Ordinary assistant text is not delivered."}

      (empty? submissions) nil

      (not= (count submissions) (count tool-calls))
      {:error "Do not mix return_result with other tools. No calls from this batch were executed. Finish tool work first, then submit the result."}

      (not-every? valid? inputs)
      {:error "Every return_result call must contain exactly one field, md, with a non-empty Markdown string. Resubmit all parts; none were accepted."}

      :else {:content (str/join "\n\n" (map (comp str/trim :md) inputs))})))

(defn receipts [response error]
  (mapv (fn [idx call]
          (cond-> {:directive :tool-call
                   :tool-name (calls/tool-name-of call)
                   :tool-call-id (calls/call-id idx call)
                   :status (if error :error :ok)
                   :result (if error {:error error} {:accepted true})}
            error (assoc :error-type :invalid-result :reason error)))
        (range) (:tool-calls response)))
