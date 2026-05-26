(ns agent.tools.common.fs-test
  (:require
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-fs-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn approved-registry [tool]
  (-> (tools/create-registry {:approval-check (fn [_] {:allow true})})
      (tools/register-tool tool)))

(deftest fs-tool-read-write-list-test
  (let [root (temp-dir)
        tool (fs-tool/create-fs-tool {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tool)
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

(deftest fs-tool-enforces-write-quota-test
  (let [root (temp-dir)
        tool (fs-tool/create-fs-tool {:roots [(.getAbsolutePath root)]
                                      :max-write-bytes 4})
        registry (approved-registry tool)
        file-path (.getAbsolutePath (io/file root "note.txt"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"max-write-bytes"
                          (tools/execute-tool registry :fs {:action :write
                                                            :path file-path
                                                            :content "hello"}
                                              {:permissions #{:filesystem-write}})))
    (.delete root)))

(deftest fs-tool-create-refuses-existing-path-test
  (let [root (temp-dir)
        tool (fs-tool/create-fs-tool {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tool)
        file-path (.getAbsolutePath (io/file root "note.txt"))]
    (tools/execute-tool registry :fs {:action :create
                                      :path file-path
                                      :content "hello"}
                        {:permissions #{:filesystem-write}})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Path already exists"
                          (tools/execute-tool registry :fs {:action :create
                                                            :path file-path
                                                            :content "again"}
                                              {:permissions #{:filesystem-write}})))
    (io/delete-file file-path true)
    (.delete root)))

(deftest fs-tool-replace-requires-unique-old-string-test
  (let [root (temp-dir)
        tool (fs-tool/create-fs-tool {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tool)
        file-path (.getAbsolutePath (io/file root "note.txt"))]
    (spit file-path "one two one")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"old-string is not unique"
                          (tools/execute-tool registry :fs {:action :replace
                                                            :path file-path
                                                            :old-string "one"
                                                            :new-string "three"}
                                              {:permissions #{:filesystem-write}})))
    (tools/execute-tool registry :fs {:action :replace
                                      :path file-path
                                      :old-string "two"
                                      :new-string "four"}
                        {:permissions #{:filesystem-write}})
    (is (= "one four one" (slurp file-path)))
    (io/delete-file file-path true)
    (.delete root)))
