(ns agent.memory.core-test
  (:require
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-memory-" ".db")))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-agent-memory-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest memory-service-exposes-surfaces-and-search-test
  (let [db-path (temp-db-path)
        prompt-dir (temp-dir)
        prompt-file (io/file prompt-dir "MEMORY.md")
        store (sqlite/create-store {:path db-path})
        session (sqlite/create-session! store "mem")]
    (spit prompt-file "remember this fact")
    (sqlite/append-message! store (:id session) "user" "hello memory")
    (sqlite/log-event! store {:event-type :session.created
                              :entity-type :session
                              :entity-id (:id session)
                              :payload {:title "memory hello"}})
    (let [service (memory/create-memory-service
                   {:prompt {:paths [(.getAbsolutePath prompt-file)]}
                    :search {:default-limit 10}
                    :graph {:enabled false
                            :backend :datahike
                            :datahike {:path (.getAbsolutePath (io/file prompt-dir "graph"))}}}
                   store)
          prompt (memory/read-prompt-memory service)
          results (memory/search-memory service "hello")
          surfaces (memory/list-surfaces service)]
      (is (= 3 (count surfaces)))
      (is (str/includes? (:combined prompt) "remember this fact"))
      (is (= 1 (count (:messages results))))
      (is (= 1 (count (:events results)))))
    (io/delete-file prompt-file true)
    (.delete prompt-dir)
    (io/delete-file db-path true)))

(deftest datahike-graph-memory-prototype-test
  (let [db-path (temp-db-path)
        graph-root (temp-dir)
        graph-path (.getAbsolutePath (io/file graph-root "graph-store"))
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)
        saved (memory/save-graph-fact! service
                                       {:subject "alice"
                                        :predicate "likes"
                                        :object "clojure"
                                        :tags ["lang"]})
        queried (memory/query-graph-memory service "alice")]
    (is (= "alice" (:subject saved)))
    (is (= 1 (count queried)))
    (is (= "likes" (:predicate (first queried))))
    (io/delete-file db-path true)))
