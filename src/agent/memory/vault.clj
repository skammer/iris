(ns agent.memory.vault
  "Vault-backed OKF-ish markdown indexing. Notes remain source of truth; SQLite
   rows are rebuildable derived state."
  (:require
   [agent.llm.core :as llm]
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
       (remove #(= "index.md" (str/lower-case (.getName %))))
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
  [{:keys [id type title description body tags scope status confidence origins timestamp evidence]}]
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
         "  status: " (yaml-value (or status "candidate")) "\n"
         "  confidence: " (yaml-value (or confidence 0.7)) "\n"
         "  origins:\n"
         (apply str
                (for [{:keys [type session-id project-id message-id event-id request-id vault-path
                              message-id-start message-id-end message-count
                              event-id-start event-id-end event-count]} origins]
                  (str "  - type: " (yaml-value type) "\n"
                       (when session-id (str "    session_id: " (yaml-value session-id) "\n"))
                       (when project-id (str "    project_id: " (yaml-value project-id) "\n"))
                       (when message-id (str "    message_id: " (yaml-value message-id) "\n"))
                       (when event-id (str "    event_id: " (yaml-value event-id) "\n"))
                       (when message-id-start (str "    message_id_start: " (yaml-value message-id-start) "\n"))
                       (when message-id-end (str "    message_id_end: " (yaml-value message-id-end) "\n"))
                       (when message-count (str "    message_count: " (yaml-value message-count) "\n"))
                       (when event-id-start (str "    event_id_start: " (yaml-value event-id-start) "\n"))
                       (when event-id-end (str "    event_id_end: " (yaml-value event-id-end) "\n"))
                       (when event-count (str "    event_count: " (yaml-value event-count) "\n"))
                       (when vault-path (str "    vault_path: " (yaml-value vault-path) "\n"))
                       (when request-id (str "    request_id: " (yaml-value request-id) "\n")))))
         "---\n\n"
         "# " title "\n\n"
         body* "\n\n"
         "## Evidence\n\n"
         "### Source\n\n"
         (quote-block (:user evidence))
         (when-not (str/blank? (:assistant evidence))
           (str "\n\n### Assistant\n\n" (quote-block (:assistant evidence))))
         "\n")))

(defn content-revision [content]
  (security/sha256-hex (or content "")))

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

(defn- audit-issue
  ([type path message] (audit-issue type path message nil))
  ([type path message data]
   (cond-> {:type type
            :path path
            :message message}
     data (merge data))))

(defn- add-origin-field [m k v]
  (update-in m [:iris :origins]
             (fn [origins]
               (let [origins* (vec (or origins []))
                     idx (dec (count origins*))]
                 (if (neg? idx)
                   origins*
                   (assoc-in origins* [idx k] v))))))

(defn- add-sequence-item [m context item]
  (let [[k v] (parse-kv item)
        value (if k {k v} (scalar item))]
    (cond
      (= context [:iris :origins])
      (update-in m [:iris :origins]
                 (fnil conj [])
                 (if k {k v} {:value value}))

      (= 1 (count context))
      (update m (first context) #(conj (vec (if (sequential? %) % [])) value))

      (and (= 2 (count context)) (= :iris (first context)))
      (update-in m context #(conj (vec (if (sequential? %) % [])) value))

      :else m)))

(defn- parse-frontmatter-map [lines path]
  (loop [remaining (map-indexed vector lines)
         result {}
         context nil
         errors []]
    (if-let [[idx line] (first remaining)]
      (cond
        (or (str/blank? line)
            (str/starts-with? (str/triml line) "#"))
        (recur (rest remaining) result context errors)

        (re-matches #"^\s*-\s+(.+)$" line)
        (let [[_ item] (re-matches #"^\s*-\s+(.+)$" line)]
          (recur (rest remaining)
                 (add-sequence-item result context item)
                 context
                 errors))

        (str/starts-with? line "    ")
        (let [[k v] (parse-kv line)]
          (recur (rest remaining)
                 (if (and (= context [:iris :origins]) k)
                   (add-origin-field result k v)
                   result)
                 context
                 (cond-> errors
                   (and (= context [:iris :origins]) (nil? k))
                   (conj (audit-issue :parse-error path "unsupported frontmatter origin line"
                                      {:line (+ idx 2) :text line})))))

        (str/starts-with? line "  ")
        (let [[k v blank?] (parse-kv line)]
          (if (and (= context [:iris]) k)
            (recur (rest remaining)
                   (assoc-in result [:iris k] (if blank? [] v))
                   (if blank? [:iris k] context)
                   errors)
            (recur (rest remaining)
                   result
                   context
                   (conj errors
                         (audit-issue :parse-error path "unsupported frontmatter line"
                                      {:line (+ idx 2) :text line})))))

        :else
        (let [[k v blank?] (parse-kv line)]
          (recur (rest remaining)
                 (cond-> result
                   k (assoc k (if blank? {} v)))
                 (when blank? [k])
                 (cond-> errors
                   (nil? k)
                   (conj (audit-issue :parse-error path "unsupported frontmatter line"
                                      {:line (+ idx 2) :text line}))))))
      {:frontmatter result
       :parse-errors errors})))

(defn parse-note-content
  ([content] (parse-note-content content nil))
  ([content path]
  (let [lines (str/split-lines (or content ""))]
    (if (= "---" (first lines))
      (let [[frontmatter-lines rest-lines] (split-with #(not= "---" %) (rest lines))
            closed? (= "---" (first rest-lines))]
        (if closed?
          (let [{:keys [frontmatter parse-errors]} (parse-frontmatter-map frontmatter-lines path)]
            {:frontmatter frontmatter
             :body (str/join "\n" (rest rest-lines))
             :parse-errors parse-errors})
          {:frontmatter {}
           :body (or content "")
           :parse-errors [(audit-issue :parse-error path "unterminated frontmatter"
                                       {:line 1})]}))
      {:frontmatter {}
       :body (or content "")
       :parse-errors []}))))

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

(defn- duplicate-id-report [notes]
  (->> notes
       (remove #(str/blank? (:id %)))
       (group-by :id)
       (keep (fn [[id matches]]
               (when (< 1 (count matches))
                 {:id id
                  :paths (mapv :path matches)})))
       vec))

(defn- okf-issues [notes]
  (->> notes
       (keep (fn [{:keys [path raw-type reserved?]}]
               (when (and (not reserved?) (str/blank? (or raw-type "")))
                 (audit-issue :missing-type path "non-reserved vault note is missing required OKF type"))))
       vec))

(defn- strip-fragment [value]
  (first (str/split (or value "") #"#" 2)))

(defn- external-link? [target]
  (let [target* (str/lower-case (str/trim (or target "")))]
    (or (str/blank? target*)
        (str/starts-with? target* "#")
        (re-find #"^[a-z][a-z0-9+.-]*:" target*))))

(defn- canonical-file-path [file]
  (.getCanonicalPath (io/file file)))

(defn- note-name-index [paths]
  (reduce (fn [acc path]
            (let [file (io/file path)
                  basename (str/replace (.getName file) #"\.md$" "")]
              (update acc (str/lower-case basename) (fnil conj []) path)))
          {}
          paths))

(defn- resolve-markdown-target [roots from-path target name-index]
  (let [target* (-> target strip-fragment str/trim (str/replace "\\" "/"))
        target** (str/replace target* #"\|.*$" "")]
    (cond
      (external-link? target**) true

      (and (not (str/includes? target** "/"))
           (not (str/ends-with? (str/lower-case target**) ".md")))
      (seq (get name-index (str/lower-case target**)))

      :else
      (let [target-paths (cond-> [target**]
                           (not (str/ends-with? (str/lower-case target**) ".md"))
                           (conj (str target** ".md")))
            parent (.getParentFile (io/file from-path))
            candidates (concat
                        (for [value target-paths]
                          (canonical-file-path (io/file parent value)))
                        (for [root roots
                              value target-paths]
                          (canonical-file-path (io/file root value))))]
        (some #(.isFile (io/file %)) candidates)))))

(defn- markdown-link-targets [body]
  (concat
   (map second (re-seq #"\[[^\]]+\]\(([^)]+)\)" (or body "")))
   (map second (re-seq #"\[\[([^\]\|#]+(?:#[^\]\|]+)?(?:\|[^\]]+)?)\]\]" (or body "")))))

(defn- broken-link-report [roots notes]
  (let [paths (mapv :path notes)
        name-index (note-name-index paths)]
    (->> notes
         (mapcat (fn [{:keys [path body]}]
                   (keep (fn [target]
                           (when-not (resolve-markdown-target roots path target name-index)
                             (audit-issue :broken-link path "broken vault note link"
                                          {:target target})))
                         (markdown-link-targets body))))
         vec)))

(defn- origin-vault-paths [note]
  (keep (fn [origin]
          (or (:vault_path origin)
              (:vault-path origin)))
        (:origins note)))

(defn- broken-origin-report [notes]
  (->> notes
       (mapcat (fn [{:keys [path] :as note}]
                 (keep (fn [origin-path]
                         (when-not (.isFile (io/file origin-path))
                           (audit-issue :broken-origin path "origin vault_path does not exist"
                                        {:origin-path origin-path})))
                       (origin-vault-paths note))))
       vec))

(defn- orphan-report [store current-paths]
  (let [current (set current-paths)
        old-notes (sqlite/list-vault-notes store {:limit 10000})
        old-chunks (sqlite/list-vault-chunks store {:limit 10000})]
    {:orphan-notes (->> old-notes
                        (remove #(contains? current (:path %)))
                        (mapv #(select-keys % [:path :id :title])))
     :orphan-chunks (->> old-chunks
                         (remove #(contains? current (:path %)))
                         (mapv #(select-keys % [:chunk_id :path :heading])))}))

(def ^:private default-embedding-surfaces #{:vault-notes :vault-chunks})
(def ^:private default-embedding-batch-size 16)

(defn- embedding-enabled? [memory-service]
  (if (true? (get-in memory-service [:config :embeddings :enabled?]))
    true
    false))

(defn- keyword-set [values fallback]
  (let [values* (if (seq values) values fallback)]
    (->> values*
         (map #(if (keyword? %) % (keyword (str %))))
         set)))

(defn- embedding-surfaces [memory-service]
  (keyword-set (get-in memory-service [:config :embeddings :surfaces])
               default-embedding-surfaces))

(defn- embedding-batch-size [memory-service]
  (let [value (get-in memory-service [:config :embeddings :batch-size])]
    (if (and (integer? value) (pos? value))
      value
      default-embedding-batch-size)))

(defn- approved-note? [note]
  (= "approved" (:iris-status note)))

(defn- approved-notes [notes]
  (filter approved-note? notes))

(defn- approved-chunks [notes]
  (mapcat (fn [note]
            (for [chunk (:chunks note)]
              (assoc chunk
                     :path (:path note)
                     :note-id (:id note)
                     :updated-at (:updated-at note))))
          (approved-notes notes)))

(defn- note-embedding-text [note]
  (str/join "\n"
            (remove str/blank?
                    [(:title note)
                     (:description note)
                     (str/join " " (:tags note))
                     (:body note)])))

(defn- embedding-vector? [value]
  (and (sequential? value)
       (every? number? value)))

(defn- normalize-embedding-response [input-count response]
  (cond
    (and (= 1 input-count) (embedding-vector? response))
    [(vec response)]

    (sequential? response)
    (mapv vec response)

    :else
    []))

(defn- embed-batches [provider texts opts batch-size]
  (->> texts
       (partition-all batch-size)
       (mapcat (fn [batch]
                 (normalize-embedding-response
                  (count batch)
                  (llm/embed provider (vec batch) opts))))
       vec))

(defn- build-vault-embeddings [memory-service notes]
  (let [surfaces (embedding-surfaces memory-service)
        provider (:embedding-provider memory-service)]
    (cond
      (not (embedding-enabled? memory-service))
      {:memory-embeddings []
       :vault-chunk-embeddings []
       :embedding-errors []}

      (nil? provider)
      {:memory-embeddings []
       :vault-chunk-embeddings []
       :embedding-errors [(audit-issue :embedding-error nil "embedding provider is not configured")]}

      :else
      (try
        (let [now (str (java.time.Instant/now))
              model (:embedding-model memory-service)
              opts (cond-> {}
                     model (assoc :model model))
              batch-size (embedding-batch-size memory-service)
              note-items (if (contains? surfaces :vault-notes)
                           (->> (approved-notes notes)
                                (remove #(str/blank? (note-embedding-text %)))
                                vec)
                           [])
              chunk-items (if (contains? surfaces :vault-chunks)
                            (vec (approved-chunks notes))
                            [])
              note-vectors (embed-batches provider
                                          (mapv note-embedding-text note-items)
                                          opts
                                          batch-size)
              chunk-vectors (embed-batches provider
                                           (mapv :text chunk-items)
                                           opts
                                           batch-size)]
          (when (not= (count note-items) (count note-vectors))
            (throw (ex-info "embedding provider returned unexpected note embedding count"
                            {:type :embedding-count-mismatch
                             :expected (count note-items)
                             :actual (count note-vectors)})))
          (when (not= (count chunk-items) (count chunk-vectors))
            (throw (ex-info "embedding provider returned unexpected chunk embedding count"
                            {:type :embedding-count-mismatch
                             :expected (count chunk-items)
                             :actual (count chunk-vectors)})))
          {:memory-embeddings
           (mapv (fn [note embedding]
                   {:id (str "vault_note:" (or (:id note) (:path note)))
                    :surface_id (:path note)
                    :content_hash (:body-hash note)
                    :model model
                    :embedding embedding
                    :updated_at now})
                 note-items
                 note-vectors)
           :vault-chunk-embeddings
           (mapv (fn [chunk embedding]
                   {:chunk_id (:chunk-id chunk)
                    :content_hash (:content-hash chunk)
                    :model model
                    :embedding embedding
                    :updated_at now})
                 chunk-items
                 chunk-vectors)
           :embedding-errors []})
        (catch Exception e
          {:memory-embeddings []
           :vault-chunk-embeddings []
           :embedding-errors [(audit-issue :embedding-error nil
                                           (or (.getMessage e) "embedding failed")
                                           (ex-data e))]})))))

(defn- stale-embedding-report [memory-service notes]
  (let [current-chunks (into {}
                             (map (juxt :chunk-id :content-hash))
                             (approved-chunks notes))
        current-notes (into {}
                            (map (juxt :path :body-hash))
                            (approved-notes notes))
        store (:store memory-service)]
    {:stale-embeddings
     (->> (sqlite/list-vault-chunk-embeddings store {:limit 10000})
          (keep (fn [{:keys [chunk-id content-hash]}]
                  (let [current-hash (get current-chunks chunk-id)]
                    (when (not= current-hash content-hash)
                      {:chunk-id chunk-id
                       :content-hash content-hash
                       :current-content-hash current-hash}))))
          vec)
     :stale-note-embeddings
     (->> (sqlite/list-memory-embeddings store {:surface "vault_note"
                                                :limit 10000})
          (keep (fn [{:keys [surface-id content-hash]}]
                  (let [current-hash (get current-notes surface-id)]
                    (when (not= current-hash content-hash)
                      {:surface-id surface-id
                       :content-hash content-hash
                       :current-content-hash current-hash}))))
          vec)}))

(defn- embedding-report [memory-service notes embedding-result]
  (if (embedding-enabled? memory-service)
    (let [surfaces (embedding-surfaces memory-service)
          desired-chunks (if (contains? surfaces :vault-chunks)
                           (vec (approved-chunks notes))
                           [])
          desired-notes (if (contains? surfaces :vault-notes)
                          (vec (approved-notes notes))
                          [])
          generated-chunk-ids (set (map :chunk_id (:vault-chunk-embeddings embedding-result)))
          generated-note-paths (set (map :surface_id (:memory-embeddings embedding-result)))
          stale (stale-embedding-report memory-service notes)]
      {:embedding-audit {:enabled true}
       :missing-embeddings (->> desired-chunks
                                (remove #(contains? generated-chunk-ids (:chunk-id %)))
                                (mapv #(select-keys % [:chunk-id :content-hash])))
       :missing-note-embeddings (->> desired-notes
                                     (remove #(contains? generated-note-paths (:path %)))
                                     (mapv #(select-keys % [:path :id :body-hash])))
       :stale-embeddings (:stale-embeddings stale)
       :stale-note-embeddings (:stale-note-embeddings stale)
       :embedding-errors (:embedding-errors embedding-result)})
    {:embedding-audit {:enabled false}
     :missing-embeddings []
     :missing-note-embeddings []
     :stale-embeddings []
     :stale-note-embeddings []
     :embedding-errors []}))

(defn- audit-report [memory-service notes paths embedding-result]
  (let [orphans (orphan-report (:store memory-service) paths)]
    (merge {:indexed-files (count notes)
            :parse-errors (vec (mapcat :parse-errors notes))
            :duplicate-ids (duplicate-id-report notes)
            :okf-issues (okf-issues notes)
            :broken-links (broken-link-report (:vault-roots memory-service) notes)
            :broken-origins (broken-origin-report notes)
            :orphan-notes (:orphan-notes orphans)
            :orphan-chunks (:orphan-chunks orphans)}
           (embedding-report memory-service notes embedding-result))))

(defn reindex! [memory-service]
  (let [files (list-markdown-files (:vault-roots memory-service))
        notes (mapv note->index files)
        paths (mapv :path notes)
        embedding-result (build-vault-embeddings memory-service notes)
        report (audit-report memory-service notes paths embedding-result)]
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
                :index-errors [(audit-issue :index-error nil
                                            (or (.getMessage e) "vault index update failed")
                                            (ex-data e))]
                :note-count (sqlite/count-vault-notes (:store memory-service))
                :chunk-count (sqlite/count-vault-chunks (:store memory-service))}
               report)))))

(defn- top-level-frontmatter-line? [line]
  (and (not (str/blank? line))
       (not (str/starts-with? line " "))))

(defn- iris-field-line [field value]
  (str "  " (name field) ": " (yaml-value value)))

(defn- remove-iris-field [lines field]
  (let [pattern (re-pattern (str "^\\s+" (name field) ":.*$"))]
    (remove #(re-matches pattern %) lines)))

(defn- remove-iris-origins [lines]
  (loop [remaining lines
         result []
         skipping? false]
    (if-let [line (first remaining)]
      (cond
        (re-matches #"^\s+origins:\s*$" line)
        (recur (rest remaining) result true)

        (and skipping? (re-matches #"^  [A-Za-z0-9_.-]+:.*$" line))
        (recur remaining result false)

        skipping?
        (recur (rest remaining) result true)

        :else
        (recur (rest remaining) (conj result line) false))
      result)))

(defn- origin-lines [origins]
  (when (seq origins)
    (vec
     (concat
      ["  origins:"]
      (mapcat (fn [origin]
                (let [session-id (or (:session-id origin) (:session_id origin))
                      project-id (or (:project-id origin) (:project_id origin))
                      message-id (or (:message-id origin) (:message_id origin))
                      event-id (or (:event-id origin) (:event_id origin))
                      request-id (or (:request-id origin) (:request_id origin))
                      vault-path (or (:vault-path origin) (:vault_path origin))
                      message-id-start (or (:message-id-start origin) (:message_id_start origin))
                      message-id-end (or (:message-id-end origin) (:message_id_end origin))
                      message-count (or (:message-count origin) (:message_count origin))
                      event-id-start (or (:event-id-start origin) (:event_id_start origin))
                      event-id-end (or (:event-id-end origin) (:event_id_end origin))
                      event-count (or (:event-count origin) (:event_count origin))]
                  (remove nil?
                          [(str "  - type: " (yaml-value (:type origin)))
                           (when session-id (str "    session_id: " (yaml-value session-id)))
                           (when project-id (str "    project_id: " (yaml-value project-id)))
                           (when message-id (str "    message_id: " (yaml-value message-id)))
                           (when event-id (str "    event_id: " (yaml-value event-id)))
                           (when message-id-start (str "    message_id_start: " (yaml-value message-id-start)))
                           (when message-id-end (str "    message_id_end: " (yaml-value message-id-end)))
                           (when message-count (str "    message_count: " (yaml-value message-count)))
                           (when event-id-start (str "    event_id_start: " (yaml-value event-id-start)))
                           (when event-id-end (str "    event_id_end: " (yaml-value event-id-end)))
                           (when event-count (str "    event_count: " (yaml-value event-count)))
                           (when request-id (str "    request_id: " (yaml-value request-id)))
                           (when vault-path (str "    vault_path: " (yaml-value vault-path)))])))
              origins)))))

(defn- update-iris-block [block {:keys [scope status origins]}]
  (let [without-fields (-> block
                           (remove-iris-field :scope)
                           (remove-iris-field :status))
        block* (remove nil? (if (some? origins)
                              (remove-iris-origins without-fields)
                              without-fields))
        fields (cond-> []
                 scope (conj (iris-field-line :scope scope))
                 status (conj (iris-field-line :status status)))]
    (vec (concat fields block* (origin-lines origins)))))

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

(defn- upsert-top-level-field [lines field value]
  (let [pattern (re-pattern (str "^" (name field) ":.*$"))
        replacement (str (name field) ": " (yaml-value value))
        found? (some #(re-matches pattern %) lines)]
    (if found?
      (mapv #(if (re-matches pattern %) replacement %) lines)
      (let [iris-idx (or (first (keep-indexed #(when (re-matches #"^iris:\s*$" %2) %1)
                                               lines))
                         (count lines))]
        (vec (concat (subvec (vec lines) 0 iris-idx)
                     [replacement]
                     (subvec (vec lines) iris-idx)))))))

(defn- update-top-level-frontmatter [lines changes]
  (reduce (fn [result field]
            (if (contains? changes field)
              (upsert-top-level-field result field (get changes field))
              result))
          (vec lines)
          [:type :title :description :tags]))

(defn- strip-leading-note-headings [body]
  (loop [lines (drop-while str/blank? (str/split-lines (or body "")))]
    (if (and (seq lines) (str/starts-with? (first lines) "# "))
      (recur (drop-while str/blank? (rest lines)))
      (str/trim (str/join "\n" lines)))))

(defn- replace-note-heading [body title]
  (let [body* (strip-leading-note-headings body)]
    (str "# " title
         (when-not (str/blank? body*)
           (str "\n\n" body*)))))

(defn- durable-body [body]
  (let [without-evidence (if-let [idx (str/index-of (or body "") "\n## Evidence\n")]
                           (subs body 0 idx)
                           (or body ""))
        body* (strip-leading-note-headings without-evidence)]
    body*))

(defn- update-evidence [evidence]
  (when (or (not (str/blank? (:user evidence)))
            (not (str/blank? (:assistant evidence))))
    (str "\n\n## Evidence\n\n"
         "### Source\n\n"
         (quote-block (:user evidence))
         (when-not (str/blank? (:assistant evidence))
           (str "\n\n### Assistant\n\n" (quote-block (:assistant evidence)))))))

(defn proposed-note-content
  "Build a full approved note revision from partial structured changes while
   preserving unknown frontmatter and prior evidence."
  [content changes evidence origins]
  (let [lines (vec (str/split-lines (or content "")))
        frontmatter? (= "---" (first lines))
        [frontmatter-lines rest-lines] (if frontmatter?
                                         (split-with #(not= "---" %) (rest lines))
                                         [[] lines])
        body-lines (if (and frontmatter? (= "---" (first rest-lines)))
                     (rest rest-lines)
                     rest-lines)
        parsed (parse-note-content content)
        current-title (or (get-in parsed [:frontmatter :title]) "Memory note")
        title (or (:title changes) current-title)
        current-body (str/join "\n" body-lines)
        body-base (if (contains? changes :body)
                    (str "# " title "\n\n" (str/trim (or (:body changes) "")))
                    (replace-note-heading
                     (if-let [idx (str/index-of current-body "\n## Evidence\n")]
                       (subs current-body 0 idx)
                       current-body)
                     title))
        body (str (str/trimr body-base) (or (update-evidence evidence) "") "\n")
        combined-origins (->> (concat (or (get-in parsed [:frontmatter :iris :origins]) [])
                                      (or origins []))
                              distinct
                              (take-last 8)
                              vec)
        frontmatter* (-> (update-top-level-frontmatter frontmatter-lines changes)
                         (upsert-iris-frontmatter {:scope (or (:scope changes)
                                                             (get-in parsed [:frontmatter :iris :scope])
                                                             "global")
                                                   :status "approved"
                                                   :origins combined-origins}))]
    (str "---\n"
         (str/join "\n" frontmatter*)
         "\n---\n\n"
         body)))

(defn note-change-values [content]
  (let [{:keys [frontmatter body]} (parse-note-content content)]
    {:type (:type frontmatter)
     :title (:title frontmatter)
     :description (:description frontmatter)
     :tags (vec (or (:tags frontmatter) []))
     :scope (get-in frontmatter [:iris :scope])
     :body (durable-body body)}))

(defn update-note-iris!
  [path changes]
  (let [file (io/file path)
        before (slurp file)
        after (update-iris-content before changes)]
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
