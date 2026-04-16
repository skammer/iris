(ns agent.tools.common.shell-test
  (:require
   [agent.tools.common.shell :as shell-tool]
   [agent.tools.core :as tools]
   [clojure.test :refer :all]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-agent-shell-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest shell-tool-executes-command-test
  (let [root (temp-dir)
        tool (shell-tool/create-shell-tool {:roots [(.getAbsolutePath root)]
                                            :working-dir (.getAbsolutePath root)
                                            :timeout-ms 5000})
        registry (-> (tools/create-registry)
                     (tools/register-tool tool))
        result (tools/execute-tool registry :shell {:command "printf hello"}
                                   {:permissions #{:shell-exec}})]
    (is (= 0 (:exit result)))
    (is (= "hello" (:stdout result)))
    (.delete root)))
