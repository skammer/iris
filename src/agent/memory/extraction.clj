(ns agent.memory.extraction
  "Bounded session extraction and conversion into reviewable note proposals."
  (:require
   [agent.llm.core :as llm]
   [agent.memory.vault :as vault]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.set :as set]
   [clojure.string :as str]
   [agent.memory.notes :as notes]
   [agent.memory.policy :as policy]
   [agent.memory.proposals :as proposals]
   [agent.memory.search :as search]))

(defn- extraction-schema []
  {:type "object"
   :additionalProperties false
   :properties {:notes {:type "array"
                        :items {:type "object"
                                :additionalProperties false
                                :properties {:operation {:type "string"
                                                         :enum ["create" "update"]
                                                         :description "Create a new memory note or propose an update to an approved note."}
                                             :target_id {:type ["string" "null"]
                                                         :description "Required for update; id of an existing approved note."}
                                             :expected_revision {:type ["string" "null"]
                                                                 :description "Required for update; revision supplied in existing_notes."}
                                             :type {:type "string"
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
                                                          :description "Confidence from 0.0 to 1.0 that this is durable supported memory."}
                                             :evidence {:type "string"
                                                        :description "Short decisive evidence for this note. Cite relevant message or event ids; never copy the full transcript."}}
                                :required ["operation" "type" "title" "description" "body"]}}}
   :required ["notes"]})

(defn- parse-note-response [content]
  (let [value (cond
                (map? content) content
                (str/blank? (or content "")) {}
                :else (json/parse-string content true))
        notes (if (vector? value) value (:notes value))]
    (->> notes
         (filter map?)
         (filter #(and (#{"create" "update"} (:operation %))
                       (every? (fn [k] (string? (get % k)))
                               [:title :description :body])))
         (mapv (fn [note]
                 (-> note
                     (select-keys [:operation :target_id :expected_revision
                                   :type :title :description :body :tags :scope :confidence
                                   :evidence])
                     (set/rename-keys {:target_id :target-id
                                       :expected_revision :expected-revision})
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
  [provider {:keys [user-message assistant-message existing-notes model session-id extractor]}]
  (let [prompt-name (name (or (:prompt extractor) "note-extraction"))
        response (llm/invoke
                  provider
                  (merge
                   {:model model
                    :session-id session-id
                    :temperature 0.0
                    :messages [{:role "system"
                                :content (prompts/load-prompt prompt-name)}
                               {:role "user"
                                :content (json/generate-string
                                          {:user user-message
                                           :assistant assistant-message
                                           :existing_notes (vec (or existing-notes []))})}]}
                   (output-options extractor)))]
    (parse-note-response (:content response))))

(defn- note-origins [opts]
  (let [message-ids (vec (:source-message-ids opts))
        event-ids (vec (:source-event-ids opts))]
    [(cond-> {:type (or (:source-type opts) "extraction")}
       (:session-id opts) (assoc :session-id (:session-id opts))
       (:project-id opts) (assoc :project-id (:project-id opts))
       (:source-request-id opts) (assoc :request-id (:source-request-id opts))
       (seq message-ids) (assoc :message-id-start (first message-ids)
                                :message-id-end (last message-ids)
                                :message-count (count message-ids))
       (seq event-ids) (assoc :event-id-start (first event-ids)
                              :event-id-end (last event-ids)
                              :event-count (count event-ids)))]))

(defn- bounded-evidence [value limit]
  (util/truncate (or value "") limit #(str "\n\n[evidence truncated " % " chars]")))

(defn- note-evidence [exchange note]
  {:user (bounded-evidence (or (:evidence note) (:user-message exchange))
                           policy/extraction-evidence-user-chars)
   :assistant (when (pos? policy/extraction-evidence-assistant-chars)
                (bounded-evidence (:assistant-message exchange)
                                  policy/extraction-evidence-assistant-chars))})

(defn- normalized-note-title [note]
  (-> (:title note)
      (or "")
      str/lower-case
      (str/replace #"\s+" " ")
      str/trim))

(defn- note-search-text [note]
  (str (:title note) "\n" (:description note) "\n" (:body note)))

(defn- reviewable-existing-note? [note]
  (contains? policy/reviewable-vault-statuses (:iris-status note)))

(defn- duplicate-note? [existing note]
  (let [title (normalized-note-title note)
        text (note-search-text note)]
    (some (fn [candidate]
            (when (reviewable-existing-note? candidate)
              (let [candidate-title (normalized-note-title candidate)
                    candidate-text (str (:title candidate) "\n" (:description candidate))]
                (or (and (not (str/blank? title))
                         (= title candidate-title))
                    (>= (search/jaccard text candidate-text) 0.88)))))
          existing)))

(defn- filter-extracted-notes [memory-service notes opts]
  (let [min-confidence (:min-confidence opts)
        existing (when (:dedupe? opts)
                   (sqlite/list-vault-notes (:store memory-service) {:limit 10000}))]
    (second
     (reduce
      (fn [[seen kept] note]
        (let [title (normalized-note-title note)
              operation (:operation note)
              seen-key (if (= "update" operation)
                         [operation (:target-id note)]
                         [operation title])]
          (if (or (contains? seen seen-key)
                  (and min-confidence
                       (< (search/confidence-score (:confidence note))
                          (double min-confidence)))
                  (and (= "create" operation)
                       existing
                       (duplicate-note? existing note)))
            [seen kept]
            [(conj seen seen-key)
             (conj kept note)])))
      [#{} []]
      notes))))

(defn- existing-note-contexts [memory-service transcript]
  (->> (sqlite/list-vault-notes (:store memory-service)
                                {:status "approved" :limit 200})
       (map (fn [note]
              (let [content (:content (notes/read-vault-file memory-service (:path note)))
                    values (vault/note-change-values content)
                    score (search/jaccard transcript
                                   (str (:title note) "\n"
                                        (:description note) "\n"
                                        (:body values)))]
                {:score score
                 :id (:id note)
                 :revision (or (:revision note) (vault/content-revision content))
                 :type (:type values)
                 :title (:title values)
                 :description (:description values)
                 :body (util/truncate (:body values)
                                      policy/existing-note-context-chars
                                      #(str " [truncated " % " chars]"))
                 :tags (:tags values)
                 :scope (:scope values)})))
       (sort-by (juxt :score :title) #(compare %2 %1))
       (take policy/default-existing-note-context-limit)
       (mapv #(dissoc % :score))))

(defn- save-extracted-memory!
  [memory-service exchange opts note]
  (let [session (when-let [session-id (:session-id opts)]
                  (sqlite/get-session (:store memory-service) session-id))
        metadata (:metadata session)
        project-id (:project-id metadata)
        origins (note-origins (cond-> opts project-id (assoc :project-id (str project-id))))
        evidence (note-evidence exchange note)]
    (case (:operation note)
      "create"
      (proposals/propose-vault-note-create!
       memory-service note
       {:source (or (:source-type opts) :extraction)
        :session-id (:session-id opts)
        :request-id (:source-request-id opts)
        :origins origins
        :evidence evidence})

      "update"
      (when (and (not (str/blank? (:target-id note)))
                 (not (str/blank? (:expected-revision note))))
        (let [result (proposals/propose-vault-note-update!
                      memory-service
                      (:target-id note)
                      (:expected-revision note)
                      (-> note
                          (select-keys [:type :title :description :body :tags :scope])
                          (update :scope #(or % (notes/note-scope memory-service note))))
                      {:source (or (:source-type opts) :extraction)
                       :request-id (:source-request-id opts)
                       :origins origins
                       :evidence evidence})]
          (when-not (or (:noop result) (:duplicate result))
            result)))

      nil)))

(defn extract-and-save-notes!
  [memory-service provider exchange opts]
  (let [extractor (merge (get-in memory-service [:config :notes :extractor])
                         (:extractor opts))]
    (if (false? (:enabled extractor))
      []
      (try
        (let [model (or (:model extractor) (:model opts))
              existing-notes (existing-note-contexts memory-service (:user-message exchange))
              notes (filter-extracted-notes
                     memory-service
                     (extract-notes provider (assoc exchange
                                                    :model model
                                                    :session-id (:session-id opts)
                                                    :existing-notes existing-notes
                                                    :extractor extractor))
                     opts)
              saved (vec (keep #(save-extracted-memory! memory-service exchange opts %) notes))
              created (filter :path saved)]
          (when (seq created)
            (notes/reindex-vault! memory-service)
            nil)
          (when (seq saved)
            (sqlite/log-event! (:store memory-service)
                               {:event-type :memory.notes.extracted
                                :entity-type :session
                                :entity-id (:session-id opts)
                                :request-id (:source-request-id opts)
                                :payload {:note-count (count saved)
                                          :created-count (count created)
                                          :update-count (count (remove :path saved))
                                          :paths (mapv :path created)
                                          :update-ids (mapv :id (remove :path saved))}}))
          saved)
        (catch Exception e
          (when-not (false? (:log-failure? opts))
            (sqlite/log-event! (:store memory-service)
                               {:event-type :memory.notes.extraction_failed
                                :entity-type :session
                                :entity-id (:session-id opts)
                                :request-id (:source-request-id opts)
                                :payload {:message (.getMessage e)}}))
          (when (:throw? opts)
            (throw e))
          [])))))

(defn- extraction-limit [value]
  (min policy/max-session-extract-limit
       (policy/positive-limit value policy/default-session-extract-limit)))

(defn- message-transcript-line [{:keys [id role content]}]
  (str "[" id "] " role ": "
       (util/truncate content
                      policy/session-extract-message-chars
                      #(str " [truncated " % " chars]"))))

(defn- session-transcript [memory-service session-id limit]
  (let [messages (sqlite/list-messages (:store memory-service) session-id)
        selected (take-last (extraction-limit limit) messages)
        transcript (-> (str/join "\n\n" (map message-transcript-line selected))
                       (util/truncate policy/session-extract-transcript-chars
                                      #(str "\n\n[transcript truncated " % " chars]")))]
    {:session-id session-id
     :total-message-count (count messages)
     :included-message-count (count selected)
     :messages (vec selected)
     :transcript transcript}))

(defn extract-session-and-save-notes!
  "Manual memory consolidation entry point. Scans a bounded session transcript
   and writes durable findings as candidate vault notes."
  [memory-service provider {:keys [session-id limit request-id model]}]
  (when (str/blank? (or session-id ""))
    (throw (ex-info "session-id is required" {:type :validation-failed})))
  (when-not provider
    (throw (ex-info "Memory note extractor provider is not configured"
                    {:type :memory-extractor-provider-missing})))
  (let [{:keys [messages transcript] :as source} (session-transcript memory-service session-id limit)]
    (if (empty? messages)
      (assoc (dissoc source :messages :transcript)
             :note-count 0
             :created-count 0
             :update-count 0
             :paths []
             :update-ids [])
      (let [saved (extract-and-save-notes!
                   memory-service
                   provider
                   {:user-message transcript
                    :assistant-message "Manual end-of-dialogue memory consolidation."}
                   {:session-id session-id
                    :source-session-id session-id
                    :source-message-ids (mapv #(str (:id %)) messages)
                    :source-request-id request-id
                    :model model})
            created (filter :path saved)
            updates (remove :path saved)]
        (assoc (dissoc source :messages :transcript)
               :note-count (count saved)
               :created-count (count created)
               :update-count (count updates)
               :paths (mapv :path created)
               :update-ids (mapv :id updates))))))
