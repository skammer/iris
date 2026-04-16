(ns agent.runners.seatbelt-test
  (:require
   [agent.runners.seatbelt :as seatbelt]
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
