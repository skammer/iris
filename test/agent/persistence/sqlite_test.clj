(ns agent.persistence.sqlite-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-" ".db")))

(deftest sqlite-session-flow-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "test")
        _ (sqlite/append-message! store (:id session) "user" "hello")
        _ (sqlite/append-message! store (:id session) "assistant" "world")
        _ (sqlite/log-completion! store {:session-id (:id session)
                                         :provider :ollama
                                         :model "llama3.2:3b"
                                         :prompt "hello"
                                         :response "world"})
        sessions (sqlite/list-sessions store)
        messages (sqlite/list-messages store (:id session))
        health (sqlite/health-check store)]
    (is (= 1 (count sessions)))
    (is (= 2 (count messages)))
    (is (= "hello" (:content (first messages))))
    (is (true? (:healthy health)))
    (io/delete-file path true)))
