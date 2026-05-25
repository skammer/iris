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
