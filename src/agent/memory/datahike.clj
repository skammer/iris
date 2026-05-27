(ns agent.memory.datahike
  "Prototype graph-memory backend using Datahike."
  (:require
   [agent.memory.core]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [datahike.api :as d]
   [taoensso.timbre :as timbre])
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
    :db/cardinality :db.cardinality/many}
   {:db/ident :entity/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/index true}
   {:db/ident :entity/label
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :entity/normalized
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :entity/type
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :entity/aliases
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :entity/updated-at
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :episode/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/index true}
   {:db/ident :episode/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :episode/source
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :episode/session-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :episode/request-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :episode/created-at
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :edge/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/index true}
   {:db/ident :edge/source
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/target
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/predicate
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/confidence
    :db/valueType :db.type/double
    :db/cardinality :db.cardinality/one}
   {:db/ident :edge/valid-from
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/valid-to
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/observed-at
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/invalidated-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :edge/source-fact-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident :edge/episodes
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :edge/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(defn- ensure-parent-dir! [path]
  (let [file (io/file path)
        parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))))

(def ^:private datahike-log-filter-installed? (atom false))

(defn- suppress-datahike-debug
  [{:keys [level ?ns-str] :as data}]
  (if (and (contains? #{:trace :debug :info} level)
           (string? ?ns-str)
           (str/starts-with? ?ns-str "datahike."))
    nil
    data))

(defn- quiet-datahike-logs! []
  (when (compare-and-set! datahike-log-filter-installed? false true)
    (timbre/merge-config!
     {:middleware (conj (vec (:middleware timbre/*config*))
                        suppress-datahike-debug)})))

(defn- backend-path [cfg]
  (get-in cfg [:store :path]))

(defn- create-db-if-needed! [cfg]
  (let [path (backend-path cfg)]
    (ensure-parent-dir! path)
    (when-not (.exists (io/file path))
      (d/create-database (assoc cfg :initial-tx schema)))))

(defn- now [] (str (Instant/now)))

(defn- ensure-schema! [conn]
  (d/transact conn {:tx-data schema}))

(defn- distinct-by* [f coll]
  (second
   (reduce (fn [[seen results] item]
             (let [k (f item)]
               (if (contains? seen k)
                 [seen results]
                 [(conj seen k) (conj results item)])))
           [#{} []]
           coll)))

(defn- normalize-entity [value]
  (-> (or value "")
      str/trim
      str/lower-case
      (str/replace #"\s+" " ")))

(defn- entity-id [value]
  (str "entity:" (normalize-entity value)))

(defn- query-entity-by-normalized [db value]
  (ffirst
   (d/q '[:find (pull ?e [*])
          :in $ ?normalized
          :where
          [?e :entity/normalized ?normalized]]
        db
        (normalize-entity value))))

(defn- query-alias [db value]
  (ffirst
   (d/q '[:find (pull ?e [*])
          :in $ ?alias
          :where
          [?e :entity/aliases ?alias]]
        db
        (normalize-entity value))))

(defn- canonical-entity-id [db value]
  (or (:entity/id (query-alias db value))
      (:entity/id (query-entity-by-normalized db value))
      (entity-id value)))

(defn- edge-id [fact]
  (or (:id fact)
      (str "edge:" (UUID/randomUUID))))

(defn- episode-id [fact]
  (or (:episode-id fact)
      (when-let [request-id (and (:episode-content fact)
                                 (:source-request-id fact))]
        (str "request:" request-id))
      (when-let [session-id (and (:episode-content fact)
                                 (:session-id fact))]
        (str "session:" session-id))
      (str "episode:" (UUID/randomUUID))))

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

(defn- entity-tx [db id label type observed-at]
  (if-let [existing (or (query-entity-by-normalized db label)
                        (query-alias db label))]
    {:db/id (:db/id existing)
     :entity/aliases (vec (distinct (conj (or (:entity/aliases existing) [])
                                         (normalize-entity label)
                                         label)))
     :entity/updated-at observed-at}
    {:entity/id id
     :entity/label label
     :entity/normalized (normalize-entity label)
     :entity/type type
     :entity/aliases [(normalize-entity label) label]
     :entity/updated-at observed-at}))

(defn- graph-tx [db fact]
  (let [observed-at (or (:observed-at fact) (:created-at fact) (now))
        subject-id (canonical-entity-id db (:subject fact))
        object-id (canonical-entity-id db (:object fact))
        edge-id* (edge-id fact)
        episode-id* (episode-id fact)
        episode {:episode/id episode-id*
                 :episode/content (or (:episode-content fact) (str (:subject fact) " " (:predicate fact) " " (:object fact)))
                 :episode/created-at observed-at}
        episode* (cond-> episode
                   (:source fact) (assoc :episode/source (:source fact))
                   (:session-id fact) (assoc :episode/session-id (:session-id fact))
                   (:source-request-id fact) (assoc :episode/request-id (:source-request-id fact)))
        edge (cond-> {:edge/id edge-id*
                      :edge/source [:entity/id subject-id]
                      :edge/target [:entity/id object-id]
                      :edge/predicate (:predicate fact)
                      :edge/observed-at observed-at
                      :edge/valid-from (or (:valid-from fact) observed-at)
                      :edge/source-fact-id (:id fact)
                      :edge/episodes [[:episode/id episode-id*]]}
               (:confidence fact) (assoc :edge/confidence (double (:confidence fact)))
               (:valid-to fact) (assoc :edge/valid-to (:valid-to fact))
               (:invalidated-by fact) (assoc :edge/invalidated-by (:invalidated-by fact))
               (seq (:tags fact)) (assoc :edge/tags (vec (:tags fact))))]
    [(entity-tx db subject-id (:subject fact) (or (:subject-type fact) "entity") observed-at)
     (entity-tx db object-id (:object fact) (or (:object-type fact) "entity") observed-at)
     episode*
     edge]))

(defn- active-at? [as-of include-historical? edge]
  (cond
    include-historical? true
    (str/blank? (or as-of "")) (str/blank? (or (:edge/valid-to edge) ""))
    :else (and (not (pos? (compare (:edge/valid-from edge) as-of)))
               (or (str/blank? (or (:edge/valid-to edge) ""))
                   (pos? (compare (:edge/valid-to edge) as-of))))))

(defn- edge->result [edge]
  (let [source (:edge/source edge)
        target (:edge/target edge)]
    {:id (:edge/id edge)
     :type "fact"
     :subject (:entity/label source)
     :predicate (:edge/predicate edge)
     :object (:entity/label target)
     :source-entity-id (:entity/id source)
     :target-entity-id (:entity/id target)
     :source-fact-id (:edge/source-fact-id edge)
     :confidence (:edge/confidence edge)
     :valid-from (:edge/valid-from edge)
     :valid-to (:edge/valid-to edge)
     :observed-at (:edge/observed-at edge)
     :invalidated-by (:edge/invalidated-by edge)
     :episodes (mapv #(select-keys % [:episode/id :episode/source :episode/session-id :episode/request-id :episode/created-at])
                      (:edge/episodes edge))
     :tags (vec (:edge/tags edge))}))

(defn- edge-target-id [node-id edge]
  (let [source-id (get-in edge [:edge/source :entity/id])
        target-id (get-in edge [:edge/target :entity/id])]
    (cond
      (= node-id source-id) target-id
      (= node-id target-id) source-id
      :else nil)))

(defn- node-label [edge node-id]
  (cond
    (= node-id (get-in edge [:edge/source :entity/id])) (get-in edge [:edge/source :entity/label])
    (= node-id (get-in edge [:edge/target :entity/id])) (get-in edge [:edge/target :entity/label])
    :else nil))

(defn- path-node-label [edges start-id start-label node-id]
  (or (some #(node-label % node-id) edges)
      (when (= node-id start-id) start-label)
      (some #(node-label % node-id) edges)
      node-id))

(defn- path->result [path]
  (let [edges (:edges path)
        start-id (:start-id path)
        node-ids (:node-ids path)
        nodes (mapv (fn [node-id]
                      {:id node-id
                       :label (path-node-label edges start-id (:start-label path) node-id)})
                    node-ids)]
    {:type "path"
     :depth (count edges)
     :nodes nodes
     :edges (mapv edge->result edges)}))

(defn- text-match? [needle result]
  (if (str/blank? needle)
    true
    (some #(str/includes? (str/lower-case (str %)) needle)
          [(:id result)
           (:subject result)
           (:predicate result)
           (:object result)
           (:source-entity-id result)
           (:target-entity-id result)
           (:source-fact-id result)
           (str/join " " (:tags result))])))

(defn- query-edges [db]
  (->> (d/q '[:find (pull ?e [*
                              {:edge/source [*]}
                              {:edge/target [*]}
                              {:edge/episodes [*]}])
              :where
              [?e :edge/id ?id]]
            db)
       (map first)))

(defn- entity-neighborhood [db entity depth as-of include-historical?]
  (let [start (canonical-entity-id db entity)
        max-depth (max 1 (or depth 1))]
    (loop [frontier #{start}
           seen #{start}
           level 0
           results []]
      (if (or (empty? frontier) (>= level max-depth))
        results
        (let [edges (->> (query-edges db)
                         (filter #(active-at? as-of include-historical? %))
                         (filter (fn [edge]
                                   (or (contains? frontier (get-in edge [:edge/source :entity/id]))
                                       (contains? frontier (get-in edge [:edge/target :entity/id]))))))
              next-ids (->> edges
                            (mapcat (fn [edge]
                                      [(get-in edge [:edge/source :entity/id])
                                       (get-in edge [:edge/target :entity/id])]))
                            set)
              next-frontier (set/difference next-ids seen)]
          (recur next-frontier
                 (set/union seen next-frontier)
                 (inc level)
                 (into results edges)))))))

(defn- path-search [db from to max-depth limit as-of include-historical?]
  (let [from-id (canonical-entity-id db from)
        to-id (canonical-entity-id db to)
        max-depth* (max 1 (or max-depth 4))
        limit* (or limit 20)
        active-edges (->> (query-edges db)
                          (filter #(active-at? as-of include-historical? %))
                          vec)]
    (loop [queue [{:node-id from-id
                   :node-ids [from-id]
                   :start-id from-id
                   :start-label from
                   :edges []
                   :seen #{from-id}}]
           results []]
      (cond
        (or (empty? queue) (>= (count results) limit*)) results
        :else
        (let [{:keys [node-id edges seen] :as path} (first queue)
              queue* (subvec (vec queue) 1)]
          (if (and (= node-id to-id) (seq edges))
            (recur queue* (conj results path))
            (let [next-paths (if (>= (count edges) max-depth*)
                               []
                               (->> active-edges
                                    (keep (fn [edge]
                                            (when-let [next-id (edge-target-id node-id edge)]
                                              (when-not (contains? seen next-id)
                                                (assoc path
                                                       :node-id next-id
                                                       :node-ids (conj (:node-ids path) next-id)
                                                       :edges (conj edges edge)
                                                       :seen (conj seen next-id))))))))]
              (recur (into queue* next-paths) results))))))))

(defn- query-mode [opts]
  (keyword (or (:mode opts)
               (when (and (:from opts) (:to opts)) :paths)
               (when (:entity opts) :neighbors)
               :facts)))

(defn- invalidation-tx [db fact new-edge-id observed-at]
  (let [subject-id (canonical-entity-id db (:subject fact))
        object-id (canonical-entity-id db (:object fact))]
    (->> (query-edges db)
         (filter (fn [edge]
                   (and (= subject-id (get-in edge [:edge/source :entity/id]))
                        (= (:predicate fact) (:edge/predicate edge))
                        (not= new-edge-id (:edge/id edge))
                        (not= object-id (get-in edge [:edge/target :entity/id]))
                        (str/blank? (or (:edge/valid-to edge) "")))))
         (mapv (fn [edge]
                 {:db/id (:db/id edge)
                  :edge/valid-to observed-at
                  :edge/invalidated-by new-edge-id})))))

(defn- remove-edge-match? [db fact edge]
  (let [id (:id fact)
        subject (:subject fact)
        predicate (:predicate fact)
        object (:object fact)
        source-id (when subject (canonical-entity-id db subject))
        target-id (when object (canonical-entity-id db object))]
    (and (str/blank? (or (:edge/valid-to edge) ""))
         (if (not (str/blank? (or id "")))
           (or (= id (:edge/id edge))
               (= id (:edge/source-fact-id edge)))
           (and (not (str/blank? (or subject "")))
                (not (str/blank? (or predicate "")))
                (not (str/blank? (or object "")))
                (= source-id (get-in edge [:edge/source :entity/id]))
                (= predicate (:edge/predicate edge))
                (= target-id (get-in edge [:edge/target :entity/id])))))))

(defn- remove-edge-tx [db fact observed-at]
  (let [invalidated-by (or (:invalidated-by fact)
                           (str "removed:" (UUID/randomUUID)))]
    (->> (query-edges db)
         (filter #(remove-edge-match? db fact %))
         (mapv (fn [edge]
                 {:db/id (:db/id edge)
                  :edge/valid-to observed-at
                  :edge/invalidated-by invalidated-by})))))

(defn- remove-all-edge-tx [db observed-at]
  (let [invalidated-by (str "reset:" (UUID/randomUUID))]
    (->> (query-edges db)
         (filter #(str/blank? (or (:edge/valid-to %) "")))
         (mapv (fn [edge]
                 {:db/id (:db/id edge)
                  :edge/valid-to observed-at
                  :edge/invalidated-by invalidated-by})))))

(defn- merge-entity-tx [db canonical aliases observed-at]
  (let [canonical-id (canonical-entity-id db canonical)
        canonical-existing (query-entity-by-normalized db canonical)
        alias-values (vec (distinct (map normalize-entity (conj aliases canonical))))
        alias-entities (->> aliases
                            (map #(or (query-entity-by-normalized db %)
                                      (query-alias db %)))
                            (remove nil?)
                            (remove #(= canonical-id (:entity/id %)))
                            (distinct-by* :db/id))
        alias-dbids (set (map :db/id alias-entities))
        edge-retargets (mapcat
                        (fn [edge]
                          (cond-> []
                            (contains? alias-dbids (get-in edge [:edge/source :db/id]))
                            (conj {:db/id (:db/id edge)
                                   :edge/source [:entity/id canonical-id]})
                            (contains? alias-dbids (get-in edge [:edge/target :db/id]))
                            (conj {:db/id (:db/id edge)
                                   :edge/target [:entity/id canonical-id]})))
                        (query-edges db))
        alias-retractions (mapcat
                           (fn [entity]
                             [[:db/retract (:db/id entity) :entity/id (:entity/id entity)]
                              [:db/retract (:db/id entity) :entity/normalized (:entity/normalized entity)]])
                           alias-entities)
        canonical-tx (if canonical-existing
                       {:db/id (:db/id canonical-existing)
                        :entity/id canonical-id
                        :entity/label canonical
                        :entity/normalized (normalize-entity canonical)
                        :entity/aliases (vec (distinct (concat (:entity/aliases canonical-existing) alias-values aliases [canonical])))
                        :entity/updated-at observed-at}
                       {:entity/id canonical-id
                        :entity/label canonical
                        :entity/normalized (normalize-entity canonical)
                        :entity/type "entity"
                        :entity/aliases (vec (distinct (concat alias-values aliases [canonical])))
                        :entity/updated-at observed-at})]
    (vec (concat [canonical-tx] edge-retargets alias-retractions))))

(defrecord DatahikeGraphBackend [cfg conn]
  agent.memory.core/IGraphMemoryBackend
  (save-fact! [_ fact]
    (let [fact* (merge {:id (str (UUID/randomUUID))
                        :type "fact"
                        :created-at (now)
                        :tags []}
                       fact)
          observed-at (or (:observed-at fact*) (:created-at fact*) (now))
          new-edge-id (edge-id fact*)
          fact** (assoc fact* :observed-at observed-at)
          tx (into [(compact-fact-tx fact**)]
                   (concat (invalidation-tx @conn fact** new-edge-id observed-at)
                           (graph-tx @conn fact**)))]
      (d/transact conn {:tx-data tx})
      fact**))
  (remove-fact! [_ fact]
    (let [observed-at (or (:observed-at fact) (now))
          tx (remove-edge-tx @conn fact observed-at)]
      (when (seq tx)
        (d/transact conn {:tx-data tx}))
      {:id (:id fact)
       :subject (:subject fact)
       :predicate (:predicate fact)
       :object (:object fact)
       :removed-count (count tx)
       :removed? (pos? (count tx))
       :observed-at observed-at}))
  (remove-all-facts! [_]
    (let [observed-at (now)
          tx (remove-all-edge-tx @conn observed-at)]
      (when (seq tx)
        (d/transact conn {:tx-data tx}))
      {:removed-count (count tx)
       :removed? (pos? (count tx))
       :observed-at observed-at}))
  (merge-entities! [_ canonical aliases]
    (let [observed-at (now)
          aliases* (vec (remove str/blank? (map str aliases)))
          tx (merge-entity-tx @conn canonical aliases* observed-at)]
      (d/transact conn {:tx-data tx})
      {:canonical canonical
       :canonical-id (canonical-entity-id @conn canonical)
       :aliases aliases*
       :merged true}))
  (query-facts [_ query opts]
    (let [limit (or (:limit opts) 20)
          db @conn
          needle (some-> query str/lower-case)
          mode (query-mode opts)]
      (case mode
        :paths
        (->> (path-search db (:from opts) (:to opts) (:max-depth opts) limit (:as-of opts) (:include-historical? opts))
             (map path->result)
             vec)
        (:neighbors :facts-by-entity)
        (->> (entity-neighborhood db (:entity opts) (:depth opts) (:as-of opts) (:include-historical? opts))
             (distinct-by* :edge/id)
             (map edge->result)
             (filter #(text-match? needle %))
             (sort-by #(or (:observed-at %) ""))
             reverse
             (take limit)
             vec)
        (:facts :facts-at-time)
        (->> (query-edges db)
             (distinct-by* :edge/id)
             (filter #(active-at? (:as-of opts) (:include-historical? opts) %))
             (map edge->result)
             (filter #(text-match? needle %))
             (sort-by #(or (:observed-at %) ""))
             reverse
             (take limit)
             vec)
        (throw (ex-info "Unsupported graph query mode" {:mode mode})))))
  (graph-facts [_ opts]
    (->> (query-edges @conn)
         (distinct-by* :edge/id)
         (filter #(active-at? (:as-of opts) (:include-historical? opts) %))
         (map edge->result)
         vec))
  (backend-health-check [_]
    (try
      {:healthy true
       :details {:path (backend-path cfg)
                 :fact-count (count (d/q '[:find ?e :where [?e :fact/id _]] @conn))
                 :entity-count (count (d/q '[:find ?e :where [?e :entity/id _]] @conn))
                 :edge-count (count (d/q '[:find ?e :where [?e :edge/id _]] @conn))}}
      (catch Exception e
        {:healthy false
         :details {:path (backend-path cfg)
                   :error (.getMessage e)}})))
  agent.memory.core/IDatalogExplorer
  (datalog-query* [_ query {:keys [args limit] :or {limit 100}}]
    (let [limit* (max 1 (min 500 (or limit 100)))
          rows (apply d/q query @conn (or args []))]
      {:query query
       :args (vec (or args []))
       :limit limit*
       :row-count (count rows)
       :rows (vec (take limit* rows))})))

(defn create-backend
  [cfg]
  (quiet-datahike-logs!)
  (create-db-if-needed! cfg)
  (let [conn (d/connect cfg)]
    (ensure-schema! conn)
    (->DatahikeGraphBackend cfg conn)))
