(ns agent.logging-test
  (:require
   [agent.logging :as logging]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(deftest start-and-write-log-file
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "clj-agent-logging-" (System/nanoTime)))
        file (io/file dir "agent.log")]
    (.mkdirs dir)
    (try
      (logging/stop!)
      (logging/start! {:enabled true
                       :file {:path (.getAbsolutePath file)}
                       :context {:service-name "clj-agent-test"}})
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
