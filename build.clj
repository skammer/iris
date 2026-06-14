(ns build
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [clojure.tools.build.api :as b])
  (:import
   (java.time Instant)))

(def lib 'iris/iris)
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn- sh-output [& args]
  (let [{:keys [exit out]} (apply sh/sh args)]
    (when (zero? exit)
      (str/trim out))))

(defn- write-build-info! []
  (let [version (or (not-empty (System/getenv "IRIS_VERSION"))
                    (sh-output "git" "describe" "--tags" "--always" "--dirty")
                    "dev")
        commit (sh-output "git" "rev-parse" "HEAD")
        commit-short (or (sh-output "git" "rev-parse" "--short=12" "HEAD")
                         version)
        dirty? (not (str/blank? (or (sh-output "git" "status" "--porcelain") "")))
        file (io/file class-dir "agent" "build_info.edn")]
    (.mkdirs (.getParentFile file))
    (spit file
          (pr-str {:version version
                   :commit commit
                   :commit-short commit-short
                   :dirty? dirty?
                   :built-at (str (Instant/now))}))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar
  [{:keys [jar]
    :or {jar "target/iris.jar"}}]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/copy-dir {:src-dirs ["public"]
               :target-dir (str class-dir "/public")})
  (write-build-info!)
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :ns-compile '[agent.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file jar
           :basis @basis
           :main 'agent.core}))
