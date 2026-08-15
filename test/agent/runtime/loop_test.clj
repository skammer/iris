(ns agent.runtime.loop-test
  (:require
   [agent.kernel.schema :as kernel-schema]
   [agent.runtime.loop :as runtime-loop]
   [agent.runtime.messages :as runtime-messages]
   [cheshire.core :as json]
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

(defn- tool-step
  ([] (tool-step "call_1" :fs_list {:path "."}))
  ([call-id tool-name input]
  {:schema-version kernel-schema/current-step-schema-version
   :state {}
   :directives [{:type :tool-call
                 :payload {:tool-name tool-name
                           :input input
                           :context {:provider-tool-call-id call-id}}}]
   :receipts []
   :llm-response {:content ""
                  :tool-calls [{:id call-id
                                :type "function"
                                :function {:name (name tool-name)
                                           :arguments (json/generate-string input)}}]}}))

(defn- tool-batch-step [id-prefix tool-name inputs]
  (let [calls (mapv (fn [idx input]
                      {:id (str id-prefix "_" idx)
                       :input input})
                    (range)
                    inputs)]
    {:schema-version kernel-schema/current-step-schema-version
     :state {}
     :directives (mapv (fn [{:keys [id input]}]
                         {:type :tool-call
                          :payload {:tool-name tool-name
                                    :input input
                                    :context {:provider-tool-call-id id}}})
                       calls)
     :receipts []
     :llm-response {:content ""
                    :tool-calls (mapv (fn [{:keys [id input]}]
                                        {:id id
                                         :type "function"
                                         :function {:name (name tool-name)
                                                    :arguments (json/generate-string input)}})
                                      calls)}}))

(defn- execute-step [step]
  (assoc step
         :receipts
         (mapv (fn [directive]
                 (case (:type directive)
                   :complete {:directive :complete
                              :status :completed
                              :result (get-in directive [:payload :result])}
                   :tool-call (let [{:keys [tool-name input context]} (:payload directive)]
                                {:directive :tool-call
                                 :status :ok
                                 :tool-name tool-name
                                 :tool-call-id (:provider-tool-call-id context)
                                 :input input
                                 :result "listed"})))
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

(deftest passes-session-id-to-planner-test
  (let [request* (atom nil)
        {:keys [result]} (run-loop {:planner-fn (fn [_ request]
                                                 (reset! request* request)
                                                 (complete-step "done"))})]
    (is (= "done" (:content result)))
    (is (= "session-1" (:session-id @request*)))))

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

(deftest streamed-completion-emits-during-plan-step-test
  (let [events (atom [])
        delta-sent (promise)
        delta-visible (promise)
        release-planner (promise)
        result (future
                 (runtime-loop/run!
                  {:messages [{:role "user" :content "hi"}]
                   :request-id "req-1"
                   :session-id "session-1"
                   :agent-id "session-1"
                   :max-steps 3
                   :stream? true
                   :event-sink (fn [event]
                                 (swap! events conj event)
                                 (when (and (= :message-update (:event-type event))
                                            (= "hel" (get-in event [:payload :delta])))
                                   (deliver delta-visible "hel")))
                   :execute-step-fn execute-step
                   :planner-fn (fn [_ request]
                                 ((:on-content-delta request) "hel")
                                 (deliver delta-sent true)
                                 (deref release-planner 1000 nil)
                                 ((:on-content-delta request) "lo")
                                 (complete-step "hello"))}))]
    (is (true? (deref delta-sent 1000 false)))
    (is (= "hel" (deref delta-visible 100 ::missing)))
    (deliver release-planner true)
    (is (= "hello" (:content @result)))))

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

(deftest doom-loop-guard-blocks-third-identical-tool-call-test
  (let [execute-count (atom 0)
        steps (atom [(tool-step "call_1" :fs_list {:path "."})
                     (tool-step "call_2" :fs_list {:path "."})
                     (tool-step "call_3" :fs_list {:path "."})])
        {:keys [result events]}
        (run-loop {:max-steps 4
                   :planner-fn (fn [_ _]
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))
                   :execute-step-fn (fn [step]
                                      (swap! execute-count inc)
                                      (execute-step step))})]
    (is (= 2 @execute-count))
    (is (= runtime-messages/doom-loop-content (:content result)))
    (is (= :doom-loop (:stop-reason result)))
    (is (some #(= :doom-loop-detected (get-in % [:payload :kind])) @events))
    (is (= :doom-loop (get-in (last @events) [:payload :stop-reason])))))

(deftest doom-loop-guard-allows-different-input-or-tool-test
  (let [execute-count (atom 0)
        steps (atom [(tool-step "call_1" :fs_list {:path "."})
                     (tool-step "call_2" :fs_list {:path "."})
                     (tool-step "call_3" :shell {:argv ["pwd"]})
                     (complete-step "done")])
        {:keys [result]}
        (run-loop {:max-steps 4
                   :planner-fn (fn [_ _]
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))
                   :execute-step-fn (fn [step]
                                      (swap! execute-count inc)
                                      (execute-step step))})]
    (is (= 4 @execute-count))
    (is (= "done" (:content result)))
    (is (= :completed (:stop-reason result)))))

(deftest doom-loop-guard-blocks-repeated-multi-step-sequence-test
  (let [execute-count (atom 0)
        sensor-inputs (mapv (fn [idx]
                              {:action "get_state"
                               :entity_id (str "sensor.plant_" idx)})
                            (range 8))
        sensor-step #(tool-batch-step (str "ha_" %) :homeassistant sensor-inputs)
        shell-step #(tool-step (str "shell_" %) :shell {:command "read-yesterday"})
        steps (atom [(sensor-step 1) (shell-step 1)
                     (sensor-step 2) (shell-step 2)
                     (sensor-step 3) (shell-step 3)])
        {:keys [result events]}
        (run-loop {:max-steps 7
                   :planner-fn (fn [_ _]
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))
                   :execute-step-fn (fn [step]
                                      (swap! execute-count inc)
                                      (execute-step step))})
        detection (some #(when (= :doom-loop-detected (get-in % [:payload :kind]))
                           (:payload %))
                        @events)]
    (is (= 5 @execute-count))
    (is (= :doom-loop (:stop-reason result)))
    (is (= :repeated-sequence (:detection detection)))
    (is (= 2 (:sequence-length detection)))))

(deftest doom-loop-guard-disabled-bypasses-repeat-check-test
  (let [execute-count (atom 0)
        steps (atom [(tool-step "call_1" :fs_list {:path "."})
                     (tool-step "call_2" :fs_list {:path "."})
                     (tool-step "call_3" :fs_list {:path "."})
                     (complete-step "done")])
        {:keys [result]}
        (run-loop {:max-steps 4
                   :doom-loop-config {:enabled? false}
                   :planner-fn (fn [_ _]
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))
                   :execute-step-fn (fn [step]
                                      (swap! execute-count inc)
                                      (execute-step step))})]
    (is (= 4 @execute-count))
    (is (= "done" (:content result)))
    (is (= :completed (:stop-reason result)))))

(deftest approval-reasons-align-by-tool-call-id-test
  (let [step {:schema-version kernel-schema/current-step-schema-version
              :state {}
              :directives [{:type :tool-call
                            :payload {:tool-name :shell
                                      :input {:value 1}
                                      :context {:provider-tool-call-id "call_a"}}}
                           {:type :tool-call
                            :payload {:tool-name :fs_write
                                      :input {:value 2}
                                      :context {:provider-tool-call-id "call_b"}}}]
              :receipts []
              :llm-response {:content ""
                             :tool-calls [{:id "call_a"
                                           :type "function"
                                           :function {:name "shell"
                                                      :arguments (json/generate-string {:value 1})}}
                                          {:id "call_b"
                                           :type "function"
                                           :function {:name "fs_write"
                                                      :arguments (json/generate-string {:value 2})}}]}}
        {:keys [events]}
        (run-loop {:planner-fn (fn [_ _] step)
                   :execute-step-fn (fn [_]
                                      {:receipts [{:directive :tool-call
                                                   :status :approval-required
                                                   :tool-name :shell
                                                   :tool-call-id "call_a"
                                                   :input {:value 1}}
                                                  {:directive :tool-call
                                                   :status :approval-required
                                                   :tool-name :fs_write
                                                   :tool-call-id "call_b"
                                                   :input {:value 2}}]})
                   :approval-fn (fn [_]
                                  [{:id "approval-b"
                                    :tool-name :fs_write
                                    :tool-call-id "call_b"
                                    :input {:value 2}
                                    :reason "write reason"}
                                   {:id "approval-a"
                                    :tool-name :shell
                                    :tool-call-id "call_a"
                                    :input {:value 1}
                                    :reason "shell reason"}])})
        approval-event (some #(when (= :approval-required (get-in % [:payload :kind])) %)
                             @events)
        receipts (get-in approval-event [:payload :receipts])]
    (is (= ["shell reason" "write reason"] (mapv :reason receipts)))))

(deftest context-pack-runs-before-planner-test
  (let [requests (atom [])
        pack-input (atom nil)
        {:keys [events]}
        (run-loop {:context-injectors [(constantly [{:role "system" :content "memory"}])]
                   :context-pack-fn (fn [{:keys [messages] :as ctx}]
                                      (reset! pack-input ctx)
                                      {:messages [(last messages)]
                                       :tokens-before 100
                                       :tokens-after 10
                                       :budgets {:system {:used 1 :limit 10}}
                                       :warnings [{:level :destructive
                                                   :tokens 100
                                                   :threshold 90}]
                                       :compaction {:summary "summary"
                                                    :first-kept-entry-id (:id (last messages))
                                                    :tokens-before 100
                                                    :tokens-after 10}})
                   :planner-fn (fn [_ request]
                                 (swap! requests conj request)
                                 (complete-step "done"))})]
    (is (= ["system" "user"] (mapv :role (:messages @pack-input))))
    (is (= ["user"] (mapv :role (:messages (first @requests)))))
    (is (some #(= :context-budget (get-in % [:payload :kind])) @events))
    (is (some #(= :context-warning (get-in % [:payload :kind])) @events))
    (is (some #(= :context-compacted (get-in % [:payload :kind])) @events))))

(deftest context-pack-output-becomes-next-step-history-test
  (let [calls (atom 0)
        requests (atom [])
        steps (atom [(tool-step) (complete-step "done")])]
    (run-loop {:messages [{:role "user" :content "old context"}
                          {:role "user" :content "current request"}]
               :context-pack-fn
               (fn [{:keys [messages]}]
                 (if (= 1 (swap! calls inc))
                   {:messages [(last messages)]}
                   {:messages messages}))
               :planner-fn (fn [_ request]
                             (swap! requests conj request)
                             (let [step (first @steps)]
                               (swap! steps rest)
                               step))})
    (is (= 2 (count @requests)))
    (is (not-any? #(= "old context" (:content %))
                  (:messages (second @requests))))))

(deftest cancellation-test
  (let [cancelled? (atom true)
        {:keys [result events]} (run-loop {:cancellation-token cancelled?
                                           :planner-fn (fn [_ _] (complete-step "late"))})]
    (is (:cancelled? result))
    (is (= runtime-messages/stopped-content (:content result)))
    (is (= :cancelled (get-in (last @events) [:payload :stop-reason])))))

(deftest provider-error-fallback-test
  (let [{:keys [result events]}
        (run-loop {:planner-fn (fn [_ _] (throw (ex-info "provider down" {:type :provider-error})))
                   :fallback-fn (fn [{:keys [error]}]
                                  {:content (str "fallback: " (.getMessage error))
                                   :fallback? true})})]
    (is (= "fallback: provider down" (:content result)))
    (is (:fallback? result))
    (is (= [:completed]
           (->> @events
                (filter #(= :agent-end (:event-type %)))
                (mapv #(get-in % [:payload :stop-reason])))))))

(deftest provider-error-fallback-receives-latest-tool-results-test
  (let [steps (atom [(tool-step)
                     (delay (throw (ex-info "provider down" {:type :provider-error})))])
        fallback-input (atom nil)
        {:keys [result]}
        (run-loop {:planner-fn (fn [_ _]
                                (let [step (first @steps)]
                                  (swap! steps rest)
                                  (if (instance? clojure.lang.IDeref step) @step step)))
                   :fallback-fn (fn [{:keys [messages]}]
                                  (reset! fallback-input messages)
                                  {:content "recovered from tool result"
                                   :fallback? true})})]
    (is (= "recovered from tool result" (:content result)))
    (is (= ["user" "assistant" "tool"]
           (mapv :role @fallback-input)))
    (is (= "listed"
           (:result (json/parse-string
                     (get-in (last @fallback-input) [:content 0 :content])
                     true))))))

(deftest max-step-stop-test
  (let [{:keys [result]} (run-loop {:max-steps 1
                                    :planner-fn (fn [_ _] (tool-step))})]
    (is (= runtime-messages/max-steps-content (:content result)))
    (is (= :max-steps (:stop-reason result)))))

(deftest max-token-truncation-stops-turn-test
  (let [{:keys [result events]}
        (run-loop {:planner-fn (fn [_ _]
                                 {:schema-version kernel-schema/current-step-schema-version
                                  :state {}
                                  :directives [{:type :complete
                                                :payload {:result "should not run"}}]
                                  :receipts []
                                  :llm-response {:content "partial"
                                                 :tool-calls []
                                                 :usage {:tokens 9}
                                                 :stop-reason "length"}})})]
    (is (= runtime-messages/max-tokens-content (:content result)))
    (is (= :max-tokens (:stop-reason result)))
    (is (= ["partial" runtime-messages/max-tokens-content]
           (->> @events
                (filter #(= :message-end (:event-type %)))
                (mapv #(get-in % [:payload :content])))))
    (is (true? (->> @events
                    (filter #(= :message-end (:event-type %)))
                    first
                    :payload
                    :excluded-from-context?)))))

(deftest max-token-with-tool-calls-executes-instead-of-dead-ending-test
  ;; finish_reason="length" with a valid tool_calls array must NOT discard the
  ;; turn: the model emitted a real call before hitting the output cap.
  (let [steps (atom [(-> (tool-step "call_1" :fs_list {:path "."})
                         (assoc-in [:llm-response :stop-reason] "length"))
                     (complete-step "done")])
        {:keys [result events]}
        (run-loop {:planner-fn (fn [_ _]
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))})]
    (is (= :completed (:stop-reason result))
        "tool calls run and the loop continues to completion")
    (is (not= :max-tokens (:stop-reason result)))
    (is (= "done" (:content result)))
    (is (some #(= "tool" (get-in % [:payload :role]))
              (filter #(= :message-end (:event-type %)) @events))
        "the truncated tool call actually executed (emitted a tool turn)")))

(deftest small-profile-retries-bare-text-and-suppresses-stream-test
  (let [steps (atom [(complete-step "bad")
                     (tool-step "call_respond" :respond {:content "done"})])
        {:keys [result events]}
        (run-loop {:stream? true
                   :chat-profile {:small-model? true
                                  :respond-tool? true
                                  :max-nudges 2
                                  :nudge-budgets {:bare-text 2}}
                   :planner-fn (fn [_ request]
                                 (let [step (first @steps)]
                                   (when (and (= "bad" (get-in step [:llm-response :content]))
                                              (:on-content-delta request))
                                     ((:on-content-delta request) "bad"))
                                   (swap! steps rest)
                                   step))})]
    (is (= "done" (:content result)))
    (is (= ["done"]
           (->> @events
                (filter #(= :message-update (:event-type %)))
                (keep #(get-in % [:payload :delta]))
                vec)))
    (is (some #(= :nudge-injected (:event-type %)) @events))))

(deftest small-profile-retries-max-token-once-test
  (let [steps (atom [{:schema-version kernel-schema/current-step-schema-version
                      :state {}
                      :directives [{:type :complete
                                    :payload {:result "too long"}}]
                      :receipts []
                      :llm-response {:content "partial"
                                     :tool-calls []
                                     :usage {:tokens 9}
                                     :stop-reason "length"}}
                     (tool-step "call_respond" :respond {:content "short"})])
        {:keys [result events]}
        (run-loop {:chat-profile {:small-model? true
                                  :respond-tool? true
                                  :max-nudges 2
                                  :nudge-budgets {:max-token-truncation 1}}
                   :planner-fn (fn [_ _]
                                 (let [step (first @steps)]
                                   (swap! steps rest)
                                   step))})]
    (is (= "short" (:content result)))
    (is (= :completed (:stop-reason result)))
    (is (some #(= "max-token-truncation" (get-in % [:payload :reason])) @events))))
