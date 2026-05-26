(ns agent.tools.common.fs
  "Filesystem tool with bounded roots."
  (:require
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private allowed-actions
  #{:read :write :create :replace :list :delete :mkdir})

(defn- normalize-action [action]
  (cond
    (keyword? action) action
    (string? action) (keyword (str/lower-case action))
    :else nil))

(defn- expand-home [path]
  (let [path (if (instance? java.io.File path) (.getPath path) (str path))
        home (System/getProperty "user.home")]
    (cond
      (= path "~") home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else path)))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file (expand-home path))))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- resolve-allowed-path! [roots path]
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

(defn- sensitive-action? [input]
  (contains? #{:write :create :replace :delete :mkdir} (:action input)))

(defn- ensure-permission! [context action]
  (let [permissions (:permissions context)]
    (case action
      :read (when-not (contains? permissions :filesystem-read)
              (throw (tools/permission-error #{:filesystem-read} permissions)))
      :list (when-not (contains? permissions :filesystem-read)
              (throw (tools/permission-error #{:filesystem-read} permissions)))
      (:write :create :replace :delete :mkdir) (when-not (contains? permissions :filesystem-write)
                                                 (throw (tools/permission-error #{:filesystem-write} permissions)))
      nil)))

(defn- validate-input [input]
    (let [action (normalize-action (:action input))]
    (when-not (allowed-actions action)
      (throw (tools/validation-error "action must be one of read/write/create/replace/list/delete/mkdir"
                                     {:action (:action input)})))
    (when (= :replace action)
      (when-not (string? (:old-string input))
        (throw (tools/validation-error "old-string must be a string" {:old-string (:old-string input)})))
      (when-not (string? (:new-string input))
        (throw (tools/validation-error "new-string must be a string" {:new-string (:new-string input)}))))
    (assoc input :action action)))

(defn create-fs-tool
  [opts]
  (let [config (merge {:roots ["."]
                       :max-read-bytes 1048576
                       :max-write-bytes 1048576}
                      opts)
        roots (mapv canonical-path (:roots config))]
    (tools/create-tool
     {:description
      (tools/create-tool-description
       :fs
       "Filesystem tool"
       :category :system
       :input-schema [:map {:closed true}
                      [:action [:or
                                [:enum :read :write :create :replace :list :delete :mkdir]
                                [:enum "read" "write" "create" "replace" "list" "delete" "mkdir"]]]
                      [:path :string]
                      [:content {:optional true} [:maybe :string]]
                      [:old-string {:optional true} [:maybe :string]]
                      [:new-string {:optional true} [:maybe :string]]
                      [:replace-all? {:optional true} [:maybe :boolean]]]
       :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
       :sensitive sensitive-action?
       :operation :act
       :approval-sensitive? false
       :action-key :action
       :read-only-actions #{:read :list}
       :parallel-safe-actions #{:read :list}
       :source :builtin)
      :validate-fn validate-input
      :health-fn (fn []
                   {:healthy true
                    :details {:roots roots
                              :max-read-bytes (:max-read-bytes config)
                              :max-write-bytes (:max-write-bytes config)}})
      :execute-fn
      (fn [input context]
        (let [action (:action input)
              path (resolve-allowed-path! roots (:path input))]
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
                     (let [content (or (:content input) "")
                           size (alength (.getBytes content "UTF-8"))]
                       (when (> size (:max-write-bytes config))
                         (throw (tools/tool-error :file-too-large "Content exceeds max-write-bytes"
                                                  {:path path
                                                   :size size
                                                   :max-write-bytes (:max-write-bytes config)})))
                       (spit path content))
                     {:path path
                      :written true})
            :create (do
                      (let [file (io/file path)
                            content (or (:content input) "")
                            size (alength (.getBytes content "UTF-8"))]
                        (when (.exists file)
                          (throw (tools/tool-error :already-exists "Path already exists" {:path path})))
                        (when (> size (:max-write-bytes config))
                          (throw (tools/tool-error :file-too-large "Content exceeds max-write-bytes"
                                                   {:path path
                                                    :size size
                                                    :max-write-bytes (:max-write-bytes config)})))
                        (spit file content))
                      {:path path
                       :created true})
            :replace (let [file (io/file path)
                           old (:old-string input)
                           new (:new-string input)
                           replace-all? (true? (:replace-all? input))]
                       (when-not (.isFile file)
                         (throw (tools/tool-error :not-found "File not found" {:path path})))
                       (let [content (slurp file)
                             matches (count (re-seq (java.util.regex.Pattern/compile
                                                     (java.util.regex.Pattern/quote old))
                                                    content))]
                         (cond
                           (zero? matches)
                           (throw (tools/tool-error :not-found "old-string not found" {:path path}))

                           (and (> matches 1) (not replace-all?))
                           (throw (tools/tool-error :ambiguous-replace
                                                    "old-string is not unique"
                                                    {:path path
                                                     :matches matches})))
                         (let [content* (if replace-all?
                                          (str/replace content old new)
                                          (str/replace-first content
                                                             (java.util.regex.Pattern/compile
                                                              (java.util.regex.Pattern/quote old))
                                                             (java.util.regex.Matcher/quoteReplacement new)))
                               size (alength (.getBytes content* "UTF-8"))]
                           (when (> size (:max-write-bytes config))
                             (throw (tools/tool-error :file-too-large "Content exceeds max-write-bytes"
                                                      {:path path
                                                       :size size
                                                       :max-write-bytes (:max-write-bytes config)})))
                           (spit file content*)
                           {:path path
                            :replaced true
                            :matches matches})))
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
