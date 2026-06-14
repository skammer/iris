(ns agent.build-info
  "Build metadata generated into the release JAR."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def fallback
  {:version "dev"
   :commit-short "dev"
   :dirty? false
   :built-at nil})

(defn read-build-info []
  (if-let [resource (io/resource "agent/build_info.edn")]
    (merge fallback (edn/read-string (slurp resource)))
    fallback))
