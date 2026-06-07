(ns agent.memory.core
  "Memory service facade. SQLite facts are the durable store; prompt and vault
   files are bounded filesystem surfaces."
  (:require
   [agent.llm.core :as llm]
   [agent.memory.schema :as memory-schema]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def default-search-limit 10)
(def default-min-search-score 0.3)

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

(defn- token-set [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

(defn- jaccard [left right]
  (let [a (token-set left)
        b (token-set right)
        union-count (count (set/union a b))]
    (if (zero? union-count)
      0.0
      (/ (double (count (set/intersection a b))) union-count))))

(defn- fact-text [fact]
  (str (:subject fact) " " (:predicate fact) " " (:object fact)))

(defn- contains-query-score [query text]
  (let [query* (str/lower-case (str/trim (or query "")))
        text* (str/lower-case (or text ""))]
    (cond
      (str/blank? query*) 0.0
      (str/includes? text* query*) 1.0
      :else 0.0)))

(defn- confidence-score [value]
  (if (number? value)
    (max 0.0 (min 1.0 (double value)))
    0.5))

(defn- item-text [surface item]
  (case surface
    :message (:content item)
    :event (json/generate-string (:payload item))
    :fact (fact-text item)
    ""))

(defn- score-memory-item [query surface item]
  (let [text (item-text surface item)
        lexical (jaccard query text)
        exact (contains-query-score query text)
        confidence (confidence-score (:confidence item))
        surface-weight (case surface
                         :fact 1.1
                         :message 1.0
                         :event 0.8
                         1.0)
        score (* surface-weight
                 (+ (* 0.65 lexical)
                    (* 0.25 exact)
                    (* 0.10 confidence)))]
    {:surface surface
     :score score
     :score-breakdown {:lexical lexical
                       :exact exact
                       :confidence confidence
                       :surface-weight surface-weight}
     :item item}))

(defn- dedupe-ranked-results [ranked]
  (second
   (reduce (fn [[seen results] {:keys [surface item] :as scored}]
             (let [value (-> (item-text surface item)
                             str/lower-case
                             (str/replace #"\s+" " ")
                             str/trim)
                   key (if (str/blank? value)
                         (pr-str item)
                         value)]
               (if (contains? seen key)
                 [seen results]
                 [(conj seen key) (conj results scored)])))
           [#{} []]
           ranked)))

(defn- rank-memory-results
  [query results opts]
  (let [limit (or (:limit opts) 20)
        min-score (or (:min-score opts) 0.0)
        dedupe? (not= false (:dedupe? opts))]
    (->> (concat
          (map #(score-memory-item query :message %) (:messages results))
          (map #(score-memory-item query :event %) (:events results))
          (map #(score-memory-item query :fact %) (:facts results)))
         (sort-by :score >)
         (filter #(and (pos? (:score %)) (>= (:score %) min-score)))
         (#(if dedupe? (dedupe-ranked-results %) %))
         (take limit)
         vec)))

(defn- positive-limit [value fallback]
  (if (and (integer? value) (pos? value))
    value
    fallback))

(defn- search-limit-config [search]
  (let [configured-default (positive-limit (get search :default-limit) default-search-limit)
        configured-max (positive-limit (get search :max-limit) configured-default)]
    {:default-limit (min configured-default configured-max)
     :max-limit configured-max}))

(defn- search-min-score-config [search]
  (let [score (:min-score search)]
    (if (number? score)
      (max 0.0 (double score))
      default-min-search-score)))

(defn- effective-search-limit [memory-service requested]
  (min (positive-limit requested (:search-default-limit memory-service))
       (:search-max-limit memory-service)))

(defn- similar-duplicate [memory-service fact opts]
  (when-let [threshold (get-in memory-service [:config :facts :dedup :similarity-threshold])]
    (let [scope (memory-schema/normalize-scope-option opts)
          candidates (sqlite/search-memory-facts (:store memory-service)
                                                 nil
                                                 {:limit 1000
                                                  :scope scope
                                                  :include-global? false})
          fact* (fact-text fact)]
      (some (fn [candidate]
              (when (>= (jaccard fact* (fact-text candidate)) threshold)
                candidate))
            candidates))))

(defn create-memory-service
  [{:keys [prompt search vault fs-roots] :as cfg} store]
  (let [{:keys [default-limit max-limit]} (search-limit-config search)]
    {:config cfg
     :prompt-paths (vec (get prompt :paths ["MEMORY.md"]))
     :search-default-limit default-limit
     :search-max-limit max-limit
     :search-min-score (search-min-score-config search)
     :vault-roots (canonical-roots (get vault :paths []))
     :vault-writable? (true? (:writable? vault))
     :fs-roots (canonical-roots (or fs-roots []))
     :store store}))

(defn list-surfaces
  [memory-service]
  [{:name :prompt
    :type :file
    :writable false
    :paths (:prompt-paths memory-service)}
   {:name :search
    :type :sqlite
    :writable false
    :default-limit (:search-default-limit memory-service)
    :max-limit (:search-max-limit memory-service)
    :min-score (:search-min-score memory-service)}
   {:name :facts
    :type :sqlite
    :writable true
    :default-limit (:search-default-limit memory-service)
    :max-limit (:search-max-limit memory-service)}
   {:name :vault
    :type :file
    :writable (:vault-writable? memory-service)
    :enabled (boolean (seq (:vault-roots memory-service)))
    :paths (:vault-roots memory-service)}])

(defn read-prompt-memory
  [memory-service]
  (let [docs (prompt-documents (:prompt-paths memory-service))]
    {:documents docs
     :combined (str/join "\n\n" (map :content docs))}))

(defn search-memory
  ([memory-service query] (search-memory memory-service query {}))
  ([memory-service query opts]
   (let [limit (effective-search-limit memory-service (:limit opts))
         min-score (or (:min-score opts) (:search-min-score memory-service))
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
         results {:query query
                  :messages messages
                  :events events
                  :facts facts}]
     (assoc results :ranked (rank-memory-results query results {:limit limit
                                                                :min-score min-score
                                                                :dedupe? (:dedupe? opts)})))))

(defn save-memory-fact!
  ([memory-service fact] (save-memory-fact! memory-service fact {}))
  ([memory-service fact opts]
   (memory-schema/validate-fact! fact)
   (let [fact* (merge opts fact)
         saved (if-let [duplicate (similar-duplicate memory-service fact opts)]
                 (sqlite/merge-memory-fact-source! (:store memory-service) duplicate fact*)
                 (sqlite/save-memory-fact! (:store memory-service) fact*))]
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

(defn remove-memory-fact!
  ([memory-service fact] (remove-memory-fact! memory-service fact {}))
  ([memory-service fact opts]
   (memory-schema/validate-fact-selector! fact)
   (let [fact* (merge opts fact)
         removed (sqlite/remove-memory-fact! (:store memory-service) fact*)]
     (sqlite/log-event! (:store memory-service)
                        {:event-type :memory.fact.removed
                         :entity-type :memory
                         :entity-id (or (:id fact*) (:id removed))
                         :request-id (:source-request-id fact*)
                         :payload {:id (or (:id fact*) (:id removed))
                                   :removed? (:removed? removed)
                                   :removed-count (:removed-count removed)
                                   :scope (:scope removed)
                                   :subject (:subject fact*)
                                   :predicate (:predicate fact*)
                                   :object (:object fact*)}})
     removed)))

(defn reset-facts! [memory-service]
  (let [result (sqlite/reset-memory-facts! (:store memory-service))]
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.facts.reset
                        :entity-type :memory
                        :entity-id "facts"
                        :payload result})
    result))

(defn search-facts
  ([memory-service query] (search-facts memory-service query {}))
  ([memory-service query opts]
   (sqlite/search-memory-facts (:store memory-service)
                               query
                               (assoc opts :limit (effective-search-limit memory-service (:limit opts))))))

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
        file (io/file path*)
        content* (or content "")]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (spit file content*)
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.vault.written
                        :entity-type :memory
                        :entity-id path*
                        :payload {:path path*
                                  :bytes (alength (.getBytes content* "UTF-8"))}})
    {:path path*
     :written true}))

(defn- extraction-schema []
  {:type "object"
   :additionalProperties false
   :properties {:facts {:type "array"
                        :items {:type "object"
                                :additionalProperties false
                                :properties {:subject {:type "string"
                                                       :description "Stable entity the fact is about, for example user, team, current session, or a named project."}
                                             :predicate {:type "string"
                                                         :description "Short relationship or preference phrase, for example prefers, uses, decided, works on, requires."}
                                             :object {:type "string"
                                                      :description "Durable value of the fact. Do not include secrets, credentials, transient chat details, or unsupported guesses."}
                                             :scope {:type "string"
                                                     :enum ["global" "session" "agent"]
                                                     :description "global for durable cross-session user/team facts, session for temporary session context, agent for agent-specific behavior."}
                                             :confidence {:type "number"
                                                          :description "Confidence from 0.0 to 1.0 that this is a durable supported memory fact."}}
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

(defn- extractor-format [extractor]
  (let [format (or (:format extractor) :json-schema)]
    (case format
      (:json-schema "json-schema") :json-schema
      (:json-object "json-object") :json-object
      (throw (ex-info "Unsupported memory fact extractor format"
                      {:type :unsupported-fact-extractor-format
                       :format format
                       :allowed [:json-schema :json-object]})))))

(defn- output-options [extractor]
  (case (extractor-format extractor)
    :json-schema
    {:structured-output {:name "memory_facts"
                         :strict? (not (false? (:strict? extractor)))
                         :schema (extraction-schema)}}

    :json-object
    {:response-format {:type "json_object"}}))

(defn extract-facts
  [provider {:keys [user-message assistant-message model session-id extractor]}]
  (let [response (llm/invoke
                  provider
                  (merge
                   {:model model
                    :session-id session-id
                    :temperature 0.0
                    :messages [{:role "system"
                                :content (prompts/load-prompt "fact-extraction")}
                               {:role "user"
                                :content (json/generate-string
                                          {:user user-message
                                           :assistant assistant-message})}]}
                   (output-options extractor)))]
    (parse-fact-response (:content response))))

(defn extract-and-save-facts!
  [memory-service provider exchange opts]
  (let [extractor (get-in memory-service [:config :facts :extractor])]
    (if (false? (:enabled extractor))
      []
      (try
        (let [model (or (:model extractor) (:model opts))
              facts (extract-facts provider (assoc exchange
                                                   :model model
                                                   :session-id (:session-id opts)
                                                   :extractor extractor))]
          (mapv (fn [fact]
                  (let [scope-type (or (:scope fact)
                                       (name (or (get-in memory-service [:config :facts :default-scope])
                                                 :session)))
                        scope (memory-schema/normalize-scope
                               {:type scope-type
                                :id (case scope-type
                                      "session" (:session-id opts)
                                      "agent" (:agent-id opts)
                                      nil)})]
                    (save-memory-fact! memory-service
                                       (dissoc fact :scope)
                                       (merge opts
                                              {:episode-content (json/generate-string exchange)
                                               :scope scope}))))
                facts))
        (catch Exception e
          (sqlite/log-event! (:store memory-service)
                             {:event-type :memory.fact.extraction_failed
                              :entity-type :session
                              :entity-id (:session-id opts)
                              :request-id (:source-request-id opts)
                              :payload {:message (.getMessage e)}})
          [])))))

(defn health-check
  [memory-service]
  (let [prompt (prompt-documents (:prompt-paths memory-service))]
    {:healthy true
     :prompt {:document-count (count prompt)
              :paths (mapv :path prompt)}
     :search {:healthy true
              :default-limit (:search-default-limit memory-service)
              :max-limit (:search-max-limit memory-service)}
     :facts {:healthy true
             :count (sqlite/count-memory-facts (:store memory-service))}
     :vault {:healthy true
             :paths (:vault-roots memory-service)
             :writable (:vault-writable? memory-service)}}))
