(ns agent.tools.common.fs
  "Filesystem tool with bounded roots."
  (:require
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.nio.charset StandardCharsets]
   [java.nio.file Files LinkOption OpenOption StandardOpenOption]
   [java.nio.file.attribute BasicFileAttributes]))

(defn- expand-home [path]
  (let [path (if (instance? java.io.File path) (.getPath path) (str path))
        home (System/getProperty "user.home")]
    (cond
      (= path "~") home
      (str/starts-with? path "~/") (str home (subs path 1))
      :else path)))

(defn- canonical-path [path]
  (.getCanonicalPath (io/file (expand-home path))))

(defn- nio-path [path]
  (-> (io/file (expand-home path))
      .getAbsoluteFile
      .toPath
      .normalize))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- resolve-allowed-path! [roots path]
  (when-not (and (string? path) (not (str/blank? path)))
    (throw (tools/validation-error "path must be a non-blank string" {:path path})))
  (let [candidate (io/file (expand-home path))
        canonical (canonical-path candidate)]
    (when-not (within-root? roots canonical)
      (throw (tools/tool-error :path-not-allowed
                               "Path is outside allowed roots"
                                {:path canonical
                                 :roots roots})))
    {:roots roots
     :path canonical
     :nio-path (nio-path path)}))

(defn- symlink-segment [roots path]
  (loop [current path]
    (cond
      (nil? current) nil
      (and (Files/isSymbolicLink current)
           (within-root? roots (.toFile current))) current
      :else (recur (.getParent current)))))

(defn- ensure-no-symlink-segments! [{:keys [roots path nio-path]}]
  (when-let [segment (symlink-segment roots nio-path)]
    (throw (tools/tool-error :path-not-allowed
                             "Path must not contain symlink segments"
                             {:path path
                              :segment (str segment)}))))

(defn- nofollow-link-options []
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- nofollow-open-options [& opts]
  (into-array OpenOption (conj (vec opts) LinkOption/NOFOLLOW_LINKS)))

(defn- regular-file-attrs! [{:keys [path nio-path] :as path-info}]
  (ensure-no-symlink-segments! path-info)
  (let [attrs (Files/readAttributes nio-path BasicFileAttributes (nofollow-link-options))]
    (when-not (.isRegularFile attrs)
      (throw (tools/tool-error :not-found "File not found" {:path path})))
    attrs))

(defn- read-file-content! [path-info]
  (with-open [in (Files/newInputStream (:nio-path path-info)
                                       (nofollow-open-options))]
    (slurp in)))

(defn- write-file-content! [path-info content create-new?]
  (ensure-no-symlink-segments! path-info)
  (Files/write (:nio-path path-info)
               (.getBytes content StandardCharsets/UTF_8)
               (if create-new?
                 (nofollow-open-options StandardOpenOption/CREATE_NEW
                                        StandardOpenOption/WRITE)
                 (nofollow-open-options StandardOpenOption/CREATE
                                        StandardOpenOption/TRUNCATE_EXISTING
                                        StandardOpenOption/WRITE))))

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

(defn create-fs-tools
  [opts]
  (let [config (merge {:roots ["."]
                       :max-read-bytes 1048576
                       :max-write-bytes 1048576}
                      opts)
        roots (mapv canonical-path (:roots config))
        health (fn []
                 {:healthy true
                  :details {:roots roots
                            :max-read-bytes (:max-read-bytes config)
                            :max-write-bytes (:max-write-bytes config)}})
        path-info (fn [input]
                    (resolve-allowed-path! roots (:path input)))
        write-size! (fn [path content]
                      (let [size (alength (.getBytes (or content "") "UTF-8"))]
                        (when (> size (:max-write-bytes config))
                          (throw (tools/tool-error :file-too-large "Content exceeds max-write-bytes"
                                                   {:path path
                                                    :size size
                                                    :max-write-bytes (:max-write-bytes config)})))))]
    [(tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_read
        "Read one file under configured filesystem roots."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :operation :read
        :parallel-safe? true
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :read)
         (let [{:keys [path] :as info} (path-info input)
               attrs (regular-file-attrs! info)
               size (.size attrs)]
           (when (> size (:max-read-bytes config))
             (throw (tools/tool-error :file-too-large "File exceeds max-read-bytes"
                                      {:path path
                                       :size size
                                       :max-read-bytes (:max-read-bytes config)})))
           {:path path
            :content (read-file-content! info)}))})
     (tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_write
        "Overwrite or create one file under configured filesystem roots."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]
                       [:content {:optional true} [:maybe :string]]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :sensitive true
        :operation :act
        :approval-sensitive? false
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :write)
         (let [{:keys [path] :as info} (path-info input)
               content (or (:content input) "")]
           (write-size! path content)
           (write-file-content! info content false)
           {:path path
            :written true}))})
     (tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_create
        "Create one new file under configured filesystem roots. Fails if path exists."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]
                       [:content {:optional true} [:maybe :string]]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :sensitive true
        :operation :act
        :approval-sensitive? false
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :create)
         (let [{:keys [path nio-path] :as info} (path-info input)
               content (or (:content input) "")]
           (when (Files/exists nio-path (nofollow-link-options))
             (throw (tools/tool-error :already-exists "Path already exists" {:path path})))
           (write-size! path content)
           (write-file-content! info content true)
           {:path path
            :created true}))})
     (tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_replace
        "Replace text in one file under configured filesystem roots."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]
                       [:old-string :string]
                       [:new-string :string]
                       [:replace-all? {:optional true} [:maybe :boolean]]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :sensitive true
        :operation :act
        :approval-sensitive? false
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :replace)
         (let [{:keys [path] :as info} (path-info input)
               old (:old-string input)
               new (:new-string input)
               replace-all? (true? (:replace-all? input))]
           (regular-file-attrs! info)
           (let [content (read-file-content! info)
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
                                                 (java.util.regex.Matcher/quoteReplacement new)))]
               (write-size! path content*)
               (write-file-content! info content* false)
               {:path path
                :replaced true
                :matches matches}))))})
     (tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_list
        "List a directory under configured filesystem roots."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :operation :read
        :parallel-safe? true
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :list)
         (let [{:keys [path nio-path] :as info} (path-info input)
               file (io/file path)]
           (ensure-no-symlink-segments! info)
           (when-not (Files/isDirectory nio-path (nofollow-link-options))
             (throw (tools/tool-error :not-directory "Path is not a directory" {:path path})))
           {:path path
            :entries (->> (.listFiles file)
                          (map (fn [entry]
                                 {:name (.getName entry)
                                  :path (.getCanonicalPath entry)
                                  :type (if (.isDirectory entry) "directory" "file")}))
                          (sort-by :name)
                          vec)}))})
     (tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_delete
        "Delete one file or empty directory under configured filesystem roots."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :sensitive true
        :operation :act
        :approval-sensitive? false
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :delete)
         (let [{:keys [path] :as info} (path-info input)
               file (io/file path)]
           (ensure-no-symlink-segments! info)
           (when-not (.exists file)
             (throw (tools/tool-error :not-found "Path not found" {:path path})))
           (io/delete-file file true)
           {:path path
            :deleted true}))})
     (tools/create-tool
      {:description
       (tools/create-tool-description
        :fs_mkdir
        "Create directories under configured filesystem roots."
        :category :system
        :input-schema [:map {:closed true}
                       [:path :string]]
        :prerequisites {:mutations [:read-same-path :list-parent-or-same-path]}
        :sensitive true
        :operation :act
        :approval-sensitive? false
        :source :builtin)
       :health-fn health
       :execute-fn
       (fn [input context]
         (ensure-permission! context :mkdir)
         (let [{:keys [path nio-path] :as info} (path-info input)]
           (ensure-no-symlink-segments! info)
           (Files/createDirectories nio-path
                                    (make-array java.nio.file.attribute.FileAttribute 0))
           {:path path
            :created true}))})]))
