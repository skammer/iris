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

(def ^:private scope-schema
  [:map {:closed true}
   [:type [:or
           [:enum :global :session :agent]
           [:enum "global" "session" "agent"]]]
   [:id {:optional true} [:maybe :string]]])

(defn- ensure-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

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

(defn- fact-map [{:keys [id subject predicate object confidence]}]
  (cond-> {:subject subject
           :predicate predicate
           :object object}
    id (assoc :id id)
    confidence (assoc :confidence confidence)))

(defn- fact-opts [{:keys [scope source-session-id source-message-ids source-request-id]}
                  context]
  {:scope (or scope
              {:type :session
               :id (:session-id context)})
   :source-session-id (or source-session-id (:session-id context))
   :source-message-ids source-message-ids
   :source-request-id (or source-request-id (:request-id context))})

(defn create-memory-search-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_search
     "Search durable memory facts and configured prompt files. Returns compact text snippets."
     :category :memory
     :input-schema [:map {:closed true}
                    [:query :string]
                    [:limit {:optional true} [:maybe :int]]
                    [:scope {:optional true} [:maybe scope-schema]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [query limit scope]} context]
      (ensure-permission! context :memory-read)
      (if (str/blank? (or query ""))
        (search-results-text query nil)
        (search-results-text
         query
         (memory-search-candidates memory-service
                                   query
                                   (cond-> {:limit limit}
                                     scope (assoc :scope scope)
                                     (:session-id context) (assoc :session-id (:session-id context))
                                     (:agent-id context) (assoc :agent-id (:agent-id context)))))))}))

(def ^:private fact-save-schema
  [:map {:closed true}
   [:id {:optional true} [:maybe :string]]
   [:subject {:optional true} [:maybe :string]]
   [:predicate {:optional true} [:maybe :string]]
   [:object {:optional true} [:maybe :string]]
   [:confidence {:optional true} [:maybe number?]]
   [:scope {:optional true} [:maybe scope-schema]]
   [:source-session-id {:optional true} [:maybe :string]]
   [:source-message-ids {:optional true} [:maybe [:vector :string]]]
   [:source-request-id {:optional true} [:maybe :string]]])

(def ^:private fact-remove-schema
  [:map {:closed true}
   [:id {:optional true} [:maybe :string]]
   [:subject {:optional true} [:maybe :string]]
   [:predicate {:optional true} [:maybe :string]]
   [:object {:optional true} [:maybe :string]]
   [:scope {:optional true} [:maybe scope-schema]]
   [:source-session-id {:optional true} [:maybe :string]]
   [:source-request-id {:optional true} [:maybe :string]]])

(defn create-memory-save-fact-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_save_fact
     "Save a durable SQLite memory fact. Provide explicit subject, predicate, and object."
     :category :memory
     :input-schema fact-save-schema
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [input context]
      (ensure-permission! context :memory-write)
      (require-fact-fields! input)
      (save-fact-text
       (memory/save-memory-fact! memory-service
                                 (fact-map input)
                                 (fact-opts input context))))}))

(defn create-memory-remove-fact-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_remove_fact
     "Remove a SQLite memory fact by id or exact subject, predicate, and object."
     :category :memory
     :input-schema fact-remove-schema
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [input context]
      (ensure-permission! context :memory-write)
      (require-fact-selector! input)
      (remove-fact-text
       (memory/remove-memory-fact! memory-service
                                   (select-keys input [:id :subject :predicate :object])
                                   (fact-opts input context))))}))

(defn create-memory-read-vault-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_read_vault
     "Read a configured memory vault file."
     :category :memory
     :input-schema [:map {:closed true}
                    [:path [:maybe :string]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [path]} context]
      (ensure-permission! context :memory-read)
      (read-vault-text path (memory/read-vault-file memory-service path)))}))

(defn create-memory-write-vault-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_write_vault
     "Write a configured memory vault file."
     :category :memory
     :input-schema [:map {:closed true}
                    [:path [:maybe :string]]
                    [:content {:optional true} [:maybe :string]]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [{:keys [path content]} context]
      (ensure-permission! context :memory-write)
      (write-vault-text path (memory/write-vault-file! memory-service path content)))}))

(defn create-memory-tools [memory-service]
  [(create-memory-search-tool memory-service)
   (create-memory-save-fact-tool memory-service)
   (create-memory-remove-fact-tool memory-service)
   (create-memory-read-vault-tool memory-service)
   (create-memory-write-vault-tool memory-service)])

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
