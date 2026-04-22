(ns agent.planner-test
  (:require
   [agent.llm.core :as llm]
   [agent.planner :as planner]
   [cheshire.core :as json]
   [clojure.test :refer :all]))

(deftest planner-validates-schema-constrained-output-test
  (let [provider (reify llm/ILLMProviderInvoke
                   (invoke [_ request]
                     (is (some? (get-in request [:structured-output :schema])))
                     {:role "assistant"
                      :content (json/generate-string
                                {:state {:phase "done"}
                                 :directives [{:type "complete"
                                               :payload {:result "ok"}}]
                                 :receipts []})
                      :tool-calls []
                      :usage nil
                      :raw nil})
                   (generate [this messages opts]
                     (llm/invoke this (assoc opts :messages messages))))]
    (let [step (planner/plan-step! provider {:messages [{:role "user" :content "finish"}]})]
      (is (= :complete (get-in step [:directives 0 :type])))
      (is (= "ok" (get-in step [:directives 0 :payload :result]))))))
