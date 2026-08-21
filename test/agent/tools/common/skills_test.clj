(ns agent.tools.common.skills-test
  (:require
   [agent.skills :as skills]
   [agent.tools.common.skills :as skills-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "iris-skills-tool-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-skill! [root name description body]
  (let [dir (io/file root name)]
    (.mkdirs dir)
    (spit (io/file dir "SKILL.md")
          (str "---\n"
               "name: " name "\n"
               "description: " description "\n"
               "---\n\n"
               body))
    dir))

(defn- delete-tree! [file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (.listFiles file)]
        (delete-tree! child)))
    (io/delete-file file true)))

(deftest skills-list-tool-lists-public-skill-catalog-test
  (let [root (temp-dir)
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
        tool (skills-tool/create-skills-list-tool registry)
        tool-registry (tools/register-tool (tools/create-registry) tool)]
    (try
      (write-skill! root "review" "Review code" "# Secret review body")
      (write-skill! root "refactor" "Refactor code" "# Secret refactor body")
      (write-skill! root "deploy" "Deploy app" "# Secret deploy body")
      (let [result (tools/execute-tool tool-registry
                                       :skills_list
                                       {:prefix "re"
                                        :limit 1}
                                       {:permissions #{}})]
        (is (= [{:name "refactor" :description "Refactor code"}]
               (:skills result)))
        (is (= 1 (:count result)))
        (is (true? (:truncated? result)))
        (is (not (contains? (first (:skills result)) :body)))
        (is (not (contains? (first (:skills result)) :path)))
        (is (not (contains? (first (:skills result)) :base-dir))))
      (finally
        (delete-tree! root)))))

(deftest skills-list-tool-clamps-limit-and-is-read-only-test
  (let [root (temp-dir)
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
        tool (skills-tool/create-skills-list-tool registry)
        description (tools/describe tool)
        tool-registry (tools/register-tool (tools/create-registry) tool)]
    (try
      (dotimes [i 205]
        (write-skill! root
                      (format "skill%03d" i)
                      (str "Skill " i)
                      "# Body"))
      (testing "metadata"
        (is (= :read (:operation description)))
        (is (= #{:read :search :plan :write :run} (:routing-categories description)))
        (is (true? (:parallel-safe? description)))
        (is (= #{} (:required-permissions description))))
      (let [result (tools/execute-tool tool-registry
                                       :skills_list
                                       {:limit 1000}
                                       {:permissions #{}})]
        (is (= 200 (:count result)))
        (is (true? (:truncated? result))))
      (finally
        (delete-tree! root)))))

(deftest skills-read-tool-loads-exact-skill-body-test
  (let [root (temp-dir)
        registry (skills/create-registry {:dirs [(.getAbsolutePath root)]})
        tool-registry (-> (tools/create-registry)
                          (tools/register-tool (skills-tool/create-skills-list-tool registry))
                          (tools/register-tool (skills-tool/create-skills-read-tool registry)))]
    (try
      (write-skill! root "tavily-search" "Search with Tavily" "Run `~/tavily.sh`." )
      (let [result (tools/execute-tool tool-registry
                                       :skills_read
                                       {:name "tavily-search"}
                                       {:permissions #{}})]
        (is (str/includes? result "# Skill /tavily-search"))
        (is (str/includes? result "Revision: "))
        (is (str/includes? result "Run `~/tavily.sh`.")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"skill not found"
                            (tools/execute-tool tool-registry
                                                :skills_read
                                                {:name "missing"}
                                                {:permissions #{}})))
      (let [description (tools/describe (tools/get-tool tool-registry :skills_read))]
        (is (= :read (:operation description)))
        (is (true? (:parallel-safe? description)))
        (is (= #{} (:required-permissions description))))
      (finally
        (delete-tree! root)))))
