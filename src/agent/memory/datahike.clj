(ns agent.memory.datahike
  "Prototype graph-memory backend using Datahike."
  (:require
   [agent.memory.core]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datahike.api :as d])
  (:import
   (java.time Instant)
   (java.util UUID)))

(def ^:private schema
  [{:db/ident :fact/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/index true}
   {:db/ident :fact/type
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :fact/subject
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :fact/predicate
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :fact/object
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fact/source
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fact/session-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fact/created-at
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :fact/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(defn- ensure-parent-dir! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(defn- backend-path [cfg]
  (get-in cfg [:store :path]))

(defn- create-db-if-needed! [cfg]
  (let [path (backend-path cfg)]
    (ensure-parent-dir! path)
    (when-not (.exists (io/file path))
      (d/create-database (assoc cfg :initial-tx schema)))))

(defn- now [] (str (Instant/now)))

(defn- compact-fact-tx [fact]
  (cond-> {:fact/id (:id fact)
           :fact/type (:type fact)
           :fact/subject (:subject fact)
           :fact/predicate (:predicate fact)
           :fact/object (:object fact)
           :fact/created-at (:created-at fact)}
    (:source fact) (assoc :fact/source (:source fact))
    (:session-id fact) (assoc :fact/session-id (:session-id fact))
    (seq (:tags fact)) (assoc :fact/tags (vec (:tags fact)))))

(defrecord DatahikeGraphBackend [cfg conn]
  agent.memory.core/IGraphMemoryBackend
  (save-fact! [_ fact]
    (let [fact* (merge {:id (str (UUID/randomUUID))
                        :type "fact"
                        :created-at (now)
                        :tags []}
                       fact)
          tx (compact-fact-tx fact*)]
      (d/transact conn {:tx-data [tx]})
      fact*))
  (query-facts [_ query opts]
    (let [limit (or (:limit opts) 20)
          db @conn
          rows (d/q '[:find (pull ?e [*])
                      :where
                      [?e :fact/id ?id]]
                    db)
          needle (some-> query str/lower-case)]
      (->> rows
           (map first)
           (filter (fn [fact]
                     (if (str/blank? needle)
                       true
                       (some #(str/includes? (str/lower-case (str %)) needle)
                             [(:fact/id fact)
                              (:fact/type fact)
                              (:fact/subject fact)
                              (:fact/predicate fact)
                              (:fact/object fact)
                              (:fact/source fact)
                              (:fact/session-id fact)
                              (str/join " " (:fact/tags fact))]))))
           (map (fn [fact]
                  {:id (:fact/id fact)
                   :type (:fact/type fact)
                   :subject (:fact/subject fact)
                   :predicate (:fact/predicate fact)
                   :object (:fact/object fact)
                   :source (:fact/source fact)
                   :session-id (:fact/session-id fact)
                   :created-at (:fact/created-at fact)
                   :tags (vec (:fact/tags fact))}))
           (sort-by :created-at)
           reverse
           (take limit)
           vec)))
  (backend-health-check [_]
    (try
      {:healthy true
       :details {:path (backend-path cfg)
                 :fact-count (count (d/q '[:find ?e :where [?e :fact/id _]] @conn))}}
      (catch Exception e
        {:healthy false
         :details {:path (backend-path cfg)
                   :error (.getMessage e)}}))))

(defn create-backend
  [cfg]
  (create-db-if-needed! cfg)
  (->DatahikeGraphBackend cfg (d/connect cfg)))
