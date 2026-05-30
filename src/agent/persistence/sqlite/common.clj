(ns agent.persistence.sqlite.common
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (com.zaxxer.hikari HikariConfig HikariDataSource)
   (java.sql Connection PreparedStatement ResultSet)
   (java.time Instant)
   (java.util UUID)
   (org.sqlite SQLiteException)))

(def default-busy-timeout-ms 5000)
(def default-retry-attempts 8)
(def default-retry-delay-ms 75)

(defn ensure-parent-dir! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(defn jdbc-url [path]
  (str "jdbc:sqlite:" path))

(defn normalize-name [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn now-str []
  (str (Instant/now)))

(defn uuid-str []
  (str (UUID/randomUUID)))

(defn json-string [value]
  (when (some? value)
    (json/generate-string value)))

(defn parse-json-string [value]
  (when value
    (json/parse-string value true)))

(defn fts5-query [value]
  (let [tokens (->> (re-seq #"[\p{L}\p{N}_]+" (str/lower-case (or value "")))
                    (remove str/blank?)
                    distinct)]
    (when (seq tokens)
      (str/join " OR " (map #(str "\"" % "\"") tokens)))))

(defn execute-ddl! [^Connection conn sql]
  (with-open [stmt (.createStatement conn)]
    (.execute stmt sql)))

(defn table-exists? [^Connection conn table-name]
  (with-open [stmt (.prepareStatement conn
                                      "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")]
    (.setString stmt 1 table-name)
    (with-open [rs (.executeQuery stmt)]
      (and (.next rs)
           (pos? (.getInt rs 1))))))

(defn column-exists? [^Connection conn table-name column-name]
  (with-open [stmt (.prepareStatement conn (str "PRAGMA table_info(" table-name ")"))
              rs (.executeQuery stmt)]
    (loop []
      (if (.next rs)
        (if (= column-name (.getString rs "name"))
          true
          (recur))
        false))))

(defn get-user-version [^Connection conn]
  (with-open [stmt (.prepareStatement conn "PRAGMA user_version")
              rs (.executeQuery stmt)]
    (.next rs)
    (.getInt rs 1)))

(defn set-user-version! [^Connection conn version]
  (execute-ddl! conn (str "PRAGMA user_version = " (int version))))

(defn create-datasource
  [{:keys [path maximum-pool-size minimum-idle connection-timeout-ms pool-name]
    :or {maximum-pool-size 8
         minimum-idle 2
         connection-timeout-ms 30000}}]
  (ensure-parent-dir! path)
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (jdbc-url path))
                 (.setPoolName (or pool-name (str "iris-sqlite-" (Math/abs (hash path)))))
                 (.setMaximumPoolSize (int maximum-pool-size))
                 (.setMinimumIdle (int minimum-idle))
                 (.setConnectionTimeout (long connection-timeout-ms))
                 (.setAutoCommit true)
                 (.setInitializationFailTimeout -1))]
    (HikariDataSource. config)))

(declare close-connection! with-connection)

(defn configure-connection! [store ^Connection conn]
  (execute-ddl! conn (str "PRAGMA busy_timeout=" (or (:busy-timeout-ms store)
                                                     default-busy-timeout-ms)
                          ";"))
  conn)

(defn- transient-sqlite-error? [^Throwable ex]
  (let [message (or (.getMessage ex) "")]
    (and (instance? SQLiteException ex)
         (boolean (re-find #"\[SQLITE_(BUSY|LOCKED|IOERR|CANTOPEN)" message)))))

(defn- retry-delay-ms [attempt]
  (min 750 (* default-retry-delay-ms (inc attempt))))

(defn- with-sqlite-retry [store f]
  (let [attempts (long (or (:retry-attempts store) default-retry-attempts))]
    (loop [attempt 0]
      (let [result (try
                     [:ok (f)]
                     (catch Exception ex
                       (if (and (< attempt attempts)
                                (transient-sqlite-error? ex))
                         [:retry ex]
                         (throw ex))))]
        (case (first result)
          :ok (second result)
          :retry (do
                   (Thread/sleep (retry-delay-ms attempt))
                   (recur (inc attempt))))))))

(defn- open-configured-connection! [store ^HikariDataSource datasource]
  ;; No retry here: with-connection / with-transaction wrap the whole
  ;; unit-of-work (acquire + execute) in with-sqlite-retry, so connection
  ;; acquisition and statement execution share a single retry layer.
  (let [conn (.getConnection datasource)]
    (try
      (configure-connection! store conn)
      conn
      (catch Exception ex
        (.close conn)
        (throw ex)))))

(defn apply-journal-mode! [store]
  (with-connection
    store
    (fn [conn]
      (execute-ddl! conn (str "PRAGMA journal_mode="
                              (or (:journal-mode store) "WAL")
                              ";")))))

(defn- close-connection! [store ^HikariDataSource datasource ^Connection conn]
  (try
    (when (:evict-on-close? store)
      (.evictConnection datasource conn))
    (finally
      (.close conn))))

(defn with-connection [store f]
  (let [datasource ^HikariDataSource (:datasource store)]
    (with-sqlite-retry
      store
      (fn []
        (let [conn (open-configured-connection! store datasource)]
          (try
            (f conn)
            (finally
              (close-connection! store datasource conn))))))))

(defn with-transaction [store f]
  (locking (:tx-lock store)
    (let [datasource ^HikariDataSource (:datasource store)]
      ;; Retry the whole transaction (fresh connection per attempt) on a
      ;; transient SQLITE_BUSY/LOCKED — whether it surfaces at acquisition,
      ;; statement execution, or commit. Each attempt is rolled back before
      ;; the error propagates, so the unit-of-work replays cleanly.
      (with-sqlite-retry
        store
        (fn []
          (let [conn (open-configured-connection! store datasource)]
            (try
              (.setAutoCommit conn false)
              (try
                (let [result (f conn)]
                  (.commit conn)
                  result)
                (catch Exception e
                  (.rollback conn)
                  (throw e))
                (finally
                  (.setAutoCommit conn true)))
              (finally
                (close-connection! store datasource conn)))))))))

(defn bind-params! [^PreparedStatement stmt params]
  (doseq [[idx value] (map-indexed vector params)]
    (.setObject stmt (inc idx) value))
  stmt)

(defn- read-row [^ResultSet rs]
  (let [metadata (.getMetaData rs)
        column-count (.getColumnCount metadata)]
    (reduce (fn [acc idx]
              (assoc acc
                     (keyword (.getColumnLabel metadata idx))
                     (.getObject rs idx)))
            {}
            (range 1 (inc column-count)))))

(defn execute! [^Connection conn [sql & params]]
  (with-open [stmt (.prepareStatement conn sql)]
    (bind-params! stmt params)
    (.executeUpdate stmt)))

(defn select-one
  [^Connection conn [sql & params] row-fn]
  (with-open [stmt (.prepareStatement conn sql)]
    (bind-params! stmt params)
    (with-open [rs (.executeQuery stmt)]
      (when (.next rs)
        (row-fn (read-row rs))))))

(defn select-many
  [^Connection conn [sql & params] row-fn]
  (with-open [stmt (.prepareStatement conn sql)]
    (bind-params! stmt params)
    (with-open [rs (.executeQuery stmt)]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc (row-fn (read-row rs))))
          acc)))))

(defn select-value [^Connection conn [sql & params]]
  (with-open [stmt (.prepareStatement conn sql)]
    (bind-params! stmt params)
    (with-open [rs (.executeQuery stmt)]
      (when (.next rs)
        (.getObject rs 1)))))

(defn close-store! [store]
  (when-let [datasource (:datasource store)]
    (.close ^HikariDataSource datasource)))
