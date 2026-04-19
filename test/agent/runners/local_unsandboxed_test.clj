(ns agent.runners.local-unsandboxed-test
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-unsandboxed :as local-unsandboxed]
   [clojure.test :refer :all]))

(deftest local-unsandboxed-runner-launch-status-stop-test
  (let [exits (promise)
        runner (local-unsandboxed/create-local-unsandboxed-runner
                {:on-exit (fn [_run-id result]
                            (deliver exits result))})
        run-spec (runners/create-run-spec
                  {:run-id "run-local-test"
                   :agent-id "agent-local"
                   :substrate :local-unsandboxed
                   :bootstrap-token "token"
                   :bootstrap-spec {:run-id "run-local-test"}
                   :runner-options {:command ["sh" "-lc" "sleep 30"]
                                    :working-dir "."}})
        launch-result (runners/launch runner run-spec)
        status-before (runners/status runner "run-local-test")
        _ (runners/stop runner "run-local-test")
        exit-result (deref exits 5000 nil)
        status-after (runners/status runner "run-local-test")]
    (is (= "run-local-test" (:run-id launch-result)))
    (is (number? (:pid launch-result)))
    (is (true? (:alive status-before)))
    (is (some? exit-result))
    (is (false? (:alive status-after)))))

(deftest local-unsandboxed-runner-captures-stdout-and-stderr-test
  (let [events* (atom [])
        exits (promise)
        runner (local-unsandboxed/create-local-unsandboxed-runner
                {:on-exit (fn [_run-id result]
                            (deliver exits result))
                 :on-output (fn [_run-id output]
                              (swap! events* conj output))})
        run-spec (runners/create-run-spec
                  {:run-id "run-local-output"
                   :agent-id "agent-local"
                   :substrate :local-unsandboxed
                   :bootstrap-token "token"
                   :bootstrap-spec {:run-id "run-local-output"}
                   :runner-options {:command ["sh" "-lc" "printf out-line; printf err-line >&2"]
                                    :working-dir "."}})]
    (runners/launch runner run-spec)
    (is (= 0 (:exit-code (deref exits 5000 nil))))
    (is (= #{"out-line" "err-line"} (set (map :line @events*))))
    (is (= #{"stdout" "stderr"} (set (map (comp name :stream) @events*))))))
