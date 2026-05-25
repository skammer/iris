(ns agent.skills-test
  (:require
   [agent.skills :as skills]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

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
