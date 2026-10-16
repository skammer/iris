(ns agent.test-runner-test
  (:require
   [agent.config :as config]
   [agent.test-runner :as runner]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

(deftest discovers-nested-tests-without-loading-helpers
  (runner/with-isolated-home
    (fn []
      (let [root (io/file (config/*user-home*) "tests")
            files ["feature_test.clj" "nested/my_feature_test.clj" "nested/helper.clj"]]
        (doseq [path files]
          (let [file (io/file root path)]
            (io/make-parents file)
            (spit file "")))
        (is (= '[feature-test nested.my-feature-test]
               (runner/test-namespaces [root root])))))))

(deftest isolated-defaults-clean-up-even-after-failure
  (let [home (atom nil)
        sqlite-tmpdir (System/getProperty "org.sqlite.tmpdir")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"fixture failed"
         (runner/with-isolated-home
           (fn []
             (reset! home (config/*user-home*))
             (is (nil? (config/*env* "IRIS_CONFIG_DIR")))
             (is (nil? (config/*env* "IRIS_DATA_DIR")))
             (is (.isDirectory (io/file (System/getProperty "org.sqlite.tmpdir"))))
             (is (= (.getParent (io/file @home))
                    (.getParent (io/file (System/getProperty "org.sqlite.tmpdir")))))
             (let [file (io/file (config/data-dir (config/global-config-dir)) "nested" "test.edn")]
               (io/make-parents file)
               (spit file "{}"))
             (throw (ex-info "fixture failed" {}))))))
    (is (not (.exists (io/file @home))))
    (is (= sqlite-tmpdir (System/getProperty "org.sqlite.tmpdir")))))
