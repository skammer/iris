(ns agent.memory.core
  "Explicit memory-surface model for rewritten runtime."
  (:require
   [agent.persistence.sqlite :as sqlite]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defprotocol IGraphMemoryBackend
  (save-fact! [this fact])
  (query-facts [this query opts])
  (backend-health-check [this]))

(defrecord NullGraphMemoryBackend []
  IGraphMemoryBackend
  (save-fact! [_ _]
    (throw (ex-info "Graph memory backend is disabled" {:type :graph-memory-disabled})))
  (query-facts [_ _ _] [])
  (backend-health-check [_]
    {:healthy true
     :details {:enabled false}}))

(defn- existing-file [path]
  (let [file (io/file path)]
    (when (.isFile file)
      file)))

(defn- prompt-documents [paths]
  (->> paths
       (map existing-file)
       (remove nil?)
       (map (fn [file]
              {:path (.getAbsolutePath file)
               :content (slurp file)}))
       vec))

(defn- create-graph-backend [{:keys [enabled backend datahike]}]
  (if (not enabled)
    (->NullGraphMemoryBackend)
    (case backend
      :datahike
      ((requiring-resolve 'agent.memory.datahike/create-backend)
       {:store {:backend :file
                :path (:path datahike)}
        :keep-history? (not= false (:keep-history? datahike))
        :schema-flexibility :write})
      (throw (ex-info "Unsupported graph memory backend" {:backend backend})))))

(defn create-memory-service
  [{:keys [prompt search graph] :as cfg} store]
  {:config cfg
   :prompt-paths (vec (get prompt :paths ["MEMORY.md"]))
   :search-default-limit (get search :default-limit 20)
   :graph-backend (create-graph-backend graph)
   :store store})

(defn list-surfaces
  [memory-service]
  [{:name :prompt
    :type :file
    :writable false
    :paths (:prompt-paths memory-service)}
   {:name :search
    :type :sqlite
    :writable false
    :default-limit (:search-default-limit memory-service)}
   {:name :graph
    :type (get-in memory-service [:config :graph :backend] :none)
    :writable true
    :enabled (true? (get-in memory-service [:config :graph :enabled]))}])

(defn read-prompt-memory
  [memory-service]
  (let [docs (prompt-documents (:prompt-paths memory-service))]
    {:documents docs
     :combined (str/join "\n\n" (map :content docs))}))

(defn search-memory
  ([memory-service query] (search-memory memory-service query {}))
  ([memory-service query opts]
   (let [limit (or (:limit opts) (:search-default-limit memory-service))
         messages (sqlite/search-messages (:store memory-service) query {:limit limit})
         events (sqlite/search-events (:store memory-service) query {:limit limit})]
     {:query query
      :messages messages
      :events events})))

(defn save-graph-fact!
  [memory-service fact]
  (save-fact! (:graph-backend memory-service) fact))

(defn query-graph-memory
  ([memory-service query] (query-graph-memory memory-service query {}))
  ([memory-service query opts]
   (query-facts (:graph-backend memory-service) query opts)))

(defn health-check
  [memory-service]
  (let [prompt (prompt-documents (:prompt-paths memory-service))]
    {:healthy true
     :prompt {:document-count (count prompt)
              :paths (mapv :path prompt)}
     :search {:healthy true
              :default-limit (:search-default-limit memory-service)}
     :graph (backend-health-check (:graph-backend memory-service))}))
