(ns agent.memory.vault.format
  "Markdown note parsing, serialization and proposed content transformations."
  (:require
   [agent.security :as security]
   [clojure.java.io :as io]
   [clojure.string :as str]))

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

(defn ^:no-doc note-id [{:keys [title description body source-request-id]}]
  (str "mem_" (subs (security/sha256-hex
                     (str title "\n" description "\n" body "\n" source-request-id))
                    0 24)))

(defn ^:no-doc note-path [root id title]
  (.getCanonicalPath
   (io/file root "inbox" (str id "-" (slug title) ".md"))))

(defn ^:no-doc note-markdown
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

(defn ^:no-doc audit-issue
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

(defn ^:no-doc update-iris-content [content changes]
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
