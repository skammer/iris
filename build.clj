(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'iris/iris)
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def version "0.1.0")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar
  [{:keys [jar]
    :or {jar (format "target/iris-%s.jar" version)}}]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :ns-compile '[agent.core]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file jar
           :basis @basis
           :main 'agent.core}))
