(ns agent.tools.common.fs
  "Filesystem tool with bounded roots."
  (:require
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private allowed-actions
  #{:read :write :list :delete :mkdir})

(defn- normalize-action [action]
  (cond
    (keyword? action) action
    (string? action) (keyword (str/lower-case action))
    :else nil))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- resolve-path! [roots path]
  (when-not (and (string? path) (not (str/blank? path)))
    (throw (tools/validation-error "path must be a non-blank string" {:path path})))
  (let [candidate (io/file path)
        canonical (canonical-path candidate)]
    (when-not (within-root? roots canonical)
      (throw (tools/tool-error :path-not-allowed
                               "Path is outside allowed roots"
                               {:path canonical
                                :roots roots})))
    canonical))

(defn- ensure-permission! [context action]
  (let [permissions (:permissions context)]
    (case action
      :read (when-not (contains? permissions :filesystem-read)
              (throw (tools/permission-error #{:filesystem-read} permissions)))
      :list (when-not (contains? permissions :filesystem-read)
              (throw (tools/permission-error #{:filesystem-read} permissions)))
      (:write :delete :mkdir) (when-not (contains? permissions :filesystem-write)
                                (throw (tools/permission-error #{:filesystem-write} permissions)))
      nil)))

(defn- validate-input [input]
  (let [action (normalize-action (:action input))]
    (when-not (allowed-actions action)
      (throw (tools/validation-error "action must be one of read/write/list/delete/mkdir"
                                     {:action (:action input)})))
    (assoc input :action action)))

(defn create-fs-tool
  [opts]
  (let [config (merge {:roots ["."]
                       :max-read-bytes 1048576}
                      opts)
        roots (mapv canonical-path (:roots config))]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :fs
       "Filesystem tool"
       :category :system
       :input-schema {:required [:action :path]
                      :optional [:content]}
       :source :builtin)
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                    :details {:roots roots}})
      :execute-fn
      (fn [input context]
        (let [action (:action input)
              path (resolve-path! roots (:path input))]
          (ensure-permission! context action)
          (case action
            :read (let [file (io/file path)
                        size (.length file)]
                    (when-not (.isFile file)
                      (throw (tools/tool-error :not-found "File not found" {:path path})))
                    (when (> size (:max-read-bytes config))
                      (throw (tools/tool-error :file-too-large "File exceeds max-read-bytes"
                                               {:path path
                                                :size size
                                                :max-read-bytes (:max-read-bytes config)})))
                    {:path path
                     :content (slurp file)})
            :write (do
                     (spit path (or (:content input) ""))
                     {:path path
                      :written true})
            :list (let [file (io/file path)]
                    (when-not (.isDirectory file)
                      (throw (tools/tool-error :not-directory "Path is not a directory" {:path path})))
                    {:path path
                     :entries (->> (.listFiles file)
                                   (map (fn [entry]
                                          {:name (.getName entry)
                                           :path (.getCanonicalPath entry)
                                           :type (if (.isDirectory entry) "directory" "file")}))
                                   (sort-by :name)
                                   vec)})
            :delete (let [file (io/file path)]
                      (when-not (.exists file)
                        (throw (tools/tool-error :not-found "Path not found" {:path path})))
                      (io/delete-file file true)
                      {:path path
                       :deleted true})
            :mkdir (let [file (io/file path)]
                     (.mkdirs file)
                     {:path path
                      :created true}))))})))
