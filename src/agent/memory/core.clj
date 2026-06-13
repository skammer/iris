(ns agent.memory.core
  "Memory service facade. Reads vault
   files from bounded roots, recalls relevant context for chat, and extracts
   candidate vault notes from completed turns."
  (:require
   [agent.llm.core :as llm]
   [agent.memory.scratchpad :as scratchpad]
   [agent.memory.vault :as vault]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def default-search-limit 10)
(def default-min-search-score 0.3)
(def ^:private allowed-vault-statuses #{"candidate" "approved" "auto_session" "rejected" "superseded"})
(def ^:private allowed-vault-scopes #{"global" "session" "agent" "project"})
(def ^:private allowed-vault-move-roots #{"inbox" "preferences" "decisions" "projects"
                                           "runbooks" "sessions" "references" "archive"})

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

(defn- vault-root-for-path [memory-service path]
  (let [target (canonical-path path)]
    (some #(when (or (= target %)
                     (str/starts-with? target (str % java.io.File/separator)))
             %)
          (:vault-roots memory-service))))

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
    ""))

(defn- score-memory-item [query surface item]
  (let [text (item-text surface item)
        lexical (jaccard query text)
        exact (contains-query-score query text)
        confidence (confidence-score (:confidence item))
        surface-weight (case surface
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
          (map #(score-memory-item query :event %) (:events results)))
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

(defn create-memory-service
  [{:keys [search vault fs-roots] :as cfg} store]
  (let [{:keys [default-limit max-limit]} (search-limit-config search)]
    {:config cfg
     :search-default-limit default-limit
     :search-max-limit max-limit
     :search-min-score (search-min-score-config search)
     :vault-roots (canonical-roots (get vault :paths []))
     :vault-writable? (true? (:writable? vault))
     :fs-roots (canonical-roots (or fs-roots []))
     :store store}))

(defn list-surfaces
  [memory-service]
  [{:name :search
    :type :sqlite
    :writable false
    :default-limit (:search-default-limit memory-service)
    :max-limit (:search-max-limit memory-service)
    :min-score (:search-min-score memory-service)}
   {:name :vault
    :type :file
    :writable (:vault-writable? memory-service)
    :enabled (boolean (seq (:vault-roots memory-service)))
    :paths (:vault-roots memory-service)}])

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
         results {:query query
                  :messages messages
                  :events events}]
     (assoc results :ranked (rank-memory-results query results {:limit limit
                                                                :min-score min-score
                                                                :dedupe? (:dedupe? opts)})))))

(defn reindex-vault!
  [memory-service]
  (vault/reindex! memory-service))

(defn search-vault
  ([memory-service query] (search-vault memory-service query {}))
  ([memory-service query opts]
   (sqlite/search-vault-chunks (:store memory-service)
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

(defn- normalize-vault-iris-change [{:keys [scope status]}]
  (let [scope* (some-> scope name)
        status* (some-> status name)]
    (when (and scope* (not (allowed-vault-scopes scope*)))
      (throw (ex-info "Unsupported vault note scope"
                      {:type :invalid-vault-note-scope
                       :scope scope*
                       :allowed allowed-vault-scopes})))
    (when (and status* (not (allowed-vault-statuses status*)))
      (throw (ex-info "Unsupported vault note status"
                      {:type :invalid-vault-note-status
                       :status status*
                       :allowed allowed-vault-statuses})))
    (cond-> {}
      scope* (assoc :scope scope*)
      status* (assoc :status status*))))

(defn update-vault-note-iris!
  [memory-service path changes]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [path* (ensure-vault-path! memory-service path)
        changes* (normalize-vault-iris-change changes)
        result (vault/update-note-iris! path* changes*)]
    (reindex-vault! memory-service)
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.vault.note_updated
                        :entity-type :memory
                        :entity-id path*
                        :payload result})
    result))

(defn- safe-vault-folder [folder]
  (let [folder* (str/replace (str/trim (or folder "")) #"\\" "/")
        parts (remove str/blank? (str/split folder* #"/"))
        top (first parts)]
    (when (or (str/blank? folder*)
              (str/starts-with? folder* "/")
              (some #(#{"." ".."} %) parts)
              (not (contains? allowed-vault-move-roots top)))
      (throw (ex-info "Unsupported vault note target folder"
                      {:type :invalid-vault-note-folder
                       :folder folder
                       :allowed allowed-vault-move-roots})))
    (str/join java.io.File/separator parts)))

(defn move-vault-note!
  [memory-service path folder]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [source-path (ensure-vault-path! memory-service path)
        root (or (vault-root-for-path memory-service source-path)
                 (throw (ex-info "Path is outside configured memory vault roots"
                                 {:type :path-not-allowed
                                  :path source-path
                                  :roots (:vault-roots memory-service)})))
        folder* (safe-vault-folder folder)
        target-path (.getCanonicalPath (io/file root folder* (.getName (io/file source-path))))]
    (ensure-vault-path! memory-service target-path)
    (let [result (vault/move-note! source-path target-path)]
      (reindex-vault! memory-service)
      (sqlite/log-event! (:store memory-service)
                         {:event-type :memory.vault.note_moved
                          :entity-type :memory
                          :entity-id (:path result)
                          :payload result})
      result)))

(defn- effective-scratchpad-scope [scope opts]
  (scratchpad/normalize-scope
   (or scope
       (when-let [session-id (:session-id opts)]
         {:type :session :id session-id})
       {:type :global})))

(defn read-scratchpad
  ([memory-service] (read-scratchpad memory-service {}))
  ([memory-service opts]
   (scratchpad/read-scratchpad memory-service (effective-scratchpad-scope (:scope opts) opts))))

(defn search-scratchpad
  [memory-service query opts]
  (scratchpad/search-scratchpad memory-service
                                (effective-scratchpad-scope (:scope opts) opts)
                                query))

(defn replace-scratchpad!
  [memory-service {:keys [old-text new-text expected-revision scope] :as opts}]
  (when-not (:vault-writable? memory-service)
    (throw (ex-info "Vault memory is read-only" {:type :vault-read-only})))
  (let [scope* (effective-scratchpad-scope scope opts)
        result (scratchpad/replace-scratchpad! memory-service
                                               scope*
                                               (or old-text "")
                                               (or new-text "")
                                               expected-revision)]
    (sqlite/log-event! (:store memory-service)
                       {:event-type :memory.scratchpad.replaced
                        :entity-type :memory
                        :entity-id (:path result)
                        :payload (select-keys result [:scope :path :revision :previous-revision])})
    result))

(defn- extraction-schema []
  {:type "object"
   :additionalProperties false
   :properties {:notes {:type "array"
                        :items {:type "object"
                                :additionalProperties false
                                :properties {:type {:type "string"
                                                    :description "OKF note type, for example Preference, Decision, ProjectNote, Runbook, or Reference."}
                                             :title {:type "string"
                                                     :description "Short stable note title."}
                                             :description {:type "string"
                                                           :description "One sentence summary."}
                                             :body {:type "string"
                                                    :description "Concise Markdown body. Do not include secrets, credentials, transient chat details, or unsupported guesses."}
                                             :tags {:type "array"
                                                    :items {:type "string"}}
                                             :scope {:type "string"
                                                     :enum ["global" "session" "agent" "project"]
                                                     :description "Scope the candidate note would belong to after review."}
                                             :confidence {:type "number"
                                                          :description "Confidence from 0.0 to 1.0 that this is durable supported memory."}}
                                :required ["type" "title" "description" "body"]}}}
   :required ["notes"]})

(defn- parse-note-response [content]
  (let [value (cond
                (map? content) content
                (str/blank? (or content "")) {}
                :else (json/parse-string content true))
        notes (if (vector? value) value (:notes value))]
    (->> notes
         (filter map?)
         (filter #(every? (fn [k] (string? (get % k))) [:title :description :body]))
         (mapv (fn [note]
                 (-> note
                     (select-keys [:type :title :description :body :tags :scope :confidence])
                     (update :type #(or % "Reference"))
                     (update :tags #(vec (or % [])))))))))

(defn- extractor-format [extractor]
  (let [format (or (:format extractor) :json-schema)]
    (case format
      (:json-schema "json-schema") :json-schema
      (:json-object "json-object") :json-object
      (throw (ex-info "Unsupported memory note extractor format"
                      {:type :unsupported-note-extractor-format
                       :format format
                       :allowed [:json-schema :json-object]})))))

(defn- output-options [extractor]
  (case (extractor-format extractor)
    :json-schema
    {:structured-output {:name "memory_notes"
                         :strict? (not (false? (:strict? extractor)))
                         :schema (extraction-schema)}}

    :json-object
    {:response-format {:type "json_object"}}))

(defn extract-notes
  [provider {:keys [user-message assistant-message model session-id extractor]}]
  (let [response (llm/invoke
                  provider
                  (merge
                   {:model model
                    :session-id session-id
                    :temperature 0.0
                    :messages [{:role "system"
                                :content (prompts/load-prompt "note-extraction")}
                               {:role "user"
                                :content (json/generate-string
                                          {:user user-message
                                           :assistant assistant-message})}]}
                   (output-options extractor)))]
    (parse-note-response (:content response))))

(defn- note-origin [opts message-id]
  (cond-> {:type "message"}
    (:session-id opts) (assoc :session-id (:session-id opts))
    message-id (assoc :message-id message-id)
    (:source-request-id opts) (assoc :request-id (:source-request-id opts))))

(defn- note-origins [opts]
  (let [[user-message-id assistant-message-id] (:source-message-ids opts)]
    (cond-> [{:type "extraction"
              :session-id (:session-id opts)
              :request-id (:source-request-id opts)}]
      user-message-id (conj (note-origin opts user-message-id))
      assistant-message-id (conj (note-origin opts assistant-message-id)))))

(defn- note-scope [memory-service note]
  (let [scope (or (:scope note)
                  (name (or (get-in memory-service [:config :notes :default-scope])
                            :session)))]
    (if (#{"global" "session" "agent" "project"} scope)
      scope
      "session")))

(defn extract-and-save-notes!
  [memory-service provider exchange opts]
  (let [extractor (get-in memory-service [:config :notes :extractor])]
    (if (false? (:enabled extractor))
      []
      (try
        (let [model (or (:model extractor) (:model opts))
              notes (extract-notes provider (assoc exchange
                                                   :model model
                                                   :session-id (:session-id opts)
                                                   :extractor extractor))
              saved (mapv (fn [note]
                            (vault/write-candidate-note!
                             memory-service
                             (merge note
                                    {:scope (note-scope memory-service note)
                                     :origins (note-origins opts)
                                     :source-request-id (:source-request-id opts)
                                     :evidence {:user (:user-message exchange)
                                                :assistant (:assistant-message exchange)}})))
                          notes)]
          (when (seq saved)
            (reindex-vault! memory-service)
            (sqlite/log-event! (:store memory-service)
                               {:event-type :memory.notes.extracted
                                :entity-type :session
                                :entity-id (:session-id opts)
                                :request-id (:source-request-id opts)
                                :payload {:note-count (count saved)
                                          :paths (mapv :path saved)}}))
          saved)
        (catch Exception e
          (sqlite/log-event! (:store memory-service)
                             {:event-type :memory.notes.extraction_failed
                              :entity-type :session
                              :entity-id (:session-id opts)
                              :request-id (:source-request-id opts)
                              :payload {:message (.getMessage e)}})
          [])))))

(defn health-check
  [memory-service]
  {:healthy true
   :search {:healthy true
            :default-limit (:search-default-limit memory-service)
            :max-limit (:search-max-limit memory-service)}
   :vault {:healthy true
           :paths (:vault-roots memory-service)
           :writable (:vault-writable? memory-service)
           :note-count (sqlite/count-vault-notes (:store memory-service))
           :chunk-count (sqlite/count-vault-chunks (:store memory-service))}})
