(ns agent.tools.common.memory
  (:require
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def ^:private max-line-chars 600)
(def ^:private max-vault-chars 8000)
(def ^:private max-message-chars 800)

(def ^:private allowed-actions
  #{:search :save-fact :remove-fact :save-graph-fact :remove-graph-fact
    :datalog :read-vault :write-vault})

(defn- normalize-action [action]
  (cond
    (keyword? action) action
    (string? action) (keyword (str/lower-case action))
    :else nil))

(defn- ensure-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

(defn- validate-input [input]
  (let [action (normalize-action (:action input))]
    (when-not (allowed-actions action)
      (throw (tools/validation-error
              "action must be one of search/save-fact/remove-fact/save-graph-fact/remove-graph-fact/datalog/read-vault/write-vault"
              {:action (:action input)})))
    (assoc input :action action)))

(defn- validate-message-search-input [input]
  (when (str/blank? (or (:query input) ""))
    (throw (tools/validation-error "query must be a non-blank string" {:query (:query input)})))
  input)

(defn- truncate-text [value max-chars]
  (let [text (str (or value ""))]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) " [truncated " (- (count text) max-chars) " chars]")
      text)))

(defn- compact-whitespace [value]
  (str/trim (str/replace (str (or value "")) #"\s+" " ")))

(defn- tokens [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

(defn- text-score [query text]
  (let [query* (str/lower-case (str/trim (or query "")))
        text* (str/lower-case (or text ""))
        query-tokens (tokens query*)
        text-tokens (tokens text*)]
    (cond
      (str/blank? query*) 0.0
      (str/includes? text* query*) 1.0
      (empty? query-tokens) 0.0
      :else (/ (double (count (set/intersection query-tokens text-tokens)))
               (count query-tokens)))))

(defn- positive-int [value fallback]
  (if (and (integer? value) (pos? value))
    value
    fallback))

(defn- memory-tool-limit [memory-service requested]
  (min (positive-int requested (or (:search-default-limit memory-service) 10))
       (or (:search-max-limit memory-service) 50)))

(defn- first-match-index [query text]
  (let [query* (str/lower-case (str/trim (or query "")))
        text* (str/lower-case (or text ""))]
    (or (when-not (str/blank? query*)
          (let [idx (str/index-of text* query*)]
            (when (some? idx) idx)))
        (some (fn [token]
                (let [idx (str/index-of text* token)]
                  (when (some? idx) idx)))
              (tokens query*)))))

(defn- focused-chunk
  ([query text] (focused-chunk query text max-line-chars))
  ([query text max-chars]
   (let [text* (compact-whitespace text)]
     (if (<= (count text*) max-chars)
       text*
       (let [idx (or (first-match-index query text*) 0)
             half (quot max-chars 2)
             start (max 0 (- idx half))
             end (min (count text*) (+ start max-chars))
             start* (max 0 (- end max-chars))
             prefix (when (pos? start*) "... ")
             suffix (when (< end (count text*)) " ...")]
         (str prefix (subs text* start* end) suffix))))))

(defn- fact-text [item]
  (compact-whitespace (str (:subject item) " " (:predicate item) " " (:object item))))

(defn- graph-text [item]
  (if (= "path" (:type item))
    (compact-whitespace
     (str (str/join " " (map :label (:nodes item)))
          " "
          (str/join " " (map :predicate (:edges item)))))
    (fact-text item)))

(defn- prompt-candidates [query documents]
  (->> documents
       (mapcat (fn [{:keys [path content]}]
                 (let [parts (->> (str/split (or content "") #"\n\s*\n")
                                  (map compact-whitespace)
                                  (remove str/blank?))
                       matches (->> parts
                                    (map (fn [part]
                                           {:surface :prompt
                                            :id path
                                            :text (focused-chunk query part)
                                            :score (text-score query part)}))
                                    (filter #(pos? (:score %)))
                                    (take 3)
                                    vec)]
                   (if (seq matches)
                     matches
                     (let [score (text-score query content)]
                       (when (pos? score)
                         [{:surface :prompt
                           :id path
                           :text (focused-chunk query content)
                           :score score}]))))))
       (remove nil?)))

(defn- memory-search-candidates [memory-service query opts]
  (let [limit (memory-tool-limit memory-service (:limit opts))
        opts* (assoc opts :limit limit)
        facts (memory/search-facts memory-service query opts*)
        graph (try
                (memory/query-graph-memory memory-service query
                                           (select-keys opts* [:limit :mode :entity :depth :from :to
                                                             :max-depth :as-of :include-historical?]))
                (catch Exception _ []))
        prompts (prompt-candidates query (:documents (memory/read-prompt-memory memory-service)))]
    (->> (concat
          (map (fn [fact]
                 {:surface :fact
                  :id (:id fact)
                  :text (fact-text fact)
                  :score (max (text-score query (fact-text fact))
                              0.7)
                  :item fact})
               facts)
          (map (fn [item]
                 {:surface :graph
                  :id (or (:id item) (:source-fact-id item))
                  :text (graph-text item)
                  :score (max (text-score query (graph-text item))
                              0.65)
                  :item item})
               graph)
          prompts)
         (sort-by :score >)
         (take limit)
         vec)))

(defn- search-results-text [query results]
  (cond
    (str/blank? (or query ""))
    "Memory search skipped: query is blank. Provide a focused query."

    (empty? results)
    (str "No memory results for: " query)

    :else
    (str "Memory results for: " query "\n"
         (str/join
          "\n"
          (map (fn [{:keys [surface id text score]}]
                 (str "- " (name surface)
                      (when id
                        (str " #" (if (= surface :prompt)
                                   (.getName (io/file id))
                                   id)))
                      (format " score=%.3f" (double score))
                      ": "
                      (truncate-text text max-line-chars)))
               results)))))

(defn- save-fact-text [saved]
  (str "Saved memory fact: "
       (:subject saved) " " (:predicate saved) " " (:object saved)
       " (scope=" (get-in saved [:scope :type])
       (when-let [id (get-in saved [:scope :id])]
         (str "/" id))
       ")"))

(defn- remove-fact-text [removed]
  (str "Removed memory fact"
       (when-let [id (:id removed)] (str " #" id))
       ": " (:removed-count removed) " row(s)"))

(defn- save-graph-fact-text [saved]
  (str "Saved graph fact: "
       (:subject saved) " " (:predicate saved) " " (:object saved)))

(defn- remove-graph-fact-text [removed]
  (str "Removed graph fact"
       (when-let [id (:id removed)] (str " #" id))
       ": " (:removed-count removed) " edge(s)"))

(defn- datalog-text [{:keys [row-count rows]}]
  (str "Datalog rows: " row-count "\n"
       (str/join "\n"
                 (map #(str "- " (truncate-text (pr-str %) max-line-chars))
                      rows))))

(defn- read-vault-text [path result]
  (str "Memory vault file: " path "\n"
       (truncate-text (or (:content result) result) max-vault-chars)))

(defn- write-vault-text [path result]
  (str "Wrote memory vault file: " (or (:path result) path)
       " (" (count (or (:content result) "")) " chars)"))

(defn- require-fact-fields! [{:keys [subject predicate object]}]
  (doseq [[field value] {:subject subject :predicate predicate :object object}]
    (when (str/blank? (or value ""))
      (throw (tools/validation-error "fact fields must be non-blank strings"
                                     {:field field})))))

(defn- require-fact-selector! [{:keys [id subject predicate object]}]
  (when (and (str/blank? (or id ""))
             (or (str/blank? (or subject ""))
                 (str/blank? (or predicate ""))
                 (str/blank? (or object ""))))
    (throw (tools/validation-error "provide id or subject/predicate/object"
                                   {:id id
                                    :subject subject
                                    :predicate predicate
                                    :object object}))))

(defn- graph-opts [{:keys [mode entity depth from to max-depth max_depth as-of as_of
                           include-historical? include_historical]}]
  (cond-> {}
    mode (assoc :mode (keyword mode))
    entity (assoc :entity entity)
    depth (assoc :depth depth)
    from (assoc :from from)
    to (assoc :to to)
    (or max-depth max_depth) (assoc :max-depth (or max-depth max_depth))
    (or as-of as_of) (assoc :as-of (or as-of as_of))
    (or include-historical? include_historical) (assoc :include-historical? true)))

(defn- fact-map [{:keys [id subject predicate object confidence valid-from valid_from valid-to valid_to
                         observed-at observed_at invalidated-by invalidated_by tags]}]
  (cond-> {:subject subject
           :predicate predicate
           :object object}
    id (assoc :id id)
    confidence (assoc :confidence confidence)
    (or valid-from valid_from) (assoc :valid-from (or valid-from valid_from))
    (or valid-to valid_to) (assoc :valid-to (or valid-to valid_to))
    (or observed-at observed_at) (assoc :observed-at (or observed-at observed_at))
    (or invalidated-by invalidated_by) (assoc :invalidated-by (or invalidated-by invalidated_by))
    tags (assoc :tags (vec tags))))

(defn- fact-opts [{:keys [scope source-session-id source-message-ids source-request-id]}
                  context]
  {:scope (or scope
              {:type :session
               :id (:session-id context)})
   :source-session-id (or source-session-id (:session-id context))
   :source-message-ids source-message-ids
   :source-request-id (or source-request-id (:request-id context))})

(defn create-memory-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory
     "Durable memory over facts, graph facts, configured prompt files, vault reads/writes, and graph Datalog."
     :category :memory
     :input-schema [:map {:closed true}
                    [:action [:or
                              [:enum :search :save-fact :remove-fact :save-graph-fact :remove-graph-fact
                               :datalog :read-vault :write-vault]
                              [:enum "search" "save-fact" "remove-fact" "save-graph-fact" "remove-graph-fact"
                               "datalog" "read-vault" "write-vault"]]]
                    [:query {:optional true} [:maybe :string]]
                    [:args {:optional true} [:maybe :any]]
                    [:limit {:optional true} [:maybe :int]]
                    [:scope {:optional true} [:maybe [:map {:closed true}
                                                [:type [:or
                                                        [:enum :global :session :agent]
                                                        [:enum "global" "session" "agent"]]]
                                                [:id {:optional true} [:maybe :string]]]]]
                    [:id {:optional true} [:maybe :string]]
                    [:subject {:optional true} [:maybe :string]]
                    [:predicate {:optional true} [:maybe :string]]
                    [:object {:optional true} [:maybe :string]]
                    [:confidence {:optional true} [:maybe number?]]
                    [:source-session-id {:optional true} [:maybe :string]]
                    [:source-message-ids {:optional true} [:maybe [:vector :string]]]
                    [:source-request-id {:optional true} [:maybe :string]]
                    [:mode {:optional true} [:maybe :string]]
                    [:entity {:optional true} [:maybe :string]]
                    [:depth {:optional true} [:maybe :int]]
                    [:from {:optional true} [:maybe :string]]
                    [:to {:optional true} [:maybe :string]]
                    [:max-depth {:optional true} [:maybe :int]]
                    [:max_depth {:optional true} [:maybe :int]]
                    [:as-of {:optional true} [:maybe :string]]
                    [:as_of {:optional true} [:maybe :string]]
                    [:include-historical? {:optional true} [:maybe :boolean]]
                    [:include_historical {:optional true} [:maybe :boolean]]
                    [:valid-from {:optional true} [:maybe :string]]
                    [:valid_from {:optional true} [:maybe :string]]
                    [:valid-to {:optional true} [:maybe :string]]
                    [:valid_to {:optional true} [:maybe :string]]
                    [:observed-at {:optional true} [:maybe :string]]
                    [:observed_at {:optional true} [:maybe :string]]
                    [:invalidated-by {:optional true} [:maybe :string]]
                    [:invalidated_by {:optional true} [:maybe :string]]
                    [:tags {:optional true} [:maybe [:vector :string]]]
                    [:path {:optional true} [:maybe :string]]
                    [:content {:optional true} [:maybe :string]]]
     :operation :act
     :approval-sensitive? false
     :action-key :action
     :read-only-actions #{:search :datalog :read-vault}
     :parallel-safe-actions #{:search :datalog :read-vault}
     :source :builtin)
    :validate-fn validate-input
    :execute-fn
    (fn [{:keys [action query limit scope path content args] :as input} context]
      (case action
        :search
        (do
          (ensure-permission! context :memory-read)
          (if (str/blank? (or query ""))
            (search-results-text query nil)
            (search-results-text
             query
             (memory-search-candidates memory-service
                                       query
                                       (merge (graph-opts input)
                                              (cond-> {:limit limit}
                                                scope (assoc :scope scope)
                                                (:session-id context) (assoc :session-id (:session-id context))
                                                (:agent-id context) (assoc :agent-id (:agent-id context))))))))

        :save-fact
        (do
          (ensure-permission! context :memory-write)
          (require-fact-fields! input)
          (save-fact-text
           (memory/save-memory-fact! memory-service
                                     (fact-map input)
                                     (fact-opts input context))))

        :remove-fact
        (do
          (ensure-permission! context :memory-write)
          (require-fact-selector! input)
          (remove-fact-text
           (memory/remove-memory-fact! memory-service
                                       (select-keys input [:id :subject :predicate :object])
                                       (fact-opts input context))))

        :save-graph-fact
        (do
          (ensure-permission! context :memory-write)
          (require-fact-fields! input)
          (save-graph-fact-text
           (memory/save-graph-fact! memory-service
                                    (merge (fact-map input)
                                           {:source-request-id (or (:source-request-id input)
                                                                   (:request-id context))
                                            :session-id (or (:source-session-id input)
                                                            (:session-id context))}))))

        :remove-graph-fact
        (do
          (ensure-permission! context :memory-write)
          (require-fact-selector! input)
          (remove-graph-fact-text
           (memory/remove-graph-fact! memory-service
                                      (select-keys input [:id :subject :predicate :object]))))

        :datalog
        (do
          (ensure-permission! context :memory-read)
          (datalog-text
           (memory/query-datalog-memory memory-service
                                        query
                                        (cond-> {}
                                          args (assoc :args args)
                                          limit (assoc :limit limit)))))

        :read-vault
        (do
          (ensure-permission! context :memory-read)
          (read-vault-text path (memory/read-vault-file memory-service path)))

        :write-vault
        (do
          (ensure-permission! context :memory-write)
          (write-vault-text path (memory/write-vault-file! memory-service path content)))))}))

(defn- message-search-text [query rows]
  (if (empty? rows)
    (str "No message chunks for: " query)
    (str "Message chunks for: " query "\n"
         (str/join "\n"
                   (map (fn [row]
                          (str "- " (focused-chunk query (:content row) max-message-chars)))
                        rows)))))

(defn create-message-search-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :message_search
     "Search persisted chat messages and return only relevant text chunks."
     :category :memory
     :input-schema [:map {:closed true}
                    [:query :string]
                    [:limit {:optional true} [:maybe :int]]
                    [:session-id {:optional true} [:maybe :string]]
                    [:all-sessions? {:optional true} [:maybe :boolean]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :validate-fn validate-message-search-input
    :execute-fn
    (fn [{:keys [query limit session-id all-sessions?]} context]
      (ensure-permission! context :memory-read)
      (let [session-id* (when-not all-sessions?
                          (or session-id (:session-id context)))
            rows (sqlite/search-messages (:store memory-service)
                                         query
                                         (cond-> {:limit (memory-tool-limit memory-service limit)}
                                           session-id* (assoc :session-id session-id*)))]
        (message-search-text query rows)))}))
