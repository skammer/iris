(ns agent.memory.vault
  "Vault-backed OKF-ish markdown indexing. Notes remain source of truth; SQLite
   rows are rebuildable derived state."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [agent.security :as security]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- markdown-file? [^java.io.File file]
  (and (.isFile file)
       (str/ends-with? (str/lower-case (.getName file)) ".md")))

(defn- list-markdown-files [roots]
  (->> roots
       (map io/file)
       (filter #(.exists %))
       (mapcat file-seq)
       (filter markdown-file?)
       (sort-by #(.getCanonicalPath %))
       vec))

(defn- slug [value]
  (let [slug* (-> (or value "memory-note")
                  str/lower-case
                  (str/replace #"[^a-z0-9]+" "-")
                  (str/replace #"(^-|-$)" ""))]
    (if (str/blank? slug*) "memory-note" slug*)))

(defn- yaml-string [value]
  (str "\""
       (-> (str (or value ""))
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\"")
           (str/replace #"\r?\n" " "))
       "\""))

(defn- yaml-list [values]
  (str "["
       (str/join ", " (map yaml-string (or values [])))
       "]"))

(defn- yaml-value [value]
  (cond
    (nil? value) "\"\""
    (number? value) (str value)
    (sequential? value) (yaml-list value)
    :else (yaml-string value)))

(defn- quote-block [value]
  (->> (str/split-lines (or value ""))
       (map #(str "> " %))
       (str/join "\n")))

(defn- note-id [{:keys [title description body source-request-id]}]
  (str "mem_" (subs (security/sha256-hex
                     (str title "\n" description "\n" body "\n" source-request-id))
                    0 24)))

(defn- note-path [root id title]
  (.getCanonicalPath
   (io/file root "inbox" (str id "-" (slug title) ".md"))))

(defn- note-markdown
  [{:keys [id type title description body tags scope confidence origins timestamp evidence]}]
  (let [body* (str/trim (or body description ""))]
    (str "---\n"
         "id: " (yaml-value id) "\n"
         "type: " (yaml-value (or type "Reference")) "\n"
         "title: " (yaml-value title) "\n"
         "description: " (yaml-value description) "\n"
         "tags: " (yaml-value (vec (distinct (concat ["memory"] tags)))) "\n"
         "timestamp: " (yaml-value timestamp) "\n"
         "iris:\n"
         "  scope: " (yaml-value (or scope "session")) "\n"
         "  status: \"candidate\"\n"
         "  confidence: " (yaml-value (or confidence 0.7)) "\n"
         "  origins:\n"
         (apply str
                (for [{:keys [type session-id message-id request-id vault-path]} origins]
                  (str "  - type: " (yaml-value type) "\n"
                       (when session-id (str "    session_id: " (yaml-value session-id) "\n"))
                       (when message-id (str "    message_id: " (yaml-value message-id) "\n"))
                       (when vault-path (str "    vault_path: " (yaml-value vault-path) "\n"))
                       (when request-id (str "    request_id: " (yaml-value request-id) "\n")))))
         "---\n\n"
         "# " title "\n\n"
         body* "\n\n"
         "## Evidence\n\n"
         "### User\n\n"
         (quote-block (:user evidence)) "\n\n"
         "### Assistant\n\n"
         (quote-block (:assistant evidence)) "\n")))

(defn- unquote-string [value]
  (let [value* (str/trim (or value ""))]
    (if (and (>= (count value*) 2)
             (or (and (str/starts-with? value* "\"")
                      (str/ends-with? value* "\""))
                 (and (str/starts-with? value* "'")
                      (str/ends-with? value* "'"))))
      (subs value* 1 (dec (count value*)))
      value*)))

(defn- scalar [value]
  (let [value* (unquote-string value)]
    (cond
      (str/blank? value*) nil
      (re-matches #"\[[^\]]*\]" value*)
      (->> (subs value* 1 (dec (count value*)))
           (#(str/split % #","))
           (mapv unquote-string)
           (remove str/blank?)
           vec)

      (#{"true" "false"} (str/lower-case value*))
      (= "true" (str/lower-case value*))

      (re-matches #"-?\d+(\.\d+)?" value*)
      (Double/parseDouble value*)

      :else value*)))

(defn- parse-kv [line]
  (when-let [[_ k v] (re-matches #"^\s*([A-Za-z0-9_.-]+):\s*(.*)$" line)]
    [(keyword k) (scalar v) (str/blank? v)]))

(defn- add-origin-field [m k v]
  (update-in m [:iris :origins]
             (fn [origins]
               (let [origins* (vec (or origins []))
                     idx (dec (count origins*))]
                 (if (neg? idx)
                   origins*
                   (assoc-in origins* [idx k] v))))))

(defn- parse-frontmatter-map [lines]
  (loop [remaining lines
         result {}
         context nil]
    (if-let [line (first remaining)]
      (cond
        (str/blank? line)
        (recur (rest remaining) result context)

        (str/starts-with? line "    ")
        (let [[k v] (parse-kv line)]
          (recur (rest remaining)
                 (if (and (= context [:iris :origins]) k)
                   (add-origin-field result k v)
                   result)
                 context))

        (re-matches #"^\s*-\s+(.+)$" line)
        (let [[_ item] (re-matches #"^\s*-\s+(.+)$" line)
              [k v] (parse-kv item)]
          (recur (rest remaining)
                 (if (= context [:iris :origins])
                   (update-in result [:iris :origins]
                              (fnil conj [])
                              (cond-> {}
                                k (assoc k v)))
                   result)
                 context))

        (str/starts-with? line "  ")
        (let [[k v blank?] (parse-kv line)]
          (if (and (= context [:iris]) k)
            (recur (rest remaining)
                   (assoc-in result [:iris k] (if blank? [] v))
                   (if blank? [:iris k] context))
            (recur (rest remaining) result context)))

        :else
        (let [[k v blank?] (parse-kv line)]
          (recur (rest remaining)
                 (cond-> result
                   k (assoc k (if blank? {} v)))
                 (when blank? [k]))))
      result)))

(defn parse-note-content [content]
  (let [lines (str/split-lines (or content ""))]
    (if (= "---" (first lines))
      (let [[frontmatter-lines rest-lines] (split-with #(not= "---" %) (rest lines))
            body-lines (if (= "---" (first rest-lines))
                         (rest rest-lines)
                         rest-lines)]
        {:frontmatter (parse-frontmatter-map frontmatter-lines)
         :body (str/join "\n" body-lines)})
      {:frontmatter {}
       :body (or content "")})))

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

(defn- note->index [file]
  (let [content (slurp file)
        path (.getCanonicalPath file)
        scratchpad (scratchpad-info path)
        parsed (parse-note-content content)
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
        chunks (->> (chunks-by-heading body)
                    (remove #(str/blank? (:text %)))
                    (mapv (fn [{:keys [heading text]}]
                            (let [text* (str/trim (str metadata-text "\n" text))
                                  content-hash (security/sha256-hex text*)]
                              {:chunk-id (str "vault_chunk_" (subs (security/sha256-hex (str path "\n" content-hash)) 0 32))
                               :heading heading
                               :block-id (block-id text)
                               :content-hash content-hash
                               :text text*}))))]
    {:path path
     :id (or (some-> (:id frontmatter) str)
             (:id scratchpad))
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
     :body-hash (security/sha256-hex body)
     :updated-at (str (java.time.Instant/ofEpochMilli (.lastModified file)))
     :chunks chunks}))

(defn reindex! [memory-service]
  (let [files (list-markdown-files (:vault-roots memory-service))
        notes (mapv note->index files)
        result (sqlite/replace-vault-index! (:store memory-service) notes)]
    (merge {:indexed-files (count files)
            :parse-errors []
            :duplicate-ids []}
           result)))

(defn- top-level-frontmatter-line? [line]
  (and (not (str/blank? line))
       (not (str/starts-with? line " "))))

(defn- iris-field-line [field value]
  (str "  " (name field) ": " (yaml-value value)))

(defn- remove-iris-field [lines field]
  (let [pattern (re-pattern (str "^\\s+" (name field) ":.*$"))]
    (remove #(re-matches pattern %) lines)))

(defn- update-iris-block [block {:keys [scope status]}]
  (let [block* (-> block
                   (remove-iris-field :scope)
                   (remove-iris-field :status))
        fields (cond-> []
                 scope (conj (iris-field-line :scope scope))
                 status (conj (iris-field-line :status status)))]
    (vec (concat fields block*))))

(defn- upsert-iris-frontmatter [frontmatter-lines changes]
  (let [lines (vec frontmatter-lines)
        iris-idx (first (keep-indexed #(when (re-matches #"^iris:\s*$" %2) %1) lines))]
    (if (nil? iris-idx)
      (vec (concat lines
                   ["iris:"]
                   (update-iris-block [] changes)))
      (let [after-iris (subvec lines (inc iris-idx))
            block-count (count (take-while #(or (str/blank? %)
                                                (not (top-level-frontmatter-line? %)))
                                           after-iris))
            block-start (inc iris-idx)
            block-end (+ block-start block-count)]
        (vec (concat (subvec lines 0 block-start)
                     (update-iris-block (subvec lines block-start block-end) changes)
                     (subvec lines block-end)))))))

(defn- update-iris-content [content changes]
  (let [lines (vec (str/split-lines (or content "")))]
    (if (= "---" (first lines))
      (let [[frontmatter-lines rest-lines] (split-with #(not= "---" %) (rest lines))
            body-lines (if (= "---" (first rest-lines))
                         (rest rest-lines)
                         rest-lines)]
        (str "---\n"
             (str/join "\n" (upsert-iris-frontmatter frontmatter-lines changes))
             "\n---\n"
             (str/join "\n" body-lines)
             "\n"))
      (str "---\n"
           (str/join "\n" (upsert-iris-frontmatter [] changes))
           "\n---\n\n"
           (or content "")))))

(defn update-note-iris!
  [path changes]
  (let [file (io/file path)
        before (slurp file)
        after (update-iris-content before changes)]
    (spit file after)
    {:path (.getCanonicalPath file)
     :updated true
     :iris (select-keys changes [:scope :status])}))

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
    (let [id (or (:id note) (note-id note))
          timestamp (or (:timestamp note) (str (java.time.Instant/now)))
          note* (assoc note :id id :timestamp timestamp)
          path (note-path root id (:title note*))
          file (io/file path)
          content (note-markdown note*)]
      (when-let [parent (.getParentFile file)]
        (.mkdirs parent))
      (spit file content)
      {:id id
       :path path
       :status "candidate"
       :written true})))
