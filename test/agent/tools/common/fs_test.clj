(ns agent.tools.common.fs-test
  (:require
   [agent.tools.common.fs :as fs-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]])
  (:import
   [java.nio.file Files]))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-fs-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn approved-registry [tools*]
  (reduce tools/register-tool
          (tools/create-registry {:approval-check (fn [_] {:allow true})})
          tools*))

(deftest fs-tool-read-write-list-test
  (let [root (temp-dir)
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tools*)
        file-path (.getAbsolutePath (io/file root "note.txt"))
        _ (tools/execute-tool registry :fs_write {:path file-path
                                            :content "hello"}
                              {:permissions #{:filesystem-write}})
        read-result (tools/execute-tool registry :fs_read {:path file-path}
                                        {:permissions #{:filesystem-read}})
        list-result (tools/execute-tool registry :fs_list {:path (.getAbsolutePath root)}
                                        {:permissions #{:filesystem-read}})]
    (is (= "hello" (:content read-result)))
    (is (= ["note.txt"] (mapv :name (:entries list-result))))
    (io/delete-file file-path true)
    (.delete root)))

(deftest fs-tool-expands-home-root-and-path-test
  (let [tools* (fs-tool/create-fs-tools {:roots ["~"]})
        registry (approved-registry tools*)
        home (.getCanonicalPath (io/file (System/getProperty "user.home")))
        health (tools/health-check (first tools*))
        result (tools/execute-tool registry :fs_list {:path "~"}
                                   {:permissions #{:filesystem-read}})]
    (is (= [home] (get-in health [:details :roots])))
    (is (= home (:path result)))))

(deftest fs-tool-enforces-write-quota-test
  (let [root (temp-dir)
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]
                                      :max-write-bytes 4})
        registry (approved-registry tools*)
        file-path (.getAbsolutePath (io/file root "note.txt"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"max-write-bytes"
                          (tools/execute-tool registry :fs_write {:path file-path
                                                            :content "hello"}
                                              {:permissions #{:filesystem-write}})))
    (.delete root)))

(deftest fs-tool-create-refuses-existing-path-test
  (let [root (temp-dir)
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tools*)
        file-path (.getAbsolutePath (io/file root "note.txt"))]
    (tools/execute-tool registry :fs_create {:path file-path
                                      :content "hello"}
                        {:permissions #{:filesystem-write}})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Path already exists"
                          (tools/execute-tool registry :fs_create {:path file-path
                                                            :content "again"}
                                              {:permissions #{:filesystem-write}})))
    (io/delete-file file-path true)
    (.delete root)))

(deftest fs-tool-refuses-symlink-paths-test
  (let [root (temp-dir)
        target (io/file root "target.txt")
        link (io/file root "link.txt")
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tools*)]
    (spit target "safe")
    (try
      (Files/createSymbolicLink (.toPath link)
                                (.toPath target)
                                (make-array java.nio.file.attribute.FileAttribute 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"symlink"
                            (tools/execute-tool registry :fs_read {:path (.getAbsolutePath link)}
                                                {:permissions #{:filesystem-read}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"symlink"
                            (tools/execute-tool registry :fs_write {:path (.getAbsolutePath link)
                                                              :content "blocked"}
                                                {:permissions #{:filesystem-write}})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"symlink"
                            (tools/execute-tool registry :fs_search {:path (.getAbsolutePath link)
                                                                     :query "safe"}
                                                {:permissions #{:filesystem-read}})))
      (is (= "safe" (slurp target)))
      (catch UnsupportedOperationException _
        (is true "symbolic links unsupported"))
      (finally
        (io/delete-file link true)
        (io/delete-file target true)
        (.delete root)))))

(deftest fs-tool-replace-requires-unique-old-string-test
  (let [root (temp-dir)
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]})
        registry (approved-registry tools*)
        file-path (.getAbsolutePath (io/file root "note.txt"))]
    (spit file-path "one two one")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"old-string is not unique"
                          (tools/execute-tool registry :fs_replace {:path file-path
                                                            :old-string "one"
                                                            :new-string "three"}
                                              {:permissions #{:filesystem-write}})))
    (tools/execute-tool registry :fs_replace {:path file-path
                                      :old-string "two"
                                      :new-string "four"}
                        {:permissions #{:filesystem-write}})
    (is (= "one four one" (slurp file-path)))
    (io/delete-file file-path true)
    (.delete root)))

(deftest fs-search-finds-bounded-literal-regex-and-glob-matches-test
  (let [root (temp-dir)
        source-dir (io/file root "src")
        clj-file (io/file source-dir "review.clj")
        text-file (io/file source-dir "notes.txt")
        _ (.mkdirs source-dir)
        _ (spit clj-file "(defn review []\n  \"MAGI verdict\")\n")
        _ (spit text-file "magi note\n")
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]
                                         :max-search-results 10})
        registry (approved-registry tools*)
        context {:permissions #{:filesystem-read}}
        literal (tools/execute-tool registry
                                    :fs_search
                                    {:path (.getAbsolutePath root)
                                     :query "magi"
                                     :glob "*.clj"}
                                    context)
        regex (tools/execute-tool registry
                                  :fs_search
                                  {:path (.getAbsolutePath root)
                                   :query "defn\\s+review"
                                   :regex? true
                                   :case-sensitive? true}
                                  context)]
    (is (= 1 (count (:matches literal))))
    (is (= 2 (get-in literal [:matches 0 :line])))
    (is (= (.getCanonicalPath clj-file) (get-in literal [:matches 0 :path])))
    (is (= 1 (count (:matches regex))))
    (is (= 1 (get-in regex [:matches 0 :line])))
    (is (false? (:truncated literal)))
    (io/delete-file clj-file true)
    (io/delete-file text-file true)
    (.delete source-dir)
    (.delete root)))

(deftest fs-search-enforces-result-and-pattern-limits-test
  (let [root (temp-dir)
        file (io/file root "many.txt")
        _ (spit file "hit one\nhit two\nhit three\n")
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]
                                         :max-search-results 2})
        registry (approved-registry tools*)
        context {:permissions #{:filesystem-read}}
        result (tools/execute-tool registry
                                   :fs_search
                                   {:path (.getAbsolutePath root)
                                    :query "hit"}
                                   context)]
    (is (= 2 (count (:matches result))))
    (is (:truncated result))
    (is (= "max-results" (:truncation-reason result)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"valid regular expression"
                          (tools/execute-tool registry
                                              :fs_search
                                              {:path (.getAbsolutePath root)
                                               :query "["
                                               :regex? true}
                                              context)))
    (io/delete-file file true)
    (.delete root)))

(deftest fs-search-skips-binary-and-oversized-files-and-enforces-root-test
  (let [root (temp-dir)
        outside (temp-dir)
        binary (io/file root "binary.dat")
        oversized (io/file root "large.txt")
        outside-file (io/file outside "outside.txt")
        _ (spit binary "needle\u0000binary")
        _ (spit oversized "needle is too large")
        _ (spit outside-file "needle")
        tools* (fs-tool/create-fs-tools {:roots [(.getAbsolutePath root)]
                                         :max-search-file-bytes 16})
        registry (approved-registry tools*)
        context {:permissions #{:filesystem-read}}
        result (tools/execute-tool registry
                                   :fs_search
                                   {:path (.getAbsolutePath root)
                                    :query "needle"}
                                   context)]
    (is (empty? (:matches result)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"outside allowed roots"
                          (tools/execute-tool registry
                                              :fs_search
                                              {:path (.getAbsolutePath outside)
                                               :query "needle"}
                                              context)))
    (io/delete-file binary true)
    (io/delete-file oversized true)
    (io/delete-file outside-file true)
    (.delete root)
    (.delete outside)))
