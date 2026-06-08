(ns agent.runtime.tools-test
  (:require
   [agent.runtime.tools :as runtime-tools]
   [agent.tools.core :as tools]
   [clojure.test :refer :all]))

(defn test-tool
  [name f & {:keys [permissions sensitive execution-mode operation parallel-safe? approval-sensitive?
                    activates-tools?]}]
  (tools/create-tool
   {:description (tools/create-tool-description
                  name
                  (str name)
                  :input-schema [:map [:value :int]]
                  :required-permissions (or permissions #{name})
                  :sensitive sensitive
                  :execution-mode execution-mode
                  :operation operation
                  :parallel-safe? parallel-safe?
                  :approval-sensitive? approval-sensitive?
                  :activates-tools? activates-tools?)
    :execute-fn f}))

(defn safe-read-tool [name f]
  (test-tool name f :operation :read :parallel-safe? true))

(defn registry
  [& tools*]
  (reduce tools/register-tool (tools/create-registry) tools*))

(deftest sequential-order-test
  (let [calls (atom [])
        reg (registry (test-tool :a (fn [input _] (swap! calls conj [:a (:value input)]) input))
                      (test-tool :b (fn [input _] (swap! calls conj [:b (:value input)]) input)))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :a :input {:value 1}}
                 {:tool-name :b :input {:value 2}}]
                {:permissions #{:a :b}}
                {:mode :sequential})]
    (is (= [[:a 1] [:b 2]] @calls))
    (is (= [:a :b] (mapv :tool-name (:results result))))
    (is (= ["tool-call-0" "tool-call-1"] (mapv :tool-call-id (:messages result))))))

(deftest parallel-completion-order-and-source-transcript-test
  (let [events (atom [])
        reg (registry (safe-read-tool :slow (fn [input _] (Thread/sleep 80) input))
                      (safe-read-tool :fast (fn [input _] input)))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :slow :input {:value 1}}
                 {:tool-name :fast :input {:value 2}}]
                {:permissions #{:slow :fast}}
                {:mode :parallel
                 :event-sink #(swap! events conj %)})
        completion-order (->> @events
                              (filter #(= :tool-execution-end (:event-type %)))
                              (mapv #(get-in % [:payload :tool-name])))]
    (is (= ["fast" "slow"] completion-order))
    (is (= [:slow :fast] (mapv :tool-name (:results result))))
    (is (= ["slow" "fast"] (mapv :name (:messages result))))))

(deftest parallel-execution-respects-max-parallelism-test
  (let [active (atom 0)
        max-seen (atom 0)
        tool-fn (fn [input _]
                  (let [n (swap! active inc)]
                    (swap! max-seen max n)
                    (Thread/sleep 40)
                    (swap! active dec)
                    input))
        tools* (mapv #(safe-read-tool (keyword (str "read-" %)) tool-fn) (range 5))
        reg (apply registry tools*)]
    (runtime-tools/execute-batch!
     reg
     (mapv (fn [idx]
             {:tool-name (keyword (str "read-" idx))
              :input {:value idx}})
           (range 5))
     {:permissions (set (map :name (tools/list-tools reg)))}
     {:mode :parallel
      :max-parallelism 2})
    (is (<= @max-seen 2))))

(deftest default-metadata-stays-sequential-test
  (let [calls (atom [])
        reg (registry (test-tool :slow (fn [input _] (Thread/sleep 60) (swap! calls conj :slow) input))
                      (test-tool :fast (fn [input _] (swap! calls conj :fast) input)))
        _ (runtime-tools/execute-batch!
           reg
           [{:tool-name :slow :input {:value 1}}
            {:tool-name :fast :input {:value 2}}]
           {:permissions #{:slow :fast}}
           {:mode :parallel})]
    (is (= [:slow :fast] @calls))))

(deftest mixed-safe-and-unsafe-boundaries-test
  (let [calls (atom [])
        reg (registry (test-tool :write-a (fn [input _] (swap! calls conj :write-a) input)
                                 :operation :act)
                      (safe-read-tool :read-b (fn [input _] (swap! calls conj :read-b) input))
                      (test-tool :write-c (fn [input _] (swap! calls conj :write-c) input)
                                 :operation :act))]
    (runtime-tools/execute-batch!
     reg
     [{:tool-name :write-a :input {:value 1}}
      {:tool-name :read-b :input {:value 2}}
      {:tool-name :write-c :input {:value 3}}]
     {:permissions #{:write-a :read-b :write-c}}
     {:mode :parallel})
    (is (= [:write-a :read-b :write-c] @calls))))

(deftest approval-sensitive-forces-whole-batch-sequential-test
  (let [events (atom [])
        reg (registry (test-tool :slow (fn [input _] (Thread/sleep 80) input)
                                 :operation :read
                                 :parallel-safe? true
                                 :approval-sensitive? true)
                      (safe-read-tool :fast (fn [input _] input)))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :slow :input {:value 1}}
                 {:tool-name :fast :input {:value 2}}]
                {:permissions #{:slow :fast}}
                {:mode :parallel
                 :event-sink #(swap! events conj %)})
        completion-order (->> @events
                              (filter #(= :tool-execution-end (:event-type %)))
                              (mapv #(get-in % [:payload :tool-name])))]
    (is (= ["slow" "fast"] completion-order))
    (is (= [:slow :fast] (mapv :tool-name (:results result))))))

(deftest activates-tools-forces-whole-batch-sequential-test
  (let [events (atom [])
        reg (registry (test-tool :activator (fn [input _] (Thread/sleep 80) input)
                                 :operation :read
                                 :parallel-safe? true
                                 :activates-tools? true)
                      (safe-read-tool :fast (fn [input _] input)))]
    (runtime-tools/execute-batch!
     reg
     [{:tool-name :activator :input {:value 1}}
      {:tool-name :fast :input {:value 2}}]
     {:permissions #{:activator :fast}}
     {:mode :parallel
      :event-sink #(swap! events conj %)})
    (is (= ["activator" "fast"]
           (->> @events
                (filter #(= :tool-execution-end (:event-type %)))
                (mapv #(get-in % [:payload :tool-name])))))))

(deftest explicit-act-parallel-safe-runs-parallel-test
  (let [events (atom [])
        reg (registry (test-tool :slow-act (fn [input _] (Thread/sleep 80) input)
                                 :operation :act
                                 :parallel-safe? true)
                      (test-tool :fast-act (fn [input _] input)
                                 :operation :act
                                 :parallel-safe? true))]
    (runtime-tools/execute-batch!
     reg
     [{:tool-name :slow-act :input {:value 1}}
      {:tool-name :fast-act :input {:value 2}}]
     {:permissions #{:slow-act :fast-act}}
     {:mode :parallel
      :event-sink #(swap! events conj %)})
    (is (= ["fast-act" "slow-act"]
           (->> @events
                (filter #(= :tool-execution-end (:event-type %)))
                (mapv #(get-in % [:payload :tool-name])))))))

(deftest cancellation-stops-parallel-submit-test
  (let [checks (atom 0)
        reg (registry (safe-read-tool :a (fn [input _] input))
                      (safe-read-tool :b (fn [input _] input)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Chat stopped"
         (runtime-tools/execute-batch!
          reg
          [{:tool-name :a :input {:value 1}}
           {:tool-name :b :input {:value 2}}]
          {:permissions #{:a :b}}
          {:mode :parallel
           :cancelled? #(>= (swap! checks inc) 5)})))))

(deftest approval-required-test
  (let [reg (registry (test-tool :secret (fn [input _] input) :sensitive true))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :secret :input {:value 1}}]
                {:permissions #{:secret}}
                {:mode :sequential})]
    (is (= :error (get-in result [:results 0 :status])))
    (is (= :approval-required (get-in result [:results 0 :error-type])))))

(deftest tool-execution-events-emitted-once-through-batch-test
  ;; Both the runtime batch layer and tools.core/execute-tool used to emit
  ;; tool-execution-start/end with divergent payloads. The batch path must now
  ;; emit each exactly once, and keep the runtime layer's event (it carries the
  ;; tool-call-id that chat + UI correlate on).
  (let [events (atom [])
        sink #(swap! events conj %)
        reg (-> (tools/create-registry {:event-sink sink})
                (tools/register-tool (test-tool :a (fn [input _] input))))
        _ (runtime-tools/execute-batch!
           reg
           [{:tool-name :a :input {:value 1}}]
           {:permissions #{:a}}
           {:mode :sequential :event-sink sink})
        starts (filter #(= :tool-execution-start (:event-type %)) @events)
        ends (filter #(= :tool-execution-end (:event-type %)) @events)]
    (is (= 1 (count starts)) "exactly one tool-execution-start")
    (is (= 1 (count ends)) "exactly one tool-execution-end")
    (is (every? :timestamp (concat starts ends)))
    (is (= "tool-call-0" (get-in (first starts) [:payload :tool-call-id]))
        "retained event is the runtime layer's (has tool-call-id)")))

(deftest permission-denied-test
  (let [reg (registry (test-tool :a (fn [input _] input)))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :a :input {:value 1}}]
                {:permissions #{}}
                {:mode :sequential})]
    (is (= :permission-denied (get-in result [:results 0 :error-type])))))

(deftest validation-error-test
  (let [reg (registry (test-tool :a (fn [input _] input)))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :a :input {:value "bad"}}]
                {:permissions #{:a}}
                {:mode :sequential})]
    (is (= :validation-failed (get-in result [:results 0 :error-type])))))

(deftest tool-update-streaming-test
  (let [events (atom [])
        reg (registry (test-tool :a (fn [input context]
                                      ((:on-tool-update context) {:progress 1})
                                      input)))
        result (runtime-tools/execute-batch!
                reg
                [{:tool-name :a :input {:value 1}}]
                {:permissions #{:a}}
                {:mode :sequential
                 :event-sink #(swap! events conj %)})]
    (is (= :ok (get-in result [:results 0 :status])))
    (is (= [1] (->> @events
                    (filter #(= :tool-execution-update (:event-type %)))
                    (mapv #(get-in % [:payload :progress])))))))

(deftest terminate-all-and-mixed-test
  (let [terminating (test-tool :done (fn [input _] (assoc input :terminate true)))
        continuing (test-tool :more (fn [input _] input))]
    (is (true? (:terminate?
                (runtime-tools/execute-batch!
                 (registry terminating)
                 [{:tool-name :done :input {:value 1}}]
                 {:permissions #{:done}}
                 {:mode :sequential}))))
    (is (false? (:terminate?
                 (runtime-tools/execute-batch!
                  (registry terminating continuing)
                  [{:tool-name :done :input {:value 1}}
                   {:tool-name :more :input {:value 2}}]
                  {:permissions #{:done :more}}
                  {:mode :sequential}))))))

(deftest before-and-after-hooks-test
  (let [reg (registry (test-tool :a (fn [input _] input)))]
    (is (= :tool-blocked
           (get-in (runtime-tools/execute-batch!
                    reg
                    [{:tool-name :a :input {:value 1}}]
                    {:permissions #{:a}}
                    {:before-tool-call (fn [_] {:block true :reason "no"})})
                   [:results 0 :error-type])))
    (is (= {:value 2}
           (get-in (runtime-tools/execute-batch!
                    reg
                    [{:tool-name :a :input {:value 1}}]
                    {:permissions #{:a}}
                    {:after-tool-call (fn [_] {:result {:value 2}})})
                   [:results 0 :result])))))
