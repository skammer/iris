(ns ^{:clj-kondo/config '{:lint-as {agent.config.test-support/with-isolated-config clojure.core/let}
                          :linters {:unused-binding {:level :off}
                                    :unresolved-symbol {:exclude [root]}}}}
  agent.config.test-support
  (:require
   [agent.config :as config]
   [clojure.java.io :as io]))

(defn thrown-message
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (.getMessage e))))

(defn thrown-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-config-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defmacro with-isolated-config
  [[root env] & body]
  `(let [~root (temp-dir)]
     (try
       (.mkdirs (io/file ~root "home"))
       (.mkdirs (io/file ~root "work"))
       (binding [config/*env* (fn [k#] (get ~env k#))
                 config/*user-home* (fn [] (.getPath (io/file ~root "home")))
                 config/*cwd* (fn [] (.getPath (io/file ~root "work")))
                 *err* (java.io.StringWriter.)]
         ~@body)
       (finally
         (io/delete-file ~root true)))))
