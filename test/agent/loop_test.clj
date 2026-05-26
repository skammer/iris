(ns agent.loop-test
  (:require
   [agent.loop :as loop]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest loop-prompt-carries-plan-summary-and-validation-test
  (let [plan (java.io.File/createTempFile "iris-loop-" ".md")]
    (try
      (spit plan "- [ ] one\n- [x] done")
      (let [state (assoc (loop/new-state {:prompt "ship it"
                                          :plan-file (.getAbsolutePath plan)
                                          :max-iterations 2
                                          :run-cmd "printf ok"})
                         :iteration 1
                         :last-summary "changed files"
                         :last-run-output "exit 0")
            prompt (loop/build-prompt state)]
        (is (str/includes? prompt "ship it"))
        (is (str/includes? prompt "- [ ] one"))
        (is (str/includes? prompt "changed files"))
        (is (str/includes? prompt "exit 0"))
        (is (str/includes? prompt "Choose ONE task")))
      (finally
        (io/delete-file plan true)))))

(deftest loop-stop-detects-max-and-complete-plan-test
  (let [plan (java.io.File/createTempFile "iris-loop-" ".md")]
    (try
      (spit plan "- [x] one\n- [X] two")
      (is (true? (loop/should-stop? (loop/new-state {:prompt "x"
                                                     :plan-file (.getAbsolutePath plan)}))))
      (is (true? (loop/should-stop? {:iteration 2 :max-iterations 2})))
      (is (false? (loop/should-stop? {:iteration 1 :max-iterations 2})))
      (finally
        (io/delete-file plan true)))))

(deftest loop-progress-summary-is-structured-test
  (let [plan (java.io.File/createTempFile "iris-loop-" ".md")]
    (try
      (spit plan "- [x] done\n- [ ] next task")
      (let [summary (loop/progress-summary
                     {:response "Changed src/agent/loop.clj and test/agent/loop_test.clj."
                      :validation-output "exit 0\n\nTesting agent.loop-test\n\nRan 3 tests containing 8 assertions.\n0 failures, 0 errors."
                      :plan-file (.getAbsolutePath plan)
                      :summary-max-chars 200})
            rendered (loop/render-progress summary)]
        (is (= ["src/agent/loop.clj" "test/agent/loop_test.clj"]
               (:changed-files summary)))
        (is (= "next task" (:next-plan-item summary)))
        (is (str/includes? rendered "changed_files: src/agent/loop.clj"))
        (is (str/includes? rendered "validation: exit 0")))
      (finally
        (io/delete-file plan true)))))

(deftest loop-validation-output-is-capped-test
  (let [output (loop/run-validation "printf 1234567890" {:validation-max-chars 8})]
    (is (str/starts-with? output "exit 0"))
    (is (str/includes? output "[truncated"))))
