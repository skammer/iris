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
      (is (= 5 (count surfaces)))
      (is (str/includes? (:combined prompt) "remember this fact"))
      (is (= 1 (count (:messages results))))
      (is (= 1 (count (:events results)))))
    (io/delete-file prompt-file true)
    (.delete prompt-dir)
    (io/delete-file db-path true)))

(deftest memory-facts-dedup-and-scope-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :facts {:extractor {:enabled false}
                          :default-scope :session}
                  :graph {:enabled false}}
                 store)]
    (try
      (let [first-save (memory/save-memory-fact! service
                                                 {:subject "Alice"
                                                  :predicate "Likes"
                                                  :object "Clojure"}
                                                 {:scope {:type :session :id "s1"}
                                                  :source-message-ids [1]})
            second-save (memory/save-memory-fact! service
                                                  {:subject " alice "
                                                   :predicate "likes"
                                                   :object "clojure"}
                                                  {:scope {:type :session :id "s1"}
                                                   :source-message-ids [2]})
            same-scope (memory/search-facts service "alice" {:scope {:type :session :id "s1"}})
            other-scope (memory/search-facts service "alice" {:scope {:type :session :id "s2"}})]
        (is (:created? first-save))
        (is (false? (:created? second-save)))
        (is (= 1 (count same-scope)))
        (is (= [1 2] (:source-message-ids (first same-scope))))
        (is (empty? other-scope)))
      (finally
        (io/delete-file db-path true)))))

(deftest memory-facts-similarity-fallback-test
  (let [db-path (temp-db-path)
        store (sqlite/create-store {:path db-path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :facts {:dedup {:similarity-threshold 0.6}}
                  :graph {:enabled false}}
                 store)]
    (try
      (memory/save-memory-fact! service
                                {:subject "Alice"
                                 :predicate "likes"
                                 :object "Clojure language"}
                                {:scope {:type :global}
                                 :source-message-ids [1]})
      (let [saved (memory/save-memory-fact! service
                                            {:subject "Alice"
                                             :predicate "likes"
                                             :object "Clojure"}
                                            {:scope {:type :global}
                                             :source-message-ids [2]})
            facts (memory/search-facts service "Alice" {:scope {:type :global}})]
        (is (false? (:created? saved)))
        (is (:similar-duplicate? saved))
        (is (= 1 (count facts)))
        (is (= [1 2] (:source-message-ids (first facts)))))
      (finally
        (io/delete-file db-path true)))))

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
