(ns agent.runtime.nudge-test
  (:require
   [agent.runtime.nudge :as nudge]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(def profile
  {:small-model? true
   :respond-tool? true
   :max-nudges 2
   :nudge-budgets {:bare-text 1
                   :repeated-tool-call 1
                   :missing-prerequisite 1}})

(deftest bare-text-budget-and-fatal-test
  (let [step {:directives [{:type :complete :payload {:result "hi"}}]}
        ctx {:step step :llm-response {:content "hi"} :allowed-tools #{:respond}}
        verdict (nudge/check-before-exec profile (nudge/new-state) ctx)
        state (nudge/record-retry (nudge/new-state) verdict)
        fatal (nudge/check-before-exec profile state ctx)]
    (is (= :retry (:action verdict)))
    (is (= :bare-text (:reason verdict)))
    (is (= :fatal (:action fatal)))
    (is (= :guardrail-exhausted (:stop-reason fatal)))
    (is (str/includes? (:content fatal) "Last issue: bare-text"))))

(deftest internal-stop-text-is-retried-test
  (let [step {:directives [{:type :complete
                            :payload {:result "Stopped: guardrail retry budget exhausted."}}]}
        verdict (nudge/check-before-exec
                 (assoc profile :nudge-budgets {:premature-final 1})
                 (nudge/new-state)
                 {:step step
                  :llm-response {:content "Stopped: guardrail retry budget exhausted."}
                  :allowed-tools #{:fs_list :telegram_send_document}})]
    (is (= :retry (:action verdict)))
    (is (= :premature-final (:reason verdict)))))

(deftest repeated-tool-call-fingerprint-test
  (let [step {:directives [{:type :tool-call
                            :payload {:tool-name :fs_list
                                      :input {:path "."}}}]}
        state (nudge/record-execution (nudge/new-state) step [{:status :ok
                                                               :tool-name :fs_list
                                                               :input {:path "."}}])
        verdict (nudge/check-before-exec profile state {:step step
                                                        :llm-response {:tool-calls []}
                                                        :allowed-tools #{:fs_list}})]
    (is (= :retry (:action verdict)))
    (is (= :repeated-tool-call (:reason verdict)))
    (is (= {:tool-name :fs_list :input {:path "."}} (:fingerprint verdict)))))

(deftest fs-prereq-test
  (let [step {:directives [{:type :tool-call
                            :payload {:tool-name :fs_replace
                                      :input {:path "src/a.clj"
                                              :old-string "old"
                                              :new-string "new"}}}]}
        blocked (nudge/check-before-exec profile (nudge/new-state)
                                         {:step step :llm-response {} :allowed-tools #{:fs_replace}})
        state (nudge/record-execution (nudge/new-state)
                                      {:directives []}
                                      [{:status :ok
                                        :tool-name :fs_list
                                        :input {:path "src"}}])
        allowed (nudge/check-before-exec profile state
                                         {:step step :llm-response {} :allowed-tools #{:fs_replace}})]
    (is (= :retry (:action blocked)))
    (is (= :missing-prerequisite (:reason blocked)))
    (is (= :execute (:action allowed)))))
