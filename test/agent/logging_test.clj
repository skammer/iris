(ns agent.logging-test
  (:require
   [agent.logging :as logging]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest start-and-write-log-file
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-" (System/nanoTime)))
        file (io/file dir "agent.log")]
    (.mkdirs dir)
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)}
                       :context {:service-name "iris-test"}})
      (logging/log! :agent.logging/test-event {:value 42})
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (is (.exists file))
      (is (str/includes? (slurp file) "agent.logging/test-event"))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file dir true)))))

(deftest masks-sensitive-log-fields
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-mask-" (System/nanoTime)))
        file (io/file dir "agent.log")]
    (.mkdirs dir)
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)}})
      (logging/log! :agent.logging/secret-event
                    {:api-key "sk-test"
                     :nested {:authorization "Bearer token-value"
                              :safe "visible"}})
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (let [body (slurp file)]
        (is (str/includes? body "***REDACTED***"))
        (is (str/includes? body "visible"))
        (is (not (str/includes? body "sk-test")))
        (is (not (str/includes? body "token-value"))))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file (io/file (str (.getAbsolutePath file) ".1")) true)
        (io/delete-file dir true)))))

(deftest rotates-existing-log-file-on-start
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "iris-logging-rotate-" (System/nanoTime)))
        file (io/file dir "agent.log")
        rotated (io/file dir "agent.log.1")]
    (.mkdirs dir)
    (spit file "0123456789")
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)
                              :max-bytes 5
                              :max-files 2}})
      (logging/log! :agent.logging/after-rotate {:value 1})
      (Thread/sleep 250)
      (logging/stop!)
      (Thread/sleep 150)
      (is (.exists rotated))
      (is (= "0123456789" (slurp rotated)))
      (is (str/includes? (slurp file) "agent.logging/after-rotate"))
      (finally
        (logging/stop!)
        (io/delete-file file true)
        (io/delete-file rotated true)
        (io/delete-file (io/file dir "agent.log.2") true)
        (io/delete-file dir true)))))
