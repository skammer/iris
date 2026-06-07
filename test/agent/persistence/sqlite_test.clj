(ns agent.persistence.sqlite-test
  (:require
   [agent.persistence.sqlite.common :as sqlite-common]
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]])
  (:import
   (java.sql DriverManager)))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-" ".db")))

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
    (is (= [sqlite/latest-schema-version] (mapv :version history)))
    (is (true? (:healthy health)))
    (is (= 1 (get-in health [:details :event-count])))
    (is (= 0 (get-in health [:details :tool-approval-count])))
    (is (= 0 (get-in health [:details :agent-run-count])))
    (is (= 0 (get-in health [:details :federation-peer-key-count])))
    (is (= 0 (get-in health [:details :federation-outbox-count])))
    (is (true? (get-in health [:details :up-to-date?])))
    (io/delete-file path true)))

(deftest sqlite-tool-approval-decision-is-pending-cas-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        approval (sqlite/create-tool-approval! store
                                               {:tool-name :shell
                                                :input {:argv ["printf" "ok"]}
                                                :input-hash "hash"
                                                :requested-permissions #{:shell-exec}
                                                :requested-by "tester"
                                                :reason "test"
                                                :expires-at nil})]
    (try
      (is (= "approved"
             (:status (sqlite/decide-tool-approval! store (:id approval) :approved "tester" "ok"))))
      (let [err (try
                  (sqlite/decide-tool-approval! store (:id approval) :denied "tester" "late")
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    e))]
        (is (some? err))
        (is (re-find #"not pending" (.getMessage err))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest sqlite-session-active-mode-flow-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "mode")]
    (try
      (is (nil? (:active-mode session)))
      (is (nil? (:active-mode (sqlite/get-session store (:id session)))))
      (is (nil? (:active-mode (first (sqlite/list-sessions store)))))
      (is (= "code"
             (:active-mode (sqlite/set-session-active-mode! store (:id session) "code"))))
      (is (= "code" (:active-mode (sqlite/get-session store (:id session)))))
      (is (nil? (:active-mode (sqlite/set-session-active-mode! store (:id session) nil))))
      (finally
        (io/delete-file path true)))))

(deftest sqlite-session-list-follows-latest-message-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        first-session (sqlite/create-session! store "first")
        second-session (sqlite/create-session! store "second")]
    (try
      (sqlite/append-message! store (:id first-session) "user" "latest activity")
      (is (= [(:id first-session) (:id second-session)]
             (mapv :id (sqlite/list-sessions store))))
      (finally
        (io/delete-file path true)))))

(deftest sqlite-rejects-unversioned-existing-schema-test
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
    (let [err (try
                (sqlite/create-store {:path path})
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
      (is (some? err))
      (is (= :migration-drift (:type (ex-data err))))
      (is (= :unversioned-schema (:reason (ex-data err)))))
    (io/delete-file path true)))

(deftest sqlite-migration-drift-reports-reset-files-test
  (let [path (temp-db-path)]
    (Class/forName "org.sqlite.JDBC")
    (with-open [conn (DriverManager/getConnection (sqlite/jdbc-url path))
                stmt (.createStatement conn)]
      (.execute stmt "CREATE TABLE schema_migration_meta (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        irreversible INTEGER NOT NULL,
                        applied_at TEXT NOT NULL
                      );")
      (.execute stmt "INSERT INTO schema_migration_meta
                      (version, name, checksum, irreversible, applied_at)
                      VALUES (999, 'drift', 'bad', 1, '2026-01-01T00:00:00Z');"))
    (let [err (try
                (sqlite/create-store {:path path})
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
      (is (some? err))
      (is (re-find #"SQLite migration drift detected" (.getMessage err)))
      (is (= :migration-drift (:type (ex-data err))))
      (is (some #(= path %) (:files-to-delete (ex-data err)))))
    (io/delete-file path true)))

(deftest sqlite-migration-drift-can-reset-destructively-test
  (let [path (temp-db-path)]
    (Class/forName "org.sqlite.JDBC")
    (with-open [conn (DriverManager/getConnection (sqlite/jdbc-url path))
                stmt (.createStatement conn)]
      (.execute stmt "CREATE TABLE schema_migration_meta (
                        version INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        checksum TEXT NOT NULL,
                        irreversible INTEGER NOT NULL,
                        applied_at TEXT NOT NULL
                      );")
      (.execute stmt "INSERT INTO schema_migration_meta
                      (version, name, checksum, irreversible, applied_at)
                      VALUES (999, 'drift', 'bad', 1, '2026-01-01T00:00:00Z');"))
    (let [store (sqlite/create-store {:path path
                                      :destructive-reset-on-drift? true})]
      (is (= sqlite/latest-schema-version (sqlite/schema-version store)))
      (is (true? (:healthy (sqlite/health-check store))))
      (sqlite/close-store! store))
    (io/delete-file path true)))

(deftest sqlite-foreign-keys-are-enforced-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [err (try
                  (sqlite/append-message! store "missing-session" "user" "hello")
                  nil
                  (catch java.sql.SQLException e
                    e))]
        (is (some? err)))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest sqlite-select-value-returns-first-column-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (is (= 42
             (sqlite-common/with-connection
               store
               #(sqlite-common/select-value % ["SELECT 42 AS n"]))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))

(deftest sqlite-memory-search-uses-fts-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})
        session (sqlite/create-session! store "fts")]
    (try
      (sqlite/append-message! store (:id session) "user" "alpha gap beta")
      (sqlite/log-event! store {:event-type :memory.marker
                                :entity-type :session
                                :entity-id (:id session)
                                :payload {:text "gamma gap delta"}})
      (sqlite/save-memory-fact! store {:subject "epsilon"
                                       :predicate "points-to"
                                       :object "zeta marker"})
      (is (= ["alpha gap beta"]
             (mapv :content (sqlite/search-messages store "alpha beta"))))
      (is (= ["memory.marker"]
             (mapv :event-type (sqlite/search-events store "gamma delta"))))
      (is (= ["zeta marker"]
             (mapv :object (sqlite/search-memory-facts store "epsilon zeta"))))
      (finally
        (io/delete-file path true)))))

(deftest sqlite-todo-list-flow-test
  (let [path (temp-db-path)
        store (sqlite/create-store {:path path})]
    (try
      (let [saved (sqlite/save-todo-list!
                   store
                   {:thread-id "thread-1"
                    :description "Implementation checklist"
                    :todos [{:content "Add migration"
                             :description "Include FTS for item notes"
                             :status :pending
                             :priority :high}]
                    :metadata {:source "test"}})
            before-updated-at (:updated-at saved)
            _ (Thread/sleep 2)
            updated (sqlite/save-todo-list!
                     store
                     {:thread-id "thread-1"
                      :slug "default"
                      :description "Updated checklist"
                      :todos [{:content "Add migration"
                               :description "FTS note retained"
                               :status "completed"
                               :priority "high"}]
                      :metadata {:source "updated"}})
            found (sqlite/get-todo-list store {:thread-id "thread-1"
                                               :slug "default"})
            search-results (sqlite/search-todo-lists store "retained" {:thread-id "thread-1"})]
        (is (:created? saved))
        (is (false? (:created? updated)))
        (is (= (:id saved) (:id updated)))
        (is (not= before-updated-at (:updated-at updated)))
        (is (= "FTS note retained" (get-in found [:todos 0 :description])))
        (is (= ["default"] (mapv :slug search-results)))
        (is (= 1 (sqlite/count-todo-lists store)))
        (with-open [conn (DriverManager/getConnection (sqlite/jdbc-url path))
                    stmt (.createStatement conn)
                    rs (.executeQuery stmt "SELECT count(*) FROM todo_items")]
          (.next rs)
          (is (= 1 (.getInt rs 1)))))
      (finally
        (sqlite/close-store! store)
        (io/delete-file path true)))))
