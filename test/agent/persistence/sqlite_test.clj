(ns agent.persistence.sqlite-test
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.test :refer :all])
  (:import
   (java.sql DriverManager)))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "clj-agent-" ".db")))

(deftest sqlite-session-flow-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "test")
        _ (sqlite/append-message! store (:id session) "user" "hello")
        _ (sqlite/append-message! store (:id session) "assistant" "world")
        _ (sqlite/log-event! store {:event-type :session.created
                                    :entity-type :session
                                    :entity-id (:id session)
                                    :payload {:title "test"}})
        _ (sqlite/log-completion! store {:session-id (:id session)
                                         :provider :ollama
                                         :model "llama3.2:3b"
                                         :prompt "hello"
                                         :response "world"})
        sessions (sqlite/list-sessions store)
        messages (sqlite/list-messages store (:id session))
        events (sqlite/list-events store {:entity-type :session
                                          :entity-id (:id session)})
        health (sqlite/health-check store)
        history (sqlite/migration-history store)]
    (is (= 1 (count sessions)))
    (is (= 2 (count messages)))
    (is (= 1 (count events)))
    (is (= "session.created" (:event-type (first events))))
    (is (= "hello" (:content (first messages))))
    (is (= sqlite/latest-schema-version (sqlite/schema-version store)))
    (is (= [1 2 3 4 5 6 7] (mapv :version history)))
    (is (true? (:healthy health)))
    (is (= 1 (get-in health [:details :event-count])))
    (is (= 0 (get-in health [:details :tool-approval-count])))
    (is (= 0 (get-in health [:details :agent-run-count])))
    (is (true? (get-in health [:details :up-to-date?])))
    (io/delete-file path true)))

(deftest sqlite-upgrades-unversioned-legacy-db-test
  (let [path (temp-db-path)]
    (Class/forName "org.sqlite.JDBC")
    (with-open [conn (DriverManager/getConnection (sqlite/jdbc-url path))]
      (with-open [stmt (.createStatement conn)]
        (.execute stmt "CREATE TABLE sessions (
                          id TEXT PRIMARY KEY,
                          title TEXT,
                          created_at TEXT NOT NULL
                        );")
        (.execute stmt "CREATE TABLE messages (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          session_id TEXT NOT NULL,
                          role TEXT NOT NULL,
                          content TEXT NOT NULL,
                          created_at TEXT NOT NULL
                        );")
        (.execute stmt "CREATE TABLE completions (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          session_id TEXT,
                          provider TEXT NOT NULL,
                          model TEXT,
                          prompt TEXT,
                          response TEXT,
                          created_at TEXT NOT NULL
                        );")))
      (let [store (sqlite/create-store {:path path})
          health (sqlite/health-check store)
          history (sqlite/migration-history store)
          session (sqlite/create-session! store "migrated")]
      (is (= sqlite/latest-schema-version (sqlite/schema-version store)))
      (is (= [1 2 3 4 5 6 7] (mapv :version history)))
      (is (true? (:healthy health)))
      (is (true? (get-in health [:details :up-to-date?])))
      (is (string? (:id session))))
    (io/delete-file path true)))
