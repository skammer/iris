(ns agent.tools.common.memory-test
  (:require
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.common.memory :as memory-tool]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(defn- temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-memory-tool-" ".db")))

(defn- memory-service [store]
  (memory/create-memory-service
   {:prompt {:paths []}
    :search {:default-limit 10}
    :facts {:extractor {:enabled false}}
    :graph {:enabled false}}
   store))

(defn- registry [service]
  (reduce tools/register-tool
          (tools/create-registry)
          (conj (memory-tool/create-memory-tools service)
                (memory-tool/create-message-search-tool service))))

(deftest memory-tool-search-uses-facts-graph-and-prompt-files-test
  (let [path (temp-db-path)
        root (doto (java.nio.file.Files/createTempDirectory "iris-memory-tool" (make-array java.nio.file.attribute.FileAttribute 0))
               (.toFile))
        root-file (.toFile root)
        prompt-file (io/file root-file "MEMORY.md")
        graph-path (.getAbsolutePath (io/file root-file "graph-store"))
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "memory-tool")
        _ (spit prompt-file (str "Kimi prompt marker " (apply str (repeat 1200 "x"))))
        service (memory/create-memory-service
                 {:prompt {:paths [(.getAbsolutePath prompt-file)]}
                  :search {:default-limit 10}
                  :facts {:extractor {:enabled false}}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)
        registry* (registry service)]
    (try
      (sqlite/append-message! store (:id session) "assistant"
                              (str "Kimi model marker " (apply str (repeat 2000 "x"))))
      (memory/save-memory-fact! service
                                {:subject "Kimi"
                                 :predicate "supports"
                                 :object "memory facts"}
                                {:scope {:type :global}
                                 :source-session-id (:id session)})
      (let [result (tools/execute-tool registry*
                                       :memory_search
                                       {:query "Kimi"
                                        :limit 3}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})]
        (is (string? result))
        (is (str/includes? result "Memory results for: Kimi"))
        (is (str/includes? result "fact #"))
        (is (str/includes? result "graph #"))
        (is (str/includes? result "prompt #MEMORY.md"))
        (is (str/includes? result "[truncated "))
        (is (not (str/includes? result "message #")))
        (is (not (str/includes? result "\"messages\"")))
        (is (not (str/includes? result "\"ranked\""))))
      (let [blank-result (tools/execute-tool registry*
                                             :memory_search
                                             {:query ""}
                                             {:permissions #{:memory-read}
                                              :session-id (:id session)})]
        (is (= "Memory search skipped: query is blank. Provide a focused query."
               blank-result)))
      (finally
        (io/delete-file path true)
        (io/delete-file root-file true)))))

(deftest memory-tool-search-clamps-requested-limit-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "memory-tool-limit")
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 2
                           :max-limit 2}
                  :facts {:extractor {:enabled false}}
                  :graph {:enabled false}}
                 store)
        registry* (registry service)]
    (try
      (doseq [idx (range 6)]
        (memory/save-memory-fact! service
                                  {:subject "Kimi"
                                   :predicate "clamp-marker"
                                   :object (str "value-" idx)}
                                  {:scope {:type :global}
                                   :source-session-id (:id session)}))
      (let [result (tools/execute-tool registry*
                                       :memory_search
                                       {:query "Kimi"
                                        :limit 99}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})
            result-lines (->> (str/split-lines result)
                              (filter #(str/starts-with? % "- "))
                              vec)]
        (is (= 2 (count result-lines))))
      (finally
        (io/delete-file path true)))))

(deftest message-search-tool-returns-only-text-chunks-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "message-search")
        service (memory-service store)
        registry* (registry service)]
    (try
      (sqlite/append-message! store (:id session) "assistant"
                              (str "alpha "
                                   (apply str (repeat 1200 "x"))
                                   " Kimi chunk marker omega"))
      (let [result (tools/execute-tool registry*
                                       :message_search
                                       {:query "Kimi"
                                        :limit 3}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})]
        (is (str/includes? result "Message chunks for: Kimi"))
        (is (str/includes? result "Kimi chunk marker"))
        (is (not (str/includes? result "message #")))
        (is (not (str/includes? result "session-id"))))
      (finally
        (io/delete-file path true)))))

(deftest message-search-tool-accepts-provider-string-limit-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "message-search-string-limit")
        service (memory-service store)
        registry* (registry service)]
    (try
      (doseq [idx (range 3)]
        (sqlite/append-message! store (:id session) "assistant" (str "Kimi chunk " idx)))
      (let [result (tools/execute-tool registry*
                                       :message_search
                                       {:query "Kimi"
                                        :limit "1"
                                        :session-id (:id session)}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})
            result-lines (->> (str/split-lines result)
                              (filter #(str/starts-with? % "- "))
                              vec)]
        (is (= 1 (count result-lines))))
      (finally
        (io/delete-file path true)))))

(deftest memory-tool-removes-sqlite-facts-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        service (memory-service store)
        registry* (registry service)]
    (try
      (tools/execute-tool registry*
                          :memory_save_fact
                          {:subject "Kimi"
                           :predicate "stores"
                           :object "facts"
                           :scope {:type :global}}
                          {:permissions #{:memory-write}})
      (is (= 1 (count (memory/search-facts service "Kimi" {:all-scopes? true}))))
      (tools/execute-tool registry*
                          :memory_remove_fact
                          {:subject "Kimi"
                           :predicate "stores"
                           :object "facts"
                           :scope {:type :global}}
                          {:permissions #{:memory-write}})
      (is (empty? (memory/search-facts service "Kimi" {:all-scopes? true})))
      (finally
        (io/delete-file path true)))))

(deftest memory-tool-stores-removes-graph-facts-and-runs-datalog-test
  (let [path (temp-db-path)
        root (doto (java.nio.file.Files/createTempDirectory "iris-memory-graph-tool" (make-array java.nio.file.attribute.FileAttribute 0))
               (.toFile))
        root-file (.toFile root)
        graph-path (.getAbsolutePath (io/file root-file "graph-store"))
        store (sqlite/create-store {:path path})
        service (memory/create-memory-service
                 {:prompt {:paths []}
                  :search {:default-limit 10}
                  :facts {:extractor {:enabled false}}
                  :graph {:enabled true
                          :backend :datahike
                          :datahike {:path graph-path
                                     :keep-history? true}}}
                 store)
        registry* (registry service)]
    (try
      (tools/execute-tool registry*
                          :memory_save_graph_fact
                          {:id "graph-kimi-fact"
                           :subject "Kimi"
                           :predicate "powers"
                           :object "graph memory"}
                          {:permissions #{:memory-write}})
      (is (= ["graph memory"]
             (mapv :object (memory/query-graph-memory service "Kimi" {:mode :facts}))))
      (is (str/includes?
           (tools/execute-tool registry*
                               :memory_datalog
                               {:query "[:find ?label :where [?e :entity/label ?label]]"
                                :limit 10}
                               {:permissions #{:memory-read}})
           "Kimi"))
      (tools/execute-tool registry*
                          :memory_remove_graph_fact
                          {:id "graph-kimi-fact"}
                          {:permissions #{:memory-write}})
      (is (empty? (memory/query-graph-memory service "Kimi" {:mode :facts})))
      (finally
        (io/delete-file path true)
        (io/delete-file root-file true)))))
