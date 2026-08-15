(ns agent.skills-test
  (:require
   [agent.skills :as skills]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn temp-dir []
  (let [dir (java.nio.file.Files/createTempDirectory "iris-skills-" (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile dir)))

(deftest loads-skills-from-dir-test
  (let [root (temp-dir)
        skill-dir (io/file root "summarize")]
    (.mkdirs skill-dir)
    (spit (io/file skill-dir "SKILL.md")
          "---\nname: summarize\ndescription: Summarize local files\n---\n# Summarize\n")
    (let [registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
          loaded (skills/list-skills registry)]
      (is (= 1 (count loaded)))
      (is (= "summarize" (:name (first loaded))))
      (is (= "Summarize local files" (:description (first loaded)))))
    (io/delete-file (io/file skill-dir "SKILL.md") true)
    (.delete skill-dir)
    (.delete root)))

(deftest loads-folded-frontmatter-description-test
  (let [root (temp-dir)
        skill-dir (io/file root "searcharvester-fallback")]
    (.mkdirs skill-dir)
    (spit (io/file skill-dir "SKILL.md")
          (str "---\nname: searcharvester-fallback\ndescription: >\n"
               "  Fallback web search via Searcharvester.\n"
               "  Use when primary search fails.\n---\n# Search\n"))
    (let [skill (first (skills/load-skills-from-dir (.getAbsolutePath root) :test))]
      (is (= "Fallback web search via Searcharvester. Use when primary search fails."
             (:description skill))))
    (io/delete-file (io/file skill-dir "SKILL.md") true)
    (.delete skill-dir)
    (.delete root)))

(deftest slash-skill-parser-skips-fences-and-quotes-test
  (is (= ["review" "code"]
         (skills/parse-invoked-skill-names
          "/review\n```\n/debug\n```\n> /ask\nplease /code this"))))

(deftest invoked-skills-section-includes-body-test
  (let [root (temp-dir)
        skill-dir (io/file root "review")]
    (.mkdirs skill-dir)
    (spit (io/file skill-dir "SKILL.md")
          "---\nname: review\ndescription: Review code\n---\n# Review\n\nUse review checklist.")
    (let [registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
          section (skills/invoked-skills-section registry "please /review this")]
      (is (str/includes? section "### /review"))
      (is (str/includes? section "Use review checklist.")))
    (io/delete-file (io/file skill-dir "SKILL.md") true)
    (.delete skill-dir)
    (.delete root)))

(defn- spit-skill [root dir-name description]
  (let [skill-dir (io/file root dir-name)]
    (.mkdirs skill-dir)
    (spit (io/file skill-dir "SKILL.md")
          (str "---\nname: " dir-name "\ndescription: " description "\n---\n# " dir-name "\n"))
    skill-dir))

(defn- delete-skill-dir [skill-dir]
  (io/delete-file (io/file skill-dir "SKILL.md") true)
  (.delete skill-dir))

(deftest cache-picks-up-new-skill-dir-test
  (let [root (temp-dir)
        alpha (spit-skill root "alpha" "Alpha work")
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})]
    (is (= ["alpha"] (mapv :name (skills/skill-catalog registry))))
    (let [beta (spit-skill root "beta" "Beta work")]
      (is (= ["alpha" "beta"] (mapv :name (skills/skill-catalog registry))))
      (delete-skill-dir beta))
    (delete-skill-dir alpha)
    (.delete root)))

(deftest cache-picks-up-edited-skill-test
  (let [root (temp-dir)
        skill-dir (spit-skill root "alpha" "Old description")
        skill-file (io/file skill-dir "SKILL.md")
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})]
    (is (= "Old description" (:description (first (skills/skill-catalog registry)))))
    (let [stamp (.lastModified skill-file)]
      (spit-skill root "alpha" "New description")
      ;; Set lastModified explicitly so the stamp differs even when the
      ;; rewrite lands within filesystem mtime granularity.
      (is (.setLastModified skill-file (+ stamp 5000))))
    (is (= "New description" (:description (first (skills/skill-catalog registry)))))
    (delete-skill-dir skill-dir)
    (.delete root)))

(deftest cache-picks-up-deleted-skill-dir-test
  (let [root (temp-dir)
        alpha (spit-skill root "alpha" "Alpha work")
        beta (spit-skill root "beta" "Beta work")
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})]
    (is (= ["alpha" "beta"] (mapv :name (skills/skill-catalog registry))))
    (delete-skill-dir beta)
    (is (= ["alpha"] (mapv :name (skills/skill-catalog registry))))
    (delete-skill-dir alpha)
    (.delete root)))

(deftest cache-skips-rescan-when-unchanged-test
  (let [root (temp-dir)
        skill-dir (spit-skill root "cached" "Cached skill")
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
        scans (atom 0)
        original-load skills/load-skills-from-dir]
    (with-redefs [skills/load-skills-from-dir
                  (fn [dir source]
                    (swap! scans inc)
                    (original-load dir source))]
      (is (= ["cached"] (mapv :name (skills/skill-catalog registry))))
      (is (= 1 @scans))
      (is (= ["cached"] (mapv :name (skills/skill-catalog registry))))
      (is (= 1 @scans) "unchanged dir must be served from cache without re-parsing"))
    (delete-skill-dir skill-dir)
    (.delete root)))

(deftest slash-command-catalog-filters-and-pages-test
  (let [root (temp-dir)]
    (doseq [[dir desc] [["alpha" "Alpha work"] ["beta" "Beta work"]]]
      (let [skill-dir (io/file root dir)]
        (.mkdirs skill-dir)
        (spit (io/file skill-dir "SKILL.md")
              (str "---\nname: " dir "\ndescription: " desc "\n---\n# " desc))))
    (let [registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
          page (skills/slash-commands-page registry {:prefix "a" :page 1 :page-size 1})]
      (is (= 1 (:total page)))
      (is (false? (:has-more page)))
      (is (= ["alpha"] (mapv :name (:items page)))))
    (doseq [child (.listFiles root)]
      (io/delete-file (io/file child "SKILL.md") true)
      (.delete child))
    (.delete root)))

(deftest bundled-iris-tools-skill-test
  (let [registry (skills/create-registry {:dirs ["skills"]})
        names (set (map :name (skills/skill-catalog registry)))
        section (skills/invoked-skills-section registry "/iris-tools")]
    (is (contains? names "iris-tools"))
    (is (str/includes? section "### /iris-tools"))
    (is (str/includes? section "MAGI is for independent judgment"))
    (is (str/includes? section "scratchpad_replace"))))
