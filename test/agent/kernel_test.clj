(ns agent.kernel-test
  (:require
   [agent.kernel :as kernel]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.kernel.schema :as kernel-schema]
   [clojure.test :refer [deftest is]]))

(deftest directive-schema-validation-test
  (is (= :tool-call
         (-> {:type "tool-call"
              :payload {:tool-name "http"
                        :input {:url "https://example.com"}}}
             kernel-schema/validate-directive!
             :type)))
  (is (= kernel-schema/current-step-schema-version
         (:schema-version (kernel-schema/validate-step! {:directives []}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"directive failed schema validation"
                        (kernel/directive :tool-call {:input {}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unknown directive type"
                        (kernel/directive :delegate {:task {:id "x"}}))))

(deftest tool-call-directives-require-yolo-or-approval-test
  (let [executed (atom [])
        ops (reify kernel-ops/KernelOps
              (execute-agent-tool! [_ agent-id tool-name input context]
                (swap! executed conj {:agent-id agent-id
                                      :tool-name tool-name
                                      :input input
                                      :context context})
                {:ok true})
              (emit-kernel-event! [_ _] nil)
              kernel-ops/KernelCapabilities
              (supported-directives [_] #{:tool-call :complete :await})
              kernel-ops/KernelToolBatchOps
              (execute-agent-tool-batch! [_ agent-id calls _context _opts]
                {:results (mapv (fn [idx {:keys [tool-name input id context]}]
                                   (swap! executed conj {:agent-id agent-id
                                                         :tool-name tool-name
                                                         :input input
                                                         :context context})
                                   {:source-index idx
                                    :tool-call-id id
                                    :tool-name tool-name
                                    :input input
                                    :status :ok
                                    :result {:ok true}})
                                 (range)
                                 calls)}))
        directive {:type :tool-call
                   :payload {:tool-name "http"
                             :input {:url "https://example.com"}}}]
    (is (= :approval-required
           (:status (kernel-runtime/execute-directive! ops "chat" directive))))
    (is (empty? @executed))
    (let [receipt (kernel-runtime/execute-directive! ops "chat" directive {:yolo? true})]
      (is (= :ok (:status receipt)))
      (is (= {:url "https://example.com"} (:input receipt))))
    (is (= [{:agent-id "chat"
             :tool-name :http
             :input {:url "https://example.com"}
             :context {}}]
           @executed))))

(deftest complete-directive-returns-completed-receipt-test
  (let [events (atom [])
        ops (reify kernel-ops/KernelOps
              (execute-agent-tool! [_ _ _ _ _] nil)
              (emit-kernel-event! [_ event] (swap! events conj event))
              kernel-ops/KernelCapabilities
              (supported-directives [_] #{:tool-call :complete :await}))]
    (is (= {:directive :complete
            :status :completed
            :result {:ok true}}
           (kernel-runtime/execute-directive!
            ops
            "chat"
            (kernel/directive :complete {:result {:ok true}}))))))

(deftest execute-step-emits-redacted-kernel-event-test
  (let [events (atom [])
        ops (reify kernel-ops/KernelOps
              (execute-agent-tool! [_ _ _ _ _] {:secret "result"})
              (emit-kernel-event! [_ event] (swap! events conj event))
              kernel-ops/KernelCapabilities
              (supported-directives [_] #{:tool-call :complete :await}))
        step {:schema-version kernel-schema/current-step-schema-version
              :directives [{:type :tool-call
                            :payload {:tool-name "http"
                                      :input {:secret "input"}
                                      :context {:provider-tool-call-id "call-1"}}}]}]
    (let [executed (kernel-runtime/execute-step! ops "chat" step {:yolo? true})]
      (is (= {:secret "input"} (get-in executed [:receipts 0 :input])))
      (is (= {:secret "result"} (get-in executed [:receipts 0 :result]))))
    (is (= [{:directive :tool-call
             :status :ok
             :tool-name "http"
             :tool-call-id "call-1"}]
           (get-in (first @events) [:payload :receipts])))))

(deftest tool-batch-result-count-mismatch-fails-test
  (let [ops (reify kernel-ops/KernelOps
              (execute-agent-tool! [_ _ _ _ _] nil)
              (emit-kernel-event! [_ _] nil)
              kernel-ops/KernelCapabilities
              (supported-directives [_] #{:tool-call :await})
              kernel-ops/KernelToolBatchOps
              (execute-agent-tool-batch! [_ _ _ _ _]
                {:results []}))
        step {:schema-version kernel-schema/current-step-schema-version
              :directives [{:type :tool-call
                            :payload {:tool-name "http" :input {:n 1}}}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Tool batch returned wrong result count"
                          (kernel-runtime/execute-step! ops "chat" step {:yolo? true})))))
