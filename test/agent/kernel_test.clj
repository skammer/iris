(ns agent.kernel-test
  (:require
   [agent.kernel :as kernel]
   [agent.kernel.schema :as kernel-schema]
   [clojure.test :refer :all]))

(deftest orchestrator-spawn-worker-step-test
  (let [result (kernel/orchestrator-spawn-worker-step
                {:task {:id "task-1" :prompt "collect facts"}
                 :worker-name "Fact Worker"
                 :capability-bundle {:capabilities ["research"]
                                     :tool-access ["http" "fs"]}
                 :memory-scopes ["session" "agent"]
                 :budgets {:max_tokens 1000}})]
    (is (= :delegated (get-in result [:state :phase])))
    (is (= 2 (count (:directives result))))
    (is (= :spawn-worker (get-in result [:directives 0 :type])))
    (is (= "Fact Worker" (get-in result [:directives 0 :payload :name])))
    (is (= {:max_tokens 1000} (get-in result [:directives 0 :payload :budgets])))))

(deftest directive-schema-validation-test
  (is (= :tool-call
         (-> {:type "tool-call"
              :payload {:tool-name "http"
                        :input {:url "https://example.com"}}}
             kernel-schema/validate-directive!
             :type)))
  (is (map? (kernel-schema/planner-json-schema)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"directive failed schema validation"
                        (kernel/directive :tool-call {:input {}}))))
