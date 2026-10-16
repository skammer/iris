(ns agent.test-runner
  "Discover tests once; load and run them inside an isolated Iris home."
  (:require
   [agent.config :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t]))

(def test-roots ["test" "export/endive-clj/test"])

(defn test-namespaces
  "Discover conventional *_test.clj namespaces in the supplied source roots."
  ([] (test-namespaces test-roots))
  ([roots]
   (->> roots
        (mapcat (fn [root]
                  (let [root-path (.toPath (io/file root))]
                    (for [file (file-seq (io/file root))
                          :when (and (.isFile file)
                                     (str/ends-with? (.getName file) "_test.clj"))]
                      (-> (str (.relativize root-path (.toPath file)))
                          (str/replace #"\.clj$" "")
                          (str/replace #"[/\\]" ".")
                          (str/replace "_" "-")
                          symbol)))))
        distinct
        sort
        vec)))

(defn with-isolated-home
  "Run f with temporary config/data defaults, then remove the entire tree."
  [f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                     "iris-test-runner-"
                     (make-array java.nio.file.attribute.FileAttribute 0)))
        home (io/file root "home")
        native-dir (io/file root "native")
        sqlite-tmpdir (System/getProperty "org.sqlite.tmpdir")]
    (try
      (.mkdirs home)
      (.mkdirs native-dir)
      ;; SQLite JDBC scans its extraction directory on first connection. Keep
      ;; that scan away from a large shared OS temp directory and pool timeout.
      (System/setProperty "org.sqlite.tmpdir" (.getPath native-dir))
      (binding [config/*user-home* (constantly (.getPath home))
                config/*env* (fn [k]
                               (when-not (#{"IRIS_CONFIG_DIR" "IRIS_DATA_DIR" "XDG_CONFIG_HOME"} k)
                                 (System/getenv k)))]
        (f))
      (finally
        (if sqlite-tmpdir
          (System/setProperty "org.sqlite.tmpdir" sqlite-tmpdir)
          (System/clearProperty "org.sqlite.tmpdir"))
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file true))))))

(defn run-all-tests []
  (with-isolated-home
    (fn []
      (let [namespaces (cond->> (test-namespaces)
                         (not= "1" (System/getenv "AGENT_RELEASE_SMOKE"))
                         (remove #{'agent.release-smoke-test}))]
        (doseq [test-ns namespaces]
          (require test-ns))
        (apply t/run-tests namespaces)))))

(defn -main [& _args]
  (let [{:keys [fail error]} (run-all-tests)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
