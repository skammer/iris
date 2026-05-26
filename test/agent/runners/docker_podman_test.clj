(ns agent.runners.docker-podman-test
  (:require
   [agent.runners.core :as runners]
   [agent.runners.docker-podman :as docker-podman]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest build-docker-argv-test
  (let [argv (docker-podman/build-container-argv
              {:engine-binary "docker"
               :run-id "Run-123"
               :image "iris:test"
               :working-dir "/workspace"
               :mounts [{:source "/tmp/work" :target "/workspace" :mode :rw}
                        {:source "/tmp/cache" :target "/cache" :mode :ro}]
               :env {"A" "1" "B" "two"}
               :user "1000:1000"
               :command ["clojure" "-M" "-m" "agent.runtime.child"]})]
    (is (= "docker" (first argv)))
    (is (some #{"--rm"} argv))
    (is (some #{"--network"} argv))
    (is (some #{"none"} argv))
    (is (some #{"--user"} argv))
    (is (some #{"1000:1000"} argv))
    (is (some #{"iris:test"} argv))
    (is (some #{"-v"} argv))
    (is (some #{"A=1"} argv))
    (is (= ["clojure" "-M" "-m" "agent.runtime.child"] (subvec argv (- (count argv) 4))))))

(deftest build-podman-argv-test
  (let [argv (docker-podman/build-container-argv
              {:engine-binary "podman"
               :run-id "run-podman"
               :image "agent:test"
               :share-network? true
               :command ["printf" "hello"]})]
    (is (= "podman" (first argv)))
    (is (not-any? #{"none"} argv))
    (is (some #{"--user"} argv))
    (is (some #{docker-podman/default-container-user} argv))
    (is (= ["printf" "hello"] (subvec argv (- (count argv) 2))))))

(deftest docker-runner-rejects-root-user-test
  (let [runner (docker-podman/create-docker-podman-runner)
        run-spec (runners/create-run-spec
                  {:run-id "run-root"
                   :agent-id "agent-root"
                   :substrate :docker
                   :runner-options {:image "agent:test"
                                    :user "0:0"
                                    :command ["printf" "hello"]}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must not be root"
                          (runners/launch runner run-spec)))))

(deftest docker-runner-forwards-bootstrap-env-test
  (let [captured (atom nil)
        delegate (reify runners/IRunner
                   (launch [_ run-spec]
                     (reset! captured run-spec)
                     {:ok true})
                   (signal [_ _ _] nil)
                   (status [_ _] nil)
                   (stop [_ _] nil))
        runner (docker-podman/create-docker-podman-runner {:delegate delegate
                                                           :engine-binary "docker"})
        run-spec (runners/create-run-spec
	                  {:run-id "run-1"
	                   :agent-id "agent-1"
	                   :substrate :docker
	                   :bootstrap-token "token-1"
                   :bootstrap-spec {:run-id "run-1"}
                   :runner-options {:image "iris:test"
                                    :command ["clojure" "-M" "-m" "agent.runtime.child"]}})]
    (runners/launch runner run-spec)
    (let [argv (get-in @captured [:runner-options :command])]
      (is (some #{"AGENT_RUN_ID=run-1"} argv))
      (is (some #{"AGENT_AGENT_ID=agent-1"} argv))
      (is (some #{"AGENT_BOOTSTRAP_TOKEN=token-1"} argv))
      (is (some #(str/includes? % "AGENT_BOOTSTRAP_SPEC={:run-id \"run-1\"}") argv)))))
