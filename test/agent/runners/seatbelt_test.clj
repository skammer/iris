(ns agent.runners.seatbelt-test
  (:require
   [agent.runners.core :as runners]
   [agent.runners.seatbelt :as seatbelt]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest build-seatbelt-profile-test
  (let [profile (seatbelt/build-seatbelt-profile
                 {:working-dir "/Users/example/work"
                  :read-only-paths ["/opt/data"]
                  :read-write-paths ["/Users/example/work/tmp"]
                  :allow-network? false})]
    (is (str/includes? profile "(version 1)"))
    (is (str/includes? profile "(deny default)"))
    (is (str/includes? profile "(import \"system.sb\")"))
    (is (str/includes? profile "(allow process*)"))
    (is (str/includes? profile "(subpath \"/Users/example/work\")"))
    (is (str/includes? profile "(subpath \"/opt/data\")"))
    (is (str/includes? profile "(subpath \"/Users/example/work/tmp\")"))
    (is (str/includes? profile "(deny network*)"))))

(deftest build-seatbelt-argv-test
  (let [argv (seatbelt/build-seatbelt-argv
              {:sandbox-exec-binary "/usr/bin/sandbox-exec"
               :profile-string "(version 1)"
               :command ["/usr/bin/printf" "hello"]})]
    (is (= "/usr/bin/sandbox-exec" (first argv)))
    (is (= "-p" (second argv)))
    (is (= "(version 1)" (nth argv 2)))
    (is (= ["/usr/bin/printf" "hello"] (subvec argv 3)))))

(deftest build-seatbelt-profile-canonicalizes-paths-test
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "iris-seatbelt-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        messy (str (.getAbsolutePath dir) "/../" (.getName dir))]
    (try
      (let [profile (seatbelt/build-seatbelt-profile
                     {:working-dir messy
                      :read-only-paths [messy]
                      :allow-network? false})
            canonical (.getCanonicalPath dir)]
        (is (str/includes? profile (str "(subpath \"" canonical "\")")))
        (is (not (str/includes? profile "/../"))))
      (finally
        (io/delete-file dir true)))))

(deftest seatbelt-launch-rejects-raw-profile-test
  (let [runner (seatbelt/create-seatbelt-runner
                {:delegate (reify runners/IRunner
                             (launch [_ _] {:ok true})
                             (signal [_ _ _] nil)
                             (status [_ _] nil)
                             (stop [_ _] nil))})
        run-spec (runners/create-run-spec
                  {:run-id "run-seatbelt"
                   :agent-id "agent-seatbelt"
                   :substrate :seatbelt
                   :runner-options {:profile-string "(allow default)"
                                    :command ["/usr/bin/printf" "hello"]}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"generated immutable profile"
                          (runners/launch runner run-spec)))))

(deftest seatbelt-launch-validates-profile-paths-test
  (let [runner (seatbelt/create-seatbelt-runner
                {:delegate (reify runners/IRunner
                             (launch [_ _] {:ok true})
                             (signal [_ _ _] nil)
                             (status [_ _] nil)
                             (stop [_ _] nil))})
        run-spec (runners/create-run-spec
                  {:run-id "run-seatbelt"
                   :agent-id "agent-seatbelt"
                   :substrate :seatbelt
                   :runner-options {:read-only-paths ["/path/that/does/not/exist"]
                                    :command ["/usr/bin/printf" "hello"]}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"seatbelt path must exist"
                          (runners/launch runner run-spec)))))
