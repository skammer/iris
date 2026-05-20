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
  (-> (tools/create-registry)
      (tools/register-tool (memory-tool/create-memory-tool service))))

(deftest memory-tool-search-returns-compact-text-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "memory-tool")
        service (memory-service store)
        registry* (registry service)]
    (try
      (sqlite/append-message! store (:id session) "assistant"
                              (str "Kimi model marker " (apply str (repeat 2000 "x"))))
      (let [result (tools/execute-tool registry*
                                       :memory
                                       {:action :search
                                        :query "Kimi"
                                        :limit 3}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})]
        (is (string? result))
        (is (str/includes? result "Memory results for: Kimi"))
        (is (str/includes? result "message #"))
        (is (str/includes? result "[truncated "))
        (is (not (str/includes? result "\"messages\"")))
        (is (not (str/includes? result "\"ranked\""))))
      (let [blank-result (tools/execute-tool registry*
                                             :memory
                                             {:action :search
                                              :query ""}
                                             {:permissions #{:memory-read}
                                              :session-id (:id session)})]
        (is (= "Memory search skipped: query is blank. Provide a focused query."
               blank-result)))
      (finally
        (io/delete-file path true)))))

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
        (sqlite/append-message! store (:id session) "assistant" (str "Kimi clamp marker " idx)))
      (let [result (tools/execute-tool registry*
                                       :memory
                                       {:action :search
                                        :query "Kimi"
                                        :limit 99}
                                       {:permissions #{:memory-read}
                                        :session-id (:id session)})
            result-lines (->> (str/split-lines result)
                              (filter #(str/starts-with? % "- "))
                              vec)]
        (is (= 2 (count result-lines))))
      (finally
        (io/delete-file path true)))))
