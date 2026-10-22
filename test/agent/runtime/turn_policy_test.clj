(ns agent.runtime.turn-policy-test
  (:require [agent.runtime.turn-policy :as policy]
            [clojure.test :refer [deftest is]]))

(deftest transition-history-and-state-contracts
  (let [initial (assoc (policy/initial-turn [{:role "user" :content "question"}] :doom :nudge)
                       :state {:existing true})
        prompt {:role "user" :content "retry"}
        protocol [{:role "assistant" :tool-calls [{:id "call"}]}
                  {:role "tool" :tool-call-id "call" :content "result"}]]
    (doseq [[kind changes expected-final expected-state]
            [[:pre-retry {:prompt prompt} [] {:existing true}]
             [:post-retry {:prompt prompt :execution-state {:ran true} :trace-entry {:step 0}}
              [] {:existing true :ran true}]
             [:tool-turn {:protocol protocol :execution-state {:ran true} :trace-entry {:step 0}}
              protocol {:existing true :ran true}]
             [:submission-retry {:protocol protocol :prompt prompt :nudge-state :retry}
              protocol {:existing true}]]]
      (let [next (policy/advance initial (assoc changes :usage {:tokens 2}))]
        (is (= 1 (:step-no next)) kind)
        (is (= expected-final (:final-messages next)) kind)
        (is (= expected-state (:state next)) kind)
        (is (= {:tokens 2} (:usage next)) kind)
        (is (= :doom (:doom-loop-state next)) kind)
        (is (= (cond-> (into (:planner-messages initial) (:protocol changes))
                 (:prompt changes) (conj prompt)) (:planner-messages next)) kind)))))

(deftest decision-precedence-and-result-budget
  (doseq [[input expected] [[{:submission {}} :submission]
                            [{:pre-verdict {:action :retry} :max-token-terminal? true} :retry]
                            [{:pre-verdict {:action :fatal} :max-token-terminal? true} :fatal]
                            [{:max-token-terminal? true} :max-tokens]
                            [{} :execute]]]
    (is (= expected (policy/next-action input))))
  (doseq [[n expected] [[0 :retry] [1 :retry] [2 :fatal]]]
    (is (= expected (:action (policy/result-verdict {:result-retries n} "invalid")))))
  (is (= :complete (policy/terminal-action {:result "done"} [:pending])))
  (is (= :approval (policy/terminal-action nil [:pending])))
  (is (= :continue (policy/terminal-action nil []))))
