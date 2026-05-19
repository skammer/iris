(ns agent.runtime.loop-test
  (:require
   [agent.kernel.schema :as kernel-schema]
   [agent.runtime.loop :as runtime-loop]
   [clojure.test :refer :all]))

(defn- complete-step [content]
  {:schema-version kernel-schema/current-step-schema-version
   :state {}
   :directives [{:type :complete
                 :payload {:result content}}]
   :receipts []
   :llm-response {:content content
                  :tool-calls []
                  :usage {:tokens 1}}})

(defn- tool-step []
  {:schema-version kernel-schema/current-step-schema-version
   :state {}
   :directives [{:type :tool-call
                 :payload {:tool-name :fs
                           :input {:action "list"}
                           :context {:provider-tool-call-id "call_1"}}}]
   :receipts []
   :llm-response {:content ""
                  :tool-calls [{:id "call_1"
                                :type "function"
                                :function {:name "fs"
                                           :arguments "{\"action\":\"list\"}"}}]}})

(defn- execute-step [step]
  (assoc step
         :receipts
         (mapv (fn [directive]
                 (case (:type directive)
                   :complete {:directive :complete
                              :status :completed
                              :result (get-in directive [:payload :result])}
                   :tool-call {:directive :tool-call
                               :status :ok
                               :tool-name :fs
                               :tool-call-id "call_1"
                               :input {:action "list"}
                               :result "listed"}))
               (:directives step))))

(defn- run-loop [opts]
  (let [events (atom [])]
    {:events events
     :result (runtime-loop/run!
              (merge {:messages [{:role "user" :content "hi"}]
                      :request-id "req-1"
                      :session-id "session-1"
                      :agent-id "session-1"
                      :max-steps 3
                      :event-sink #(swap! events conj %)
                      :execute-step-fn execute-step}
                     opts))}))

(deftest plain-one-turn-completion-test
  (let [{:keys [result events]} (run-loop {:planner-fn (fn [_ _] (complete-step "done"))})]
    (is (= "done" (:content result)))
    (is (= :completed (:stop-reason result)))
    (is (= [:agent-start :turn-start :message-start :turn-end :message-update :message-end :agent-end]
           (mapv :event-type @events)))))

(deftest streamed-completion-test
  (let [{:keys [result events]}
        (run-loop {:stream? true
                   :planner-fn (fn [_ request]
                                 ((:on-content-delta request) "hel")
                                 ((:on-content-delta request) "lo")
                                 (complete-step "hello"))})]
    (is (= "hello" (:content result)))
    (is (= ["hel" "lo"]
           (->> @events
                (filter #(= :message-update (:event-type %)))
                (mapv #(get-in % [:payload :delta])))))))

(deftest tool-call-then-completion-test
  (let [requests (atom [])
        steps (atom [(tool-step) (complete-step "listed")])
        {:keys [result events]}
        (run-loop {:planner-fn (fn [_ request]
                                 (swap! requests conj request)
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))})]
    (is (= "listed" (:content result)))
    (is (some #(= "tool" (:role %)) (:messages (second @requests))))
    (is (= ["assistant" "tool" "assistant"]
           (->> @events
                (filter #(= :message-end (:event-type %)))
                (mapv #(get-in % [:payload :role])))))))

(deftest cancellation-test
  (let [cancelled? (atom true)
        {:keys [result events]} (run-loop {:cancellation-token cancelled?
                                           :planner-fn (fn [_ _] (complete-step "late"))})]
    (is (:cancelled? result))
    (is (= runtime-loop/stopped-content (:content result)))
    (is (= :cancelled (get-in (last @events) [:payload :stop-reason])))))

(deftest provider-error-fallback-test
  (let [{:keys [result events]}
        (run-loop {:planner-fn (fn [_ _] (throw (ex-info "provider down" {:type :provider-error})))
                   :fallback-fn (fn [{:keys [error]}]
                                  {:content (str "fallback: " (.getMessage error))
                                   :fallback? true})})]
    (is (= "fallback: provider down" (:content result)))
    (is (:fallback? result))
    (is (some #(= :planner-error (get-in % [:payload :stop-reason])) @events))))

(deftest max-step-stop-test
  (let [{:keys [result]} (run-loop {:max-steps 1
                                    :planner-fn (fn [_ _] (tool-step))})]
    (is (= runtime-loop/max-steps-content (:content result)))
    (is (= :max-steps (:stop-reason result)))))
