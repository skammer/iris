(ns agent.memory.paths
  "Canonical vault paths and filesystem policy boundaries."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn ^:no-doc canonical-roots [paths]
  (mapv canonical-path paths))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn ^:no-doc ensure-vault-path! [memory-service path]
  (let [vault-roots (:vault-roots memory-service)
        fs-roots (:fs-roots memory-service)
        target (canonical-path path)]
    (when-not (seq vault-roots)
      (throw (ex-info "Vault memory is not configured" {:type :vault-memory-disabled})))
    (when-not (within-root? vault-roots target)
      (throw (ex-info "Path is outside configured memory vault roots"
                      {:type :path-not-allowed
                       :path target
                       :roots vault-roots})))
    (when (and (seq fs-roots) (not (within-root? fs-roots target)))
      (throw (ex-info "Path is outside filesystem policy roots"
                      {:type :path-not-allowed
                       :path target
                       :roots fs-roots})))
    target))

(defn ^:no-doc vault-root-for-path [memory-service path]
  (let [target (canonical-path path)]
    (some #(when (or (= target %)
                     (str/starts-with? target (str % java.io.File/separator)))
             %)
          (:vault-roots memory-service))))
