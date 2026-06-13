(ns agent.memory.scratchpad
  "Fixed-scope scratchpad files for working memory."
  (:require
   [agent.security :as security]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- first-vault-root [memory-service]
  (or (first (:vault-roots memory-service))
      (throw (ex-info "Vault memory is not configured"
                      {:type :vault-memory-disabled}))))

(defn- safe-id [value]
  (let [value* (-> (str (or value ""))
                   (str/replace #"[^A-Za-z0-9._-]+" "_")
                   (str/replace #"(^_+|_+$)" ""))]
    (when (str/blank? value*)
      (throw (ex-info "scratchpad session id is required"
                      {:type :invalid-scratchpad-scope})))
    value*))

(defn normalize-scope
  [scope]
  (let [type* (name (or (:type scope) :global))]
    (case type*
      "global" {:type "global"}
      "session" {:type "session" :id (safe-id (:id scope))}
      (throw (ex-info "scratchpad scope must be global or session"
                      {:type :invalid-scratchpad-scope
                       :scope scope})))))

(defn path-for
  [memory-service scope]
  (let [root (first-vault-root memory-service)
        scope* (normalize-scope scope)]
    (.getCanonicalPath
     (case (:type scope*)
       "global" (io/file root "scratchpad" "global.md")
       "session" (io/file root "scratchpad" "sessions" (str (:id scope*) ".md"))))))

(defn- read-content [path]
  (let [file (io/file path)]
    (if (.isFile file)
      (slurp file)
      "")))

(defn revision [content]
  (security/sha256-hex (or content "")))

(defn read-scratchpad
  [memory-service scope]
  (let [scope* (normalize-scope scope)
        path (path-for memory-service scope*)
        content (read-content path)]
    {:scope scope*
     :path path
     :content content
     :revision (revision content)}))

(defn- occurrence-count [haystack needle]
  (if (= "" (or needle ""))
    (if (= "" (or haystack "")) 1 2)
    (loop [idx 0
           n 0]
      (if-let [found (str/index-of haystack needle idx)]
        (recur (+ found (count needle)) (inc n))
        n))))

(defn- replace-once [haystack needle replacement]
  (let [idx (str/index-of haystack needle)]
    (str (subs haystack 0 idx)
         (or replacement "")
         (subs haystack (+ idx (count needle))))))

(defn validate-replace
  [{:keys [content revision]} old-text expected-revision]
  (when-not (= revision expected-revision)
    (throw (ex-info "scratchpad revision is stale; reread before replacing"
                    {:type :stale-scratchpad-revision
                     :expected expected-revision
                     :actual revision})))
  (let [matches (occurrence-count content old-text)]
    (cond
      (zero? matches)
      (throw (ex-info "scratchpad old_text not found"
                      {:type :scratchpad-no-match}))

      (> matches 1)
      (throw (ex-info "scratchpad old_text matched multiple locations"
                      {:type :scratchpad-ambiguous-match
                       :matches matches}))

      :else true)))

(defn replace-scratchpad!
  [memory-service scope old-text new-text expected-revision]
  (let [{:keys [path content] :as current} (read-scratchpad memory-service scope)]
    (validate-replace current old-text expected-revision)
    (let [content* (replace-once content old-text new-text)
          file (io/file path)]
      (when-let [parent (.getParentFile file)]
        (.mkdirs parent))
      (spit file content*)
      (assoc (read-scratchpad memory-service scope)
             :replaced true
             :previous-revision (:revision current)))))

(defn- line-snippets [query content]
  (let [query* (str/lower-case (str/trim (or query "")))]
    (when-not (str/blank? query*)
      (->> (str/split-lines (or content ""))
           (keep-indexed (fn [idx line]
                           (when (str/includes? (str/lower-case line) query*)
                             {:line (inc idx)
                              :text line})))
           vec))))

(defn search-scratchpad
  [memory-service scope query]
  (let [{:keys [content] :as scratchpad} (read-scratchpad memory-service scope)]
    (assoc scratchpad :query query :snippets (or (line-snippets query content) []))))

(defn promoted-content
  [content old-text]
  (replace-once content old-text ""))
