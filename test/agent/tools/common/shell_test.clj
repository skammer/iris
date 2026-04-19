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
        registry (-> (tools/create-registry {:approval-check (fn [_] nil)})
                     (tools/register-tool tool))
        result (tools/execute-tool registry :shell {:argv ["printf" "hello"]}
                                   {:permissions #{:shell-exec}})]
    (is (= 0 (:exit result)))
    (is (= "hello" (:stdout result)))
    (.delete root)))

(deftest shell-tool-blocks-non-allowlisted-command-test
  (let [root (temp-dir)
        tool (shell-tool/create-shell-tool {:roots [(.getAbsolutePath root)]
                                            :working-dir (.getAbsolutePath root)
                                            :timeout-ms 5000
                                            :allowed-commands ["printf"]})
        registry (-> (tools/create-registry {:approval-check (fn [_] nil)})
                     (tools/register-tool tool))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"allowlist"
                          (tools/execute-tool registry :shell {:argv ["uname" "-a"]}
                                              {:permissions #{:shell-exec}})))
    (.delete root)))

(deftest shell-tool-rejects-command-string-test
  (let [root (temp-dir)
        tool (shell-tool/create-shell-tool {:roots [(.getAbsolutePath root)]
                                            :working-dir (.getAbsolutePath root)
                                            :timeout-ms 5000})
        registry (-> (tools/create-registry {:approval-check (fn [_] nil)})
                     (tools/register-tool tool))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"command string is not supported"
                          (tools/execute-tool registry :shell {:command "printf hello; rm -rf /"}
                                              {:permissions #{:shell-exec}})))
    (.delete root)))
