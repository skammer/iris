(ns agent.skills-test
  (:require
   [agent.skills :as skills]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-dir []
  (let [dir (java.nio.file.Files/createTempDirectory "clj-agent-skills-" (make-array java.nio.file.attribute.FileAttribute 0))]
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
