(ns agent.runners.docker-podman-test
  (:require
   [agent.runners.docker-podman :as docker-podman]
   [clojure.test :refer :all]))

(deftest build-docker-argv-test
  (let [argv (docker-podman/build-container-argv
              {:engine-binary "docker"
               :run-id "Run-123"
               :image "clj-agent:test"
               :working-dir "/workspace"
               :mounts [{:source "/tmp/work" :target "/workspace" :mode :rw}
                        {:source "/tmp/cache" :target "/cache" :mode :ro}]
               :env {"A" "1" "B" "two"}
               :command ["clojure" "-M" "-m" "agent.runtime.child"]})]
    (is (= "docker" (first argv)))
    (is (some #{"--rm"} argv))
    (is (some #{"--network"} argv))
    (is (some #{"none"} argv))
    (is (some #{"clj-agent:test"} argv))
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
    (is (= ["printf" "hello"] (subvec argv (- (count argv) 2))))))
