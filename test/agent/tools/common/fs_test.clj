(ns agent.tools.common.fs-test
  (:require
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-agent-fs-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest fs-tool-read-write-list-test
  (let [root (temp-dir)
        tool (fs-tool/create-fs-tool {:roots [(.getAbsolutePath root)]})
        registry (-> (tools/create-registry)
                     (tools/register-tool tool))
        file-path (.getAbsolutePath (io/file root "note.txt"))
        _ (tools/execute-tool registry :fs {:action :write
                                            :path file-path
                                            :content "hello"}
                              {:permissions #{:filesystem-write}})
        read-result (tools/execute-tool registry :fs {:action :read
                                                      :path file-path}
                                        {:permissions #{:filesystem-read}})
        list-result (tools/execute-tool registry :fs {:action :list
                                                      :path (.getAbsolutePath root)}
                                        {:permissions #{:filesystem-read}})]
    (is (= "hello" (:content read-result)))
    (is (= ["note.txt"] (mapv :name (:entries list-result))))
    (io/delete-file file-path true)
    (.delete root)))
