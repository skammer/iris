(ns agent.memory.vault.audit
  "Vault index integrity, links and orphan detection."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [agent.memory.vault.embeddings :as embeddings]
   [agent.memory.vault.format :as note-format]))

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
                 (note-format/audit-issue :missing-type path "non-reserved vault note is missing required OKF type"))))
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
                             (note-format/audit-issue :broken-link path "broken vault note link"
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
                           (note-format/audit-issue :broken-origin path "origin vault_path does not exist"
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

(defn ^:no-doc audit-report [memory-service notes paths embedding-result]
  (let [orphans (orphan-report (:store memory-service) paths)]
    (merge {:indexed-files (count notes)
            :parse-errors (vec (mapcat :parse-errors notes))
            :duplicate-ids (duplicate-id-report notes)
            :okf-issues (okf-issues notes)
            :broken-links (broken-link-report (:vault-roots memory-service) notes)
            :broken-origins (broken-origin-report notes)
            :orphan-notes (:orphan-notes orphans)
            :orphan-chunks (:orphan-chunks orphans)}
           (embeddings/embedding-report memory-service notes embedding-result))))
