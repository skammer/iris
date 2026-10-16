(ns agent.memory.vault
  "Vault-backed OKF-ish markdown indexing. Notes remain source of truth; SQLite
   rows are rebuildable derived state."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.security :as security]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [agent.memory.vault.audit :as audit]
   [agent.memory.vault.embeddings :as embeddings]
   [agent.memory.vault.format :as note-format]))

(def content-revision note-format/content-revision)
(def parse-note-content note-format/parse-note-content)
(def proposed-note-content note-format/proposed-note-content)
(def note-change-values note-format/note-change-values)

(defn- markdown-file? [^java.io.File file]
  (and (.isFile file)
       (str/ends-with? (str/lower-case (.getName file)) ".md")))

(defn- list-markdown-files [roots]
  (->> roots
       (map io/file)
       (filter #(.exists %))
       (mapcat file-seq)
       (filter markdown-file?)
       (remove #(= "index.md" (str/lower-case (.getName %))))
       (sort-by #(.getCanonicalPath %))
       vec))

(defn- block-id [text]
  (some-> (re-find #"(?m)\^([A-Za-z0-9_-]+)\s*$" text) second))

(defn- chunks-by-heading [body]
  (let [lines (str/split-lines (or body ""))]
    (loop [remaining lines
           current-heading nil
           current []
           chunks []]
      (if-let [line (first remaining)]
        (if-let [[_ heading] (re-matches #"^#{1,6}\s+(.+?)\s*$" line)]
          (recur (rest remaining)
                 heading
                 [line]
                 (cond-> chunks
                   (seq current) (conj {:heading current-heading
                                        :text (str/trim (str/join "\n" current))})))
          (recur (rest remaining)
                 current-heading
                 (conj current line)
                 chunks))
        (cond-> chunks
          (seq current) (conj {:heading current-heading
                               :text (str/trim (str/join "\n" current))}))))))

(defn- durable-index-body [body]
  (first (str/split (or body "") #"(?m)^## Evidence\s*$" 2)))

(defn- nonblank-title [frontmatter file]
  (or (some-> (:title frontmatter) str str/trim not-empty)
      (some-> (.getName file) (str/replace #"\.md$" "") not-empty)))

(defn- scratchpad-info [path]
  (let [path* (str/replace path "\\" "/")]
    (cond
      (str/ends-with? path* "/scratchpad/global.md")
      {:id "scratchpad_global"
       :scope "global"
       :title "Global Scratchpad"
       :tags ["scratchpad" "global"]
       :origins [{:type "scratchpad"}]}

      :else
      (when-let [[_ session-id] (re-find #"/scratchpad/sessions/([^/]+)\.md$" path*)]
        {:id (str "scratchpad_session_" session-id)
         :scope "session"
         :title (str "Session Scratchpad " session-id)
         :tags ["scratchpad" "session"]
         :origins [{:type "scratchpad"
                    :session_id session-id}]}))))

(defn- reserved-note? [path]
  (#{"index.md" "log.md"} (str/lower-case (.getName (io/file path)))))

(defn- note->index [file]
  (let [content (slurp file)
        path (.getCanonicalPath file)
        scratchpad (scratchpad-info path)
        parsed (parse-note-content content path)
        frontmatter (:frontmatter parsed)
        iris (:iris frontmatter)
        body (or (:body parsed) "")
        title (or (some-> (:title frontmatter) str str/trim not-empty)
                  (:title scratchpad)
                  (nonblank-title frontmatter file))
        description (some-> (:description frontmatter) str)
        tags (vec (or (:tags frontmatter) (:tags scratchpad) []))
        metadata-text (str/join "\n" (remove str/blank? [title description
                                                         (str/join " " tags)]))
        chunks (->> (chunks-by-heading (durable-index-body body))
                    (remove #(str/blank? (:text %)))
                    (map-indexed (fn [ordinal {:keys [heading text]}]
                            (let [text* (str/trim (str metadata-text "\n" text))
                                  content-hash (security/sha256-hex text*)]
                              {:chunk-id (str "vault_chunk_" (subs (security/sha256-hex
                                                                    (str path "\n" ordinal "\n" content-hash))
                                                                   0 32))
                               :heading heading
                               :block-id (block-id text)
                               :content-hash content-hash
                               :text text*})))
                    vec)]
    {:path path
     :id (or (some-> (:id frontmatter) str)
             (:id scratchpad))
     :raw-type (some-> (:type frontmatter) str)
     :type (or (some-> (:type frontmatter) str)
               (when scratchpad "Scratchpad")
               "Reference")
     :title title
     :description description
     :tags tags
     :timestamp (some-> (:timestamp frontmatter) str)
     :iris-scope (or (some-> (:scope iris) str)
                     (:scope scratchpad)
                     "global")
     :iris-status (or (some-> (:status iris) str)
                      (when scratchpad "approved")
                      "candidate")
     :iris-confidence (:confidence iris)
     :origins (vec (or (:origins iris) (:origins scratchpad) []))
     :frontmatter frontmatter
     :body body
     :body-hash (security/sha256-hex body)
     :revision (content-revision content)
     :updated-at (str (java.time.Instant/ofEpochMilli (.lastModified file)))
     :chunks chunks
     :parse-errors (vec (:parse-errors parsed))
     :reserved? (reserved-note? path)}))

(defn reindex! [memory-service]
  (let [files (list-markdown-files (:vault-roots memory-service))
        notes (mapv note->index files)
        paths (mapv :path notes)
        embedding-result (embeddings/build-vault-embeddings memory-service notes)
        report (audit/audit-report memory-service notes paths embedding-result)]
    (try
      (merge {:ok? true
              :used-last-successful-index? false}
             report
             (sqlite/replace-vault-index! (:store memory-service)
                                          notes
                                          (select-keys embedding-result
                                                       [:memory-embeddings
                                                        :vault-chunk-embeddings])))
      (catch Exception e
        (merge {:ok? false
                :used-last-successful-index? true
                :index-errors [(note-format/audit-issue :index-error nil
                                            (or (.getMessage e) "vault index update failed")
                                            (ex-data e))]
                :note-count (sqlite/count-vault-notes (:store memory-service))
                :chunk-count (sqlite/count-vault-chunks (:store memory-service))}
               report)))))

(defn update-note-iris!
  [path changes]
  (let [file (io/file path)
        before (slurp file)
        after (note-format/update-iris-content before changes)]
    (spit file after)
    {:path (.getCanonicalPath file)
     :updated true
     :iris (select-keys changes [:scope :status])}))

(defn replace-note-content!
  [path expected-revision content]
  (let [file (io/file path)
        before (slurp file)
        actual-revision (content-revision before)]
    (when-not (= expected-revision actual-revision)
      (throw (ex-info "Vault Note revision changed"
                      {:type :stale-vault-note-revision
                       :path (.getCanonicalPath file)
                       :expected-revision expected-revision
                       :actual-revision actual-revision})))
    (let [target (.toPath file)
          parent (.getParent target)
          temp (java.nio.file.Files/createTempFile
                parent
                ".iris-memory-"
                ".tmp"
                (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (java.nio.file.Files/writeString
         temp
         (or content "")
         java.nio.charset.StandardCharsets/UTF_8
         (into-array java.nio.file.OpenOption
                     [java.nio.file.StandardOpenOption/TRUNCATE_EXISTING
                      java.nio.file.StandardOpenOption/WRITE]))
        (try
          (java.nio.file.Files/move
           temp
           target
           (into-array java.nio.file.CopyOption
                       [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                        java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
          (catch java.nio.file.AtomicMoveNotSupportedException _
            (java.nio.file.Files/move
             temp
             target
             (into-array java.nio.file.CopyOption
                         [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
        {:path (.getCanonicalPath file)
         :updated true
         :previous-revision actual-revision
         :revision (content-revision content)}
        (finally
          (java.nio.file.Files/deleteIfExists temp))))))

(defn move-note!
  [source-path target-path]
  (let [source (io/file source-path)
        target (io/file target-path)]
    (when-not (.isFile source)
      (throw (ex-info "Vault note file not found"
                      {:type :not-found
                       :path (.getCanonicalPath source)})))
    (when (and (.exists target)
               (not= (.getCanonicalPath source) (.getCanonicalPath target)))
      (throw (ex-info "Vault note target already exists"
                      {:type :target-exists
                       :path (.getCanonicalPath target)})))
    (when-let [parent (.getParentFile target)]
      (.mkdirs parent))
    (java.nio.file.Files/move (.toPath source)
                              (.toPath target)
                              (into-array java.nio.file.CopyOption []))
    {:from (.getCanonicalPath source)
     :path (.getCanonicalPath target)
     :moved true}))

(defn write-candidate-note!
  [memory-service note]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [root (first (:vault-roots memory-service))]
    (when-not root
      (throw (ex-info "Vault memory is not configured" {:type :vault-memory-disabled})))
    (let [id (or (:id note) (note-format/note-id note))
          timestamp (or (:timestamp note) (str (java.time.Instant/now)))
          note* (assoc note :id id :timestamp timestamp)
          path (note-format/note-path root id (:title note*))
          file (io/file path)
          content (note-format/note-markdown note*)]
      (when-let [parent (.getParentFile file)]
        (.mkdirs parent))
      (spit file content)
      {:id id
       :path path
       :status "candidate"
       :written true})))
