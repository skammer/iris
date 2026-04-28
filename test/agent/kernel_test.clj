(ns agent.kernel-test
  (:require
   [agent.kernel :as kernel]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
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
  (is (= kernel-schema/current-step-schema-version
         (:schema-version (kernel-schema/validate-step! {:directives []}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"directive failed schema validation"
                        (kernel/directive :tool-call {:input {}}))))

(deftest tool-call-directives-require-yolo-or-approval-test
  (let [executed (atom [])
        ops (reify kernel-ops/KernelOps
              (spawn-task-worker! [_ _] nil)
              (execute-agent-tool! [_ agent-id tool-name input context]
                (swap! executed conj {:agent-id agent-id
                                      :tool-name tool-name
                                      :input input
                                      :context context})
                {:ok true})
              (send-agent-message! [_ _ _] nil)
              (patch-agent-state! [_ _ _] nil)
              (set-agent-status! [_ _ _] nil)
              (emit-kernel-event! [_ _] nil))
        directive {:type :tool-call
                   :payload {:tool-name "http"
                             :input {:url "https://example.com"}}}]
    (is (= :approval-required
           (:status (kernel-runtime/execute-directive! ops "agent-1" directive))))
    (is (empty? @executed))
    (let [receipt (kernel-runtime/execute-directive! ops "agent-1" directive {:yolo? true})]
      (is (= :ok (:status receipt)))
      (is (= {:url "https://example.com"} (:input receipt))))
    (is (= [{:agent-id "agent-1"
             :tool-name :http
             :input {:url "https://example.com"}
             :context {}}]
           @executed))))
