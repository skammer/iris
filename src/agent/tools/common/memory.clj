(ns agent.tools.common.memory
  (:require
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]
   [agent.persistence.sqlite :as sqlite]
   [agent.tools.core :as tools]
   [agent.util :as util]
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
  (util/truncate value max-chars #(str " [truncated " % " chars]")))

(defn- compact-whitespace [value]
  (str/trim (str/replace (str (or value "")) #"\s+" " ")))

(defn- tokens [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

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

(defn- recall-results-text [query results]
  (cond
    (str/blank? (or query ""))
    "Memory recall skipped: query is blank. Provide a focused query."

    (empty? results)
    (str "No memory recall results for: " query)

    :else
    (str "Memory recall for: " query "\n"
         (str/join
          "\n"
          (map (fn [{:keys [surface id text score scope status reason]}]
                 (str "- " (name surface)
                      (when id
                        (str " #" id))
                      (format " score=%.3f" (double score))
                      " " (name status)
                      " " (name reason)
                      (when scope
                        (str " scope=" (name (:type scope))
                             (when-let [scope-id (:id scope)]
                               (str "/" scope-id))))
                      ": "
                      (truncate-text text max-line-chars)))
               results)))))

(defn- vault-search-text [query results]
  (if (empty? results)
    (str "No vault results for: " query)
    (str "Vault results for: " query "\n"
         (str/join
          "\n"
          (map (fn [{:keys [path note-id chunk-id heading text iris-status iris-scope]}]
                 (str "- " chunk-id
                      (when note-id (str " note=" note-id))
                      " " iris-status
                      " scope=" iris-scope
                      " path=" path
                      (when heading (str " heading=" heading))
                      ": "
                      (truncate-text text max-line-chars)))
               results)))))

(def ^:private scratchpad-scope-schema
  [:map {:closed true}
   [:type [:or [:enum :global :session] [:enum "global" "session"]]]
   [:id {:optional true} [:maybe :string]]])

(defn- scratchpad-read-text [{:keys [scope path revision content]}]
  (str "Scratchpad " (:type scope)
       (when-let [id (:id scope)] (str "/" id))
       " revision=" revision
       " path=" path
       "\n"
       (truncate-text content max-vault-chars)))

(defn- scratchpad-search-text [{:keys [query scope revision snippets]}]
  (if (empty? snippets)
    (str "No scratchpad results for: " query)
    (str "Scratchpad results for: " query
         " scope=" (:type scope)
         (when-let [id (:id scope)] (str "/" id))
         " revision=" revision
         "\n"
         (str/join "\n"
                   (map (fn [{:keys [line text]}]
                          (str "- line " line ": " (truncate-text text max-line-chars)))
                        snippets)))))

(defn- scratchpad-replace-text [{:keys [scope path revision previous-revision]}]
  (str "Updated scratchpad " (:type scope)
       (when-let [id (:id scope)] (str "/" id))
       " revision=" revision
       " previous=" previous-revision
       " path=" path))

(defn create-memory-recall-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_recall
     "Recall relevant memory records. Returns compact source-cited snippets."
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
        (recall-results-text query nil)
        (let [results (:results
                       (recall/recall
                        memory-service
                        query
                        (cond-> {:limit (memory-tool-limit memory-service limit)}
                          scope (assoc :scope scope)
                          (:session-id context) (assoc :session-id (:session-id context))
                          (:agent-id context) (assoc :agent-id (:agent-id context)))))]
          (recall-results-text query results))))}))

(defn create-vault-search-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :vault_search
     "Search indexed approved memory vault notes and chunks."
     :category :memory
     :input-schema [:map {:closed true}
                    [:query :string]
                    [:limit {:optional true} [:maybe :int]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [query limit]} context]
      (ensure-permission! context :memory-read)
      (vault-search-text query
                         (memory/search-vault memory-service query
                                              (cond-> {:limit (memory-tool-limit memory-service limit)}
                                                (:session-id context) (assoc :session-id (:session-id context))))))}))

(defn create-scratchpad-read-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :scratchpad_read
     "Read global or session scratchpad working memory. Returns full text and revision."
     :category :memory
     :input-schema [:map {:closed true}
                    [:scope {:optional true} [:maybe scratchpad-scope-schema]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [scope]} context]
      (ensure-permission! context :memory-read)
      (scratchpad-read-text
       (memory/read-scratchpad memory-service
                               (cond-> {}
                                 scope (assoc :scope scope)
                                 (:session-id context) (assoc :session-id (:session-id context))))))}))

(defn create-scratchpad-search-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :scratchpad_search
     "Search global or session scratchpad working memory."
     :category :memory
     :input-schema [:map {:closed true}
                    [:query :string]
                    [:scope {:optional true} [:maybe scratchpad-scope-schema]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [query scope]} context]
      (ensure-permission! context :memory-read)
      (scratchpad-search-text
       (memory/search-scratchpad memory-service
                                 query
                                 (cond-> {}
                                   scope (assoc :scope scope)
                                   (:session-id context) (assoc :session-id (:session-id context))))))}))

(defn create-scratchpad-replace-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :scratchpad_replace
     "Exact replace in scratchpad working memory using expected revision."
     :category :memory
     :input-schema [:map {:closed true}
                    [:old-text :string]
                    [:new-text :string]
                    [:expected-revision :string]
                    [:scope {:optional true} [:maybe scratchpad-scope-schema]]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [{:keys [old-text new-text expected-revision scope]} context]
      (ensure-permission! context :memory-write)
      (scratchpad-replace-text
       (memory/replace-scratchpad! memory-service
                                   (cond-> {:old-text old-text
                                            :new-text new-text
                                            :expected-revision expected-revision}
                                     scope (assoc :scope scope)
                                     (:session-id context) (assoc :session-id (:session-id context))))))}))

(defn create-memory-tools [memory-service]
  [(create-memory-recall-tool memory-service)
   (create-vault-search-tool memory-service)
   (create-scratchpad-read-tool memory-service)
   (create-scratchpad-search-tool memory-service)
   (create-scratchpad-replace-tool memory-service)])

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
