(ns agent.skills
  "Filesystem-backed skill discovery for rewritten runtime."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- existing-dir [path]
  (let [file (io/file path)]
    (when (.isDirectory file)
      file)))

(defn- parse-frontmatter [content]
  (let [lines (str/split-lines content)]
    (when (= "---" (first lines))
      (loop [remaining (rest lines)
             meta {}]
        (let [line (first remaining)]
          (cond
            (nil? line) nil
            (= "---" line) meta
            (str/blank? line) (recur (rest remaining) meta)
            :else (let [[k v] (str/split line #":" 2)]
                    (recur (rest remaining)
                           (if (and k v)
                             (assoc meta (keyword (str/trim k)) (str/trim v))
                             meta)))))))))

(defn- load-skill-file [skill-dir source]
  (let [skill-file (io/file skill-dir "SKILL.md")]
    (when (.isFile skill-file)
      (let [content (slurp skill-file)
            frontmatter (or (parse-frontmatter content) {})
            name (or (:name frontmatter) (.getName skill-dir))
            description (:description frontmatter)]
        (when (and (not (str/blank? name))
                   (not (str/blank? description)))
          {:name name
           :description description
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

(defn registry-health
  [registry]
  (let [skills (list-skills registry)]
    {:healthy true
     :count (count skills)
     :dirs (vec (:dirs registry))}))
