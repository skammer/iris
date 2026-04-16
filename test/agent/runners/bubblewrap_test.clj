(ns agent.runners.bubblewrap-test
  (:require
   [agent.runners.bubblewrap :as bubblewrap]
   [clojure.test :refer :all]))

(deftest build-bwrap-argv-test
  (let [argv (bubblewrap/build-bwrap-argv
              {:bwrap-binary "bwrap"
               :working-dir "/workspace"
               :binds [{:source "/workspace" :target "/workspace" :mode :rw}
                       {:source "/nix/store" :target "/nix/store" :mode :ro}]
               :command ["printf" "hello"]})]
    (is (= "bwrap" (first argv)))
    (is (some #{"--unshare-net"} argv))
    (is (some #{"--bind"} argv))
    (is (some #{"--ro-bind"} argv))
    (is (= ["--" "printf" "hello"] (subvec argv (- (count argv) 3))))))
