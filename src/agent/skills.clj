(ns agent.skills
  "Filesystem-backed skill discovery. Reads SKILL.md files from configured
   roots, extracts metadata/frontmatter, and returns paged summaries for API,
   UI, and prompt construction."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.nio.charset StandardCharsets)
   (java.nio.file Files StandardCopyOption)
   (java.security MessageDigest)))

(def default-page-size 50)
(def max-page-size 200)

(defn- existing-dir [path]
  (let [file (io/file path)]
    (when (.isDirectory file)
      file)))

(defn- parse-frontmatter* [content]
  (let [lines (str/split-lines content)]
    (if (= "---" (first lines))
      (let [[frontmatter-lines rest-lines] (split-with #(not= "---" %) (rest lines))]
        (if (seq rest-lines)
          [(loop [remaining frontmatter-lines
                  meta {}]
             (if-let [line (first remaining)]
               (if (str/blank? line)
                 (recur (rest remaining) meta)
                 (let [[k v] (str/split line #":" 2)
                       value (some-> v str/trim)]
                   (if (and k (#{">" ">-" "|" "|-"} value))
                     (let [[block tail] (split-with #(or (str/blank? %)
                                                        (re-find #"^\s" %))
                                                    (rest remaining))
                           block-lines (map str/trim block)
                           block-value (if (str/starts-with? value ">")
                                         (str/join " " block-lines)
                                         (str/join "\n" block-lines))]
                       (recur tail (assoc meta (keyword (str/trim k)) block-value)))
                     (recur (rest remaining)
                            (if (and k v)
                              (assoc meta (keyword (str/trim k)) value)
                              meta)))))
               meta))
           (str/trim (str/join "\n" (rest rest-lines)))]
          [nil content]))
      [nil content])))

(defn- first-markdown-blurb [content]
  (loop [lines (str/split-lines (or content ""))
         in-fence? false]
    (when-let [line (first lines)]
      (let [trimmed (str/trim line)]
        (cond
          (str/starts-with? trimmed "```")
          (recur (rest lines) (not in-fence?))

          (or in-fence?
              (str/blank? trimmed)
              (= "---" trimmed)
              (str/starts-with? trimmed "#"))
          (recur (rest lines) in-fence?)

          (> (count trimmed) 160)
          (str (subs trimmed 0 157) "...")

          :else trimmed)))))

(defn slash-command-name? [value]
  (boolean (and (string? value)
                (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]*" value))))

(defn- load-skill-file [skill-dir source]
  (let [skill-file (io/file skill-dir "SKILL.md")]
    (when (.isFile skill-file)
      (let [content (slurp skill-file)
            [frontmatter body] (parse-frontmatter* content)
            frontmatter (or frontmatter {})
            name (or (:name frontmatter) (.getName skill-dir))
            description (or (:description frontmatter)
                            (first-markdown-blurb body))]
        (when (and (slash-command-name? name)
                   (not (str/blank? description))
                   (not (str/blank? body)))
          {:name name
           :description description
           :body body
           :path (.getAbsolutePath skill-file)
           :base-dir (.getAbsolutePath skill-dir)
           :source source})))))

(defn load-skills-from-dir
  [dir source]
  (if-let [root (existing-dir dir)]
    (let [direct (load-skill-file root source)
          children (->> (.listFiles root)
                        (filter #(.isDirectory %))
                        (map #(load-skill-file % source))
                        (remove nil?))]
      (->> (cond-> children direct (conj direct))
           (sort-by :name)
           vec))
    []))

(defn- dir-fingerprint
  "Cheap staleness stamp for one skills dir: the set of [SKILL.md path,
  lastModified] pairs found by a shallow listing. Covers existence (new or
  deleted skill dirs change the set) and edits (mtime changes); never reads
  file contents."
  [dir]
  (if-let [root (existing-dir dir)]
    (let [root-skill (io/file root "SKILL.md")]
      (->> (cond-> (vec (.listFiles root))
             (.isFile root-skill) (conj root))
           (filter #(.isDirectory %))
           (keep (fn [skill-dir]
                   (let [skill-file (io/file skill-dir "SKILL.md")]
                     (when (.isFile skill-file)
                       [(.getAbsolutePath skill-file) (.lastModified skill-file)]))))
           set))
    #{}))

(defn- registry-fingerprint [registry]
  (mapv dir-fingerprint (:dirs registry)))

(defn create-registry
  [{:keys [dirs bundle-dirs]
    :or {dirs ["skills"]}}]
  {:dirs (vec (concat dirs bundle-dirs))
   ;; Registry-scoped scan cache: {:fingerprint <registry-fingerprint>
   ;;                              :skills <list-skills result>}.
   :cache (atom nil)})

(declare list-skills skill-map)

(defn content-revision [content]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str content) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn validate-proposed-skill! [content]
  (let [[frontmatter body] (parse-frontmatter* content)
        name (:name frontmatter)
        description (:description frontmatter)]
    (when-not (and (slash-command-name? name)
                   (not (str/blank? description))
                   (not (str/blank? body)))
      (throw (ex-info "Invalid proposed SKILL.md"
                      {:type :invalid-skill-proposal
                       :name name
                       :description-present? (not (str/blank? description))})))
    {:name name :description description :body body}))

(defn install-proposed-skill!
  "Validate and atomically activate one reviewed SKILL.md draft. Never overwrites."
  [registry content]
  (let [{:keys [name]} (validate-proposed-skill! content)
        root (some-> (:dirs registry) last io/file)]
    (when-not root
      (throw (ex-info "Invalid proposed SKILL.md"
                      {:type :invalid-skill-proposal
                       :name name
                       :description-present? true})))
    (when (some #(= name (:name %)) (list-skills registry))
      (throw (ex-info "Skill already exists; propose an update instead"
                      {:type :skill-exists :name name})))
    (let [dir (io/file root name)
          file (io/file dir "SKILL.md")]
      (when (.exists file)
        (throw (ex-info "Skill target already exists"
                        {:type :skill-exists :name name :path (.getAbsolutePath file)})))
      (.mkdirs dir)
      (spit file content)
      (if-let [loaded (load-skill-file dir :filesystem)]
        (do
          (some-> (:cache registry) (reset! nil))
          loaded)
        (do
          (io/delete-file file true)
          (throw (ex-info "Proposed skill failed registry validation"
                          {:type :invalid-skill-proposal :name name})))))))

(defn update-proposed-skill!
  "Atomically replace one existing skill after explicit proposal approval."
  [registry skill-name expected-revision content]
  (let [{proposed-name :name} (validate-proposed-skill! content)
        skill (get (skill-map registry) skill-name)]
    (when-not skill
      (throw (ex-info "Skill not found" {:type :not-found :skill-name skill-name})))
    (when-not (= skill-name proposed-name)
      (throw (ex-info "Skill update cannot rename a skill"
                      {:type :skill-rename-not-supported
                       :skill-name skill-name
                       :proposed-name proposed-name})))
    (let [path (io/file (:path skill))
          before (slurp path)
          actual-revision (content-revision before)]
      (when-not (= expected-revision actual-revision)
        (throw (ex-info "Skill changed after proposal creation"
                        {:type :stale-skill-revision
                         :skill-name skill-name
                         :expected-revision expected-revision
                         :actual-revision actual-revision})))
      (let [temp (Files/createTempFile (.toPath (.getParentFile path))
                                       ".skill-update-"
                                       ".md"
                                       (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (spit (.toFile temp) content)
          (Files/move temp
                      (.toPath path)
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/ATOMIC_MOVE
                                   StandardCopyOption/REPLACE_EXISTING]))
          (some-> (:cache registry) (reset! nil))
          (let [updated (get (skill-map registry) skill-name)]
            (when-not updated
              (throw (ex-info "Updated skill failed registry validation"
                              {:type :invalid-skill-proposal :skill-name skill-name})))
            updated)
          (catch Exception e
            (when (.exists path)
              (spit path before)
              (some-> (:cache registry) (reset! nil)))
            (throw e))
          (finally
            (Files/deleteIfExists temp)))))))

(defn uninstall-proposed-skill! [registry path]
  (let [file (io/file path)
        dir (.getParentFile file)]
    (when (.isFile file) (io/delete-file file true))
    (when (and dir (.isDirectory dir) (empty? (seq (.listFiles dir))))
      (io/delete-file dir true))
    (some-> (:cache registry) (reset! nil))))

(defn- scan-skills [registry]
  (->> (:dirs registry)
       (mapcat #(load-skills-from-dir % :filesystem))
       (sort-by :name)
       vec))

(defn list-skills
  [registry]
  (if-let [cache (:cache registry)]
    (let [fingerprint (registry-fingerprint registry)
          cached @cache]
      (if (and cached (= fingerprint (:fingerprint cached)))
        (:skills cached)
        (let [skills (scan-skills registry)]
          ;; Concurrent rescans may race; the single atomic reset! keeps the
          ;; cache consistent (last writer wins) without locking.
          (reset! cache {:fingerprint fingerprint :skills skills})
          skills)))
    (scan-skills registry)))

(defn- dedupe-by-name [skills]
  (->> skills
       (reduce (fn [{:keys [seen out] :as acc} skill]
                 (let [name (:name skill)]
                   (if (contains? seen name)
                     acc
                     {:seen (conj seen name)
                      :out (conj out skill)})))
               {:seen #{} :out []})
       :out))

(defn skill-catalog
  [registry]
  (->> (list-skills registry)
       dedupe-by-name
       (mapv #(select-keys % [:name :description :path :base-dir :source]))))

(defn skill-map
  [registry]
  (into {}
        (map (juxt :name identity))
        (dedupe-by-name (list-skills registry))))

(defn filter-catalog
  [catalog prefix]
  (let [prefix* (str/lower-case (str/trim (or prefix "")))]
    (if (str/blank? prefix*)
      (vec catalog)
      (filterv #(str/starts-with? (str/lower-case (:name %)) prefix*) catalog))))

(defn paginate
  [items {:keys [page page-size]}]
  (let [page* (max 1 (long (or page 1)))
        page-size* (min max-page-size
                        (max 1 (long (or page-size default-page-size))))
        total (count items)
        start (* (dec page*) page-size*)
        page-items (if (>= start total)
                     []
                     (subvec (vec items) start (min total (+ start page-size*))))]
    {:items page-items
     :total total
     :has-more (< (+ start page-size*) total)
     :page page*
     :page-size page-size*}))

(defn slash-commands-page
  [registry {:keys [prefix page page-size]}]
  (let [filtered (filter-catalog (skill-catalog registry) prefix)]
    (paginate filtered {:page page :page-size page-size})))

(def ^:private slash-token-re
  #"(^|[\t ])\/([A-Za-z0-9][A-Za-z0-9_-]*)")

(def ^:private skill-link-re
  #"\[\/([A-Za-z0-9][A-Za-z0-9_-]*)\]\((?:iris|coddy)-skill:([A-Za-z0-9][A-Za-z0-9_-]*)\)")

(defn parse-invoked-skill-names
  [text]
  (let [seen (atom #{})
        add! (fn [acc name]
               (if (or (str/blank? name) (contains? @seen name))
                 acc
                 (do (swap! seen conj name)
                     (conj acc name))))]
    (loop [lines (str/split-lines (or text ""))
           in-fence? false
           acc []]
      (if-let [line (first lines)]
        (let [trimmed-leading (str/triml line)
              fence? (str/starts-with? (str/trim trimmed-leading) "```")]
          (cond
            fence?
            (recur (rest lines) (not in-fence?) acc)

            (or in-fence? (str/starts-with? trimmed-leading ">"))
            (recur (rest lines) in-fence? acc)

            :else
            (let [acc* (reduce (fn [acc [_ label href]]
                                 (if (= label href) (add! acc label) acc))
                               acc
                               (re-seq skill-link-re line))
                  scratch (str/replace line skill-link-re " ")
                  acc** (reduce (fn [acc [_ _ name]]
                                  (add! acc name))
                                acc*
                                (re-seq slash-token-re scratch))]
              (recur (rest lines) in-fence? acc**))))
        acc))))

(defn invoked-skills
  [registry text]
  (let [by-name (skill-map registry)]
    (vec (keep by-name (parse-invoked-skill-names text)))))

(defn invoked-skills-section
  [registry text]
  (let [skills (invoked-skills registry text)]
    (when (seq skills)
      (str "## User-invoked slash skill instructions\n\n"
           (str/join "\n\n"
                     (map (fn [{:keys [name body]}]
                            (str "### /" name "\n\n" (str/trim body)))
                          skills))))))

(defn registry-health
  [registry]
  (let [skills (list-skills registry)]
    {:healthy true
     :count (count skills)
     :dirs (vec (:dirs registry))}))
