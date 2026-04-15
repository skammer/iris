(ns agent.persistence.sqlite
  "SQLite-backed persistence for sessions, messages, and completion logs."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.sql DriverManager PreparedStatement ResultSet Statement Timestamp)
   (java.time Instant)
   (java.util UUID)))

(defn- ensure-parent-dir! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(defn jdbc-url [path]
  (str "jdbc:sqlite:" path))

(defn- execute-ddl! [conn sql]
  (with-open [stmt (.createStatement conn)]
    (.execute stmt sql)))

(defn init-store!
  [{:keys [path]}]
  (Class/forName "org.sqlite.JDBC")
  (ensure-parent-dir! path)
  (with-open [conn (DriverManager/getConnection (jdbc-url path))]
    (execute-ddl! conn "PRAGMA journal_mode=WAL;")
    (execute-ddl! conn "CREATE TABLE IF NOT EXISTS sessions (
                          id TEXT PRIMARY KEY,
                          title TEXT,
                          created_at TEXT NOT NULL
                        );")
    (execute-ddl! conn "CREATE TABLE IF NOT EXISTS messages (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          session_id TEXT NOT NULL,
                          role TEXT NOT NULL,
                          content TEXT NOT NULL,
                          created_at TEXT NOT NULL,
                          FOREIGN KEY(session_id) REFERENCES sessions(id)
                        );")
    (execute-ddl! conn "CREATE INDEX IF NOT EXISTS idx_messages_session_created
                        ON messages(session_id, created_at);")
    (execute-ddl! conn "CREATE TABLE IF NOT EXISTS completions (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          session_id TEXT,
                          provider TEXT NOT NULL,
                          model TEXT,
                          prompt TEXT,
                          response TEXT,
                          created_at TEXT NOT NULL
                        );"))
  {:path path})

(defn create-store
  [config]
  (init-store! config))

(defn- with-connection [store f]
  (with-open [conn (DriverManager/getConnection (jdbc-url (:path store)))]
    (f conn)))

(defn create-session!
  ([store] (create-session! store nil))
  ([store title]
   (let [id (str (UUID/randomUUID))
         created-at (str (Instant/now))]
     (with-connection
       store
       (fn [conn]
         (with-open [stmt (.prepareStatement conn
                                            "INSERT INTO sessions (id, title, created_at) VALUES (?, ?, ?)")]
           (.setString stmt 1 id)
           (.setString stmt 2 title)
           (.setString stmt 3 created-at)
           (.executeUpdate stmt))))
     {:id id
      :title title
      :created-at created-at})))

(defn list-sessions
  [store]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT id, title, created_at FROM sessions ORDER BY created_at DESC")
                  rs (.executeQuery stmt)]
        (loop [acc []]
          (if (.next rs)
            (recur (conj acc {:id (.getString rs "id")
                              :title (.getString rs "title")
                              :created-at (.getString rs "created_at")}))
            acc))))))

(defn session-exists?
  [store session-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT 1 FROM sessions WHERE id = ? LIMIT 1")]
        (.setString stmt 1 session-id)
        (with-open [rs (.executeQuery stmt)]
          (.next rs))))))

(defn append-message!
  [store session-id role content]
  (let [created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO messages (session_id, role, content, created_at) VALUES (?, ?, ?, ?)")]
          (.setString stmt 1 session-id)
          (.setString stmt 2 role)
          (.setString stmt 3 content)
          (.setString stmt 4 created-at)
          (.executeUpdate stmt))))
    {:session-id session-id
     :role role
     :content content
     :created-at created-at}))

(defn list-messages
  [store session-id]
  (with-connection
    store
    (fn [conn]
      (with-open [stmt (.prepareStatement conn
                                         "SELECT role, content, created_at FROM messages WHERE session_id = ? ORDER BY id ASC")]
        (.setString stmt 1 session-id)
        (with-open [rs (.executeQuery stmt)]
          (loop [acc []]
            (if (.next rs)
              (recur (conj acc {:role (.getString rs "role")
                                :content (.getString rs "content")
                                :created-at (.getString rs "created_at")}))
              acc)))))))

(defn log-completion!
  [store {:keys [session-id provider model prompt response]}]
  (let [created-at (str (Instant/now))]
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn
                                           "INSERT INTO completions (session_id, provider, model, prompt, response, created_at) VALUES (?, ?, ?, ?, ?, ?)")]
          (.setString stmt 1 session-id)
          (.setString stmt 2 (name provider))
          (.setString stmt 3 model)
          (.setString stmt 4 prompt)
          (.setString stmt 5 response)
          (.setString stmt 6 created-at)
          (.executeUpdate stmt))))
    {:session-id session-id
     :provider provider
     :model model
     :prompt prompt
     :response response
     :created-at created-at}))

(defn health-check
  [store]
  (try
    (with-connection
      store
      (fn [conn]
        (with-open [stmt (.prepareStatement conn "SELECT COUNT(*) AS n FROM sessions")
                    rs (.executeQuery stmt)]
          (.next rs)
          {:healthy true
           :details {:path (:path store)
                     :session-count (.getInt rs "n")}})))
    (catch Exception e
      {:healthy false
       :details {:path (:path store)
                 :error (.getMessage e)}})))
