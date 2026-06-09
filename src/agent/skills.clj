(ns agent.skills
  "Filesystem-backed skill discovery for rewritten runtime."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

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
          [(reduce (fn [meta line]
                     (if (str/blank? line)
                       meta
                       (let [[k v] (str/split line #":" 2)]
                         (if (and k v)
                           (assoc meta (keyword (str/trim k)) (str/trim v))
                           meta))))
                   {}
                   frontmatter-lines)
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
    (->> (.listFiles root)
         (filter #(.isDirectory %))
         (map #(load-skill-file % source))
         (remove nil?)
         (sort-by :name)
         vec)
    []))

(defn create-registry
  [{:keys [dirs]
    :or {dirs ["skills"]}}]
  {:dirs (vec dirs)})

(defn list-skills
  [registry]
  (->> (:dirs registry)
       (mapcat #(load-skills-from-dir % :filesystem))
       (sort-by :name)
       vec))

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
