(ns agent.runners.local-process-test
  (:require
   [agent.runners.core :as runners]
   [agent.runners.local-process :as local-process]
   [clojure.test :refer :all]))

(deftest local-process-runner-launch-status-stop-test
  (let [exits (promise)
        runner (local-process/create-local-process-runner
                {:on-exit (fn [_run-id result]
                            (deliver exits result))})
        run-spec (runners/create-run-spec
                  {:run-id "run-local-test"
                   :agent-id "agent-local"
                   :substrate :local-process
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
