(ns agent.runners.bubblewrap-test
  (:require
   [agent.runners.bubblewrap :as bubblewrap]
   [agent.runners.core :as runners]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-bwrap-bind-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest build-bwrap-argv-test
  (let [argv (bubblewrap/build-bwrap-argv
              {:bwrap-binary "bwrap"
               :working-dir "/workspace"
               :binds [{:source "/workspace" :target "/workspace" :mode "rw"}
                       {:source "/nix/store" :target "/nix/store" :mode :ro}]
               :command ["printf" "hello"]})]
    (is (= "bwrap" (first argv)))
    (is (some #{"--unshare-net"} argv))
    (is (some #{"--clearenv"} argv))
    (is (some #{"--bind"} argv))
    (is (some #{"--ro-bind"} argv))
    (is (= ["--" "printf" "hello"] (subvec argv (- (count argv) 3))))))

(deftest bubblewrap-runner-normalizes-string-bind-mode-test
  (let [bind-dir (temp-dir)]
    (try
      (let [captured (atom nil)
            delegate (reify runners/IRunner
                       (launch [_ run-spec]
                         (reset! captured run-spec)
                         {:ok true})
                       (signal [_ _ _] nil)
                       (status [_ _] nil)
                       (stop [_ _] nil))
            runner (bubblewrap/create-bubblewrap-runner {:delegate delegate
                                                         :bwrap-binary "bwrap"})
            source (.getAbsolutePath bind-dir)
            run-spec (runners/create-run-spec
                      {:run-id "run-bwrap-bind"
                       :agent-id "agent-bwrap-bind"
                       :substrate :bubblewrap
                       :runner-options {:host-working-dir "."
                                        :working-dir "/workspace"
                                        :binds [{:source source
                                                 :target "/workspace"
                                                 :mode "rw"}]
                                        :command ["printf" "hello"]}})]
        (runners/launch runner run-spec)
        (let [argv (get-in @captured [:runner-options :command])]
          (is (some #{"--bind"} argv))
          (is (some #{source} argv))))
      (finally
        (io/delete-file bind-dir true)))))
