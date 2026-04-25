(ns agent.memory.core
  "Explicit memory-surface model for rewritten runtime."
  (:require
   [agent.llm.core :as llm]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set]
   [clojure.string :as str]))

(defprotocol IGraphMemoryBackend
  (save-fact! [this fact])
  (query-facts [this query opts])
  (backend-health-check [this]))

(declare save-graph-fact! query-graph-memory)

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

(defn- canonical-path [path]
  (.getCanonicalPath (io/file path)))

(defn- canonical-roots [paths]
  (mapv canonical-path paths))

(defn- within-root? [roots path]
  (let [target (canonical-path path)]
    (some #(or (= target %)
               (str/starts-with? target (str % java.io.File/separator)))
          roots)))

(defn- ensure-vault-path! [memory-service path]
  (let [vault-roots (:vault-roots memory-service)
        fs-roots (:fs-roots memory-service)
        target (canonical-path path)]
    (when-not (seq vault-roots)
      (throw (ex-info "Vault memory is not configured" {:type :vault-memory-disabled})))
    (when-not (within-root? vault-roots target)
      (throw (ex-info "Path is outside configured memory vault roots"
                      {:type :path-not-allowed
                       :path target
                       :roots vault-roots})))
    (when (and (seq fs-roots) (not (within-root? fs-roots target)))
      (throw (ex-info "Path is outside filesystem policy roots"
                      {:type :path-not-allowed
                       :path target
                       :roots fs-roots})))
    target))

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

(defn- token-set [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

(defn- jaccard [left right]
  (let [a (token-set left)
        b (token-set right)
        union-count (count (clojure.set/union a b))]
    (if (zero? union-count)
      0.0
      (/ (double (count (clojure.set/intersection a b))) union-count))))

(defn- fact-text [fact]
  (str (:subject fact) " " (:predicate fact) " " (:object fact)))

(defn- similar-duplicate [memory-service fact opts]
  (when-let [threshold (get-in memory-service [:config :facts :dedup :similarity-threshold])]
    (let [candidates (sqlite/search-memory-facts (:store memory-service)
                                                 nil
                                                 (assoc opts :limit 1000 :include-global? false))
          fact* (fact-text fact)]
      (some (fn [candidate]
              (when (>= (jaccard fact* (fact-text candidate)) threshold)
                candidate))
            candidates))))

(defn create-memory-service
  [{:keys [prompt search graph vault fs-roots] :as cfg} store]
  {:config cfg
   :prompt-paths (vec (get prompt :paths ["MEMORY.md"]))
   :search-default-limit (get search :default-limit 20)
   :vault-roots (canonical-roots (get vault :paths []))
   :vault-writable? (true? (:writable? vault))
   :fs-roots (canonical-roots (or fs-roots []))
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
   {:name :facts
    :type :sqlite
    :writable true
    :default-limit (:search-default-limit memory-service)}
   {:name :graph
    :type (get-in memory-service [:config :graph :backend] :none)
    :writable true
    :enabled (true? (get-in memory-service [:config :graph :enabled]))}
   {:name :vault
    :type :file
    :writable (:vault-writable? memory-service)
    :enabled (seq (:vault-roots memory-service))
    :paths (:vault-roots memory-service)}])

(defn read-prompt-memory
  [memory-service]
  (let [docs (prompt-documents (:prompt-paths memory-service))]
    {:documents docs
     :combined (str/join "\n\n" (map :content docs))}))

(defn search-memory
  ([memory-service query] (search-memory memory-service query {}))
  ([memory-service query opts]
   (let [limit (or (:limit opts) (:search-default-limit memory-service))
         messages (sqlite/search-messages (:store memory-service)
                                          query
                                          {:limit limit
                                           :session-id (:session-id opts)})
         events (sqlite/search-events (:store memory-service)
                                      query
                                      {:limit limit
                                       :entity-type (:entity-type opts)
                                       :entity-id (:entity-id opts)})
         facts (sqlite/search-memory-facts (:store memory-service)
                                           query
                                           (merge {:limit limit} opts))
         graph (try
                 (query-graph-memory memory-service query {:limit limit})
                 (catch Exception _ []))]
     {:query query
      :messages messages
      :events events
      :facts facts
      :graph graph})))

(defn save-memory-fact!
  ([memory-service fact] (save-memory-fact! memory-service fact {}))
  ([memory-service fact opts]
   (let [fact* (merge opts fact)
         saved (if-let [duplicate (similar-duplicate memory-service fact opts)]
                 (sqlite/merge-memory-fact-source! (:store memory-service) duplicate fact*)
                 (sqlite/save-memory-fact! (:store memory-service) fact*))]
     (when (true? (get-in memory-service [:config :graph :enabled]))
       (try
         (save-graph-fact! memory-service
                           (merge fact*
                                  {:id (:id saved)
                                   :session-id (:source-session-id saved)}))
         (catch Exception _ nil)))
     (sqlite/log-event! (:store memory-service)
                        {:event-type :memory.fact.saved
                         :entity-type :memory
                         :entity-id (:id saved)
                         :request-id (:source-request-id saved)
                         :payload {:fact-id (:id saved)
                                   :created? (:created? saved)
                                   :scope (:scope saved)
                                   :subject (:subject saved)
                                   :predicate (:predicate saved)
                                   :source-session-id (:source-session-id saved)}})
     saved)))

(defn search-facts
  ([memory-service query] (search-facts memory-service query {}))
  ([memory-service query opts]
   (sqlite/search-memory-facts (:store memory-service)
                               query
                               (merge {:limit (:search-default-limit memory-service)}
                                      opts))))

(defn read-vault-file
  [memory-service path]
  (let [path* (ensure-vault-path! memory-service path)
        file (io/file path*)]
    (when-not (.isFile file)
      (throw (ex-info "Vault file not found" {:type :not-found :path path*})))
    {:path path*
     :content (slurp file)}))

(defn write-vault-file!
  [memory-service path content]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [path* (ensure-vault-path! memory-service path)
        file (io/file path*)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file (or content ""))
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.vault.written
                        :entity-type :memory
                        :entity-id path*
                        :payload {:path path*
                                  :bytes (alength (.getBytes (or content "") "UTF-8"))}})
    {:path path*
     :written true}))

(defn- extraction-schema []
  {:type "object"
   :additionalProperties false
   :properties {:facts {:type "array"
                        :items {:type "object"
                                :additionalProperties false
                                :properties {:subject {:type "string"}
                                             :predicate {:type "string"}
                                             :object {:type "string"}
                                             :scope {:type "string"
                                                     :enum ["global" "session" "agent"]}
                                             :confidence {:type "number"}}
                                :required ["subject" "predicate" "object"]}}}
   :required ["facts"]})

(defn- parse-fact-response [content]
  (let [value (cond
                (map? content) content
                (str/blank? (or content "")) {}
                :else (json/parse-string content true))
        facts (if (vector? value) value (:facts value))]
    (->> facts
         (filter map?)
         (filter #(every? (fn [k] (string? (get % k))) [:subject :predicate :object]))
         (mapv #(select-keys % [:subject :predicate :object :scope :confidence])))))

(defn extract-facts
  [provider {:keys [user-message assistant-message model]}]
  (let [response (llm/invoke
                  provider
                  {:model model
                   :temperature 0.0
                   :structured-output {:name "memory_facts"
                                       :strict? true
                                       :schema (extraction-schema)}
                   :messages [{:role "system"
                               :content (str "Extract durable memory facts from the exchange. "
                                             "Keep stable user preferences, profile, projects, decisions, constraints. "
                                             "Skip transient chat details, secrets, credentials, and unsupported guesses. "
                                             "Return JSON only.")}
                              {:role "user"
                               :content (json/generate-string
                                         {:user user-message
                                          :assistant assistant-message})}]})]
    (parse-fact-response (:content response))))

(defn extract-and-save-facts!
  [memory-service provider exchange opts]
  (let [extractor (get-in memory-service [:config :facts :extractor])]
    (if (false? (:enabled extractor))
      []
      (try
        (let [model (or (:model extractor) (:model opts))
              facts (extract-facts provider (assoc exchange :model model))]
          (mapv (fn [fact]
                  (let [scope-type (or (:scope fact)
                                       (name (or (get-in memory-service [:config :facts :default-scope])
                                                 :session)))]
                    (save-memory-fact! memory-service
                                       (dissoc fact :scope)
                                       (merge opts
                                              {:scope {:type scope-type
                                                       :id (case scope-type
                                                             "session" (:session-id opts)
                                                             "agent" (:agent-id opts)
                                                             nil)}}))))
                facts))
        (catch Exception e
          (sqlite/log-event! (:store memory-service)
                             {:event-type :memory.fact.extraction_failed
                              :entity-type :session
                              :entity-id (:session-id opts)
                              :request-id (:source-request-id opts)
                              :payload {:message (.getMessage e)}})
          [])))))

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
     :facts {:healthy true
             :count (sqlite/count-memory-facts (:store memory-service))}
     :vault {:healthy true
             :paths (:vault-roots memory-service)
             :writable (:vault-writable? memory-service)}
     :graph (backend-health-check (:graph-backend memory-service))}))
