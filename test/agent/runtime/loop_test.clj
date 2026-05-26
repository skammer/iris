(ns agent.runtime.loop-test
  (:require
   [agent.kernel.schema :as kernel-schema]
   [agent.runtime.loop :as runtime-loop]
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
  ([] (tool-step "call_1" :fs {:action "list"}))
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
        steps (atom [(tool-step "call_1" :fs {:path "." :action "list"})
                     (tool-step "call_2" :fs {:action "list" :path "."})
                     (tool-step "call_3" :fs {:path "." :action "list"})])
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
    (is (= runtime-loop/doom-loop-content (:content result)))
    (is (= :doom-loop (:stop-reason result)))
    (is (some #(= :doom-loop-detected (get-in % [:payload :kind])) @events))
    (is (= :doom-loop (get-in (last @events) [:payload :stop-reason])))))

(deftest doom-loop-guard-allows-different-input-or-tool-test
  (let [execute-count (atom 0)
        steps (atom [(tool-step "call_1" :fs {:action "list"})
                     (tool-step "call_2" :fs {:action "read"})
                     (tool-step "call_3" :shell {:action "list"})
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

(deftest doom-loop-guard-disabled-bypasses-repeat-check-test
  (let [execute-count (atom 0)
        steps (atom [(tool-step "call_1" :fs {:action "list"})
                     (tool-step "call_2" :fs {:action "list"})
                     (tool-step "call_3" :fs {:action "list"})
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

(deftest normalize-chat-history-inserts-missing-tool-result-test
  (let [{:keys [messages repairs]}
        (runtime-loop/normalize-chat-history
         [{:role "assistant"
           :content [{:type :tool-call
                      :id "call_1"
                      :name "fs"
                      :arguments {:action "list"}}]}
          {:role "user" :content "next"}])]
    (is (= 1 (:inserted-tool-results repairs)))
    (is (= ["assistant" "tool" "user"] (mapv :role messages)))
    (is (= "call_1" (get-in messages [1 :content 0 :tool-call-id])))))

(deftest normalize-chat-history-removes-orphan-tool-result-test
  (let [{:keys [messages repairs]}
        (runtime-loop/normalize-chat-history
         [{:role "tool"
           :content [{:type :tool-result
                      :tool-call-id "orphan"
                      :content "late"}]}
          {:role "user" :content "next"}])]
    (is (= 1 (:removed-tool-results repairs)))
    (is (= ["user"] (mapv :role messages)))))

(deftest normalize-chat-history-preserves-valid-tool-order-test
  (let [input [{:role "assistant"
                :content [{:type :tool-call
                           :id "call_1"
                           :name "fs"
                           :arguments {:action "list"}}]}
               {:role "tool"
                :content [{:type :tool-result
                           :tool-call-id "call_1"
                           :content "ok"}]}
               {:role "user" :content [{:type :text :text "next"}]}]
        normalized (runtime-loop/normalize-chat-history input)]
    (is (= {} (:repairs normalized)))
    (is (= input (:messages normalized)))))

(deftest normalize-chat-history-adds-empty-assistant-placeholder-test
  (let [{:keys [messages repairs]}
        (runtime-loop/normalize-chat-history
         [{:role "assistant" :content []}
          {:role "user" :content "next"}])]
    (is (= 1 (:placeholder-assistant-messages repairs)))
    (is (= runtime-loop/empty-assistant-content
           (get-in messages [0 :content 0 :text])))))

(deftest normalize-chat-history-removes-internal-stop-assistant-test
  (let [{:keys [messages repairs]}
        (runtime-loop/normalize-chat-history
         [{:role "assistant" :content "Stopped: guardrail retry budget exhausted."}
          {:role "user" :content "next"}])]
    (is (= 1 (:removed-internal-stop-messages repairs)))
    (is (= ["user"] (mapv :role messages)))))

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
    (is (= runtime-loop/max-tokens-content (:content result)))
    (is (= :max-tokens (:stop-reason result)))
    (is (= ["partial" runtime-loop/max-tokens-content]
           (->> @events
                (filter #(= :message-end (:event-type %)))
                (mapv #(get-in % [:payload :content])))))
    (is (true? (->> @events
                    (filter #(= :message-end (:event-type %)))
                    first
                    :payload
                    :excluded-from-context?)))))

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
