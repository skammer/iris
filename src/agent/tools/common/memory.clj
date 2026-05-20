(ns agent.tools.common.memory
  (:require
   [agent.memory.core :as memory]
   [agent.tools.core :as tools]
   [clojure.string :as str]))

(def ^:private max-line-chars 600)
(def ^:private max-vault-chars 8000)

(def ^:private allowed-actions
  #{:search :save-fact :read-vault :write-vault})

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
      (throw (tools/validation-error "action must be one of search/save-fact/read-vault/write-vault"
                                     {:action (:action input)})))
    (assoc input :action action)))

(defn- truncate-text [value max-chars]
  (let [text (str (or value ""))]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) " [truncated " (- (count text) max-chars) " chars]")
      text)))

(defn- fact-text [item]
  (str (:subject item) " " (:predicate item) " " (:object item)))

(defn- event-text [item]
  (str (:event-type item) " " (pr-str (:payload item))))

(defn- ranked-text [{:keys [surface score item]}]
  (let [body (case surface
               :message (str (:role item) ": " (:content item))
               :event (event-text item)
               :fact (fact-text item)
               :graph (fact-text item)
               (str item))
        id (or (:id item) (:source-fact-id item))]
    (str "- " (name surface)
         (when id (str " #" id))
         (when score (format " score=%.3f" (double score)))
         ": "
         (truncate-text (str/replace body #"\s+" " ") max-line-chars))))

(defn- search-results-text [query results]
  (let [ranked (vec (:ranked results))
        omitted (+ (max 0 (- (count (:messages results)) (count ranked)))
                   (count (:events results))
                   (count (:facts results))
                   (count (:graph results)))]
    (cond
      (str/blank? (or query ""))
      "Memory search skipped: query is blank. Provide a focused query."

      (empty? ranked)
      (str "No memory results for: " query)

      :else
      (str "Memory results for: " query "\n"
           (str/join "\n" (map ranked-text ranked))
           (when (pos? omitted)
             (str "\nOmitted lower-priority raw results: " omitted))))))

(defn- save-fact-text [saved]
  (str "Saved memory fact: "
       (:subject saved) " " (:predicate saved) " " (:object saved)
       " (scope=" (get-in saved [:scope :type])
       (when-let [id (get-in saved [:scope :id])]
         (str "/" id))
       ")"))

(defn- read-vault-text [path result]
  (str "Memory vault file: " path "\n"
       (truncate-text (or (:content result) result) max-vault-chars)))

(defn- write-vault-text [path result]
  (str "Wrote memory vault file: " (or (:path result) path)
       " (" (count (or (:content result) "")) " chars)"))

(defn create-memory-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory
     "Durable memory tool"
     :category :memory
     :input-schema [:map {:closed true}
                    [:action [:or
                              [:enum :search :save-fact :read-vault :write-vault]
                              [:enum "search" "save-fact" "read-vault" "write-vault"]]]
                    [:query {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:scope {:optional true} [:maybe [:map {:closed true}
                                                [:type [:or
                                                        [:enum :global :session :agent]
                                                        [:enum "global" "session" "agent"]]]
                                                [:id {:optional true} [:maybe :string]]]]]
                    [:subject {:optional true} [:maybe :string]]
                    [:predicate {:optional true} [:maybe :string]]
                    [:object {:optional true} [:maybe :string]]
                    [:path {:optional true} [:maybe :string]]
                    [:content {:optional true} [:maybe :string]]]
     :source :builtin)
    :validate-fn validate-input
    :execute-fn
    (fn [{:keys [action query limit scope subject predicate object path content]} context]
      (case action
        :search
        (do
          (ensure-permission! context :memory-read)
          (if (str/blank? (or query ""))
            (search-results-text query nil)
            (search-results-text
             query
             (memory/search-memory memory-service
                                   query
                                   (cond-> {}
                                     limit (assoc :limit limit)
                                     scope (assoc :scope scope)
                                     (:session-id context) (assoc :session-id (:session-id context))
                                     (:agent-id context) (assoc :agent-id (:agent-id context)))))))

        :save-fact
        (do
          (ensure-permission! context :memory-write)
          (doseq [[field value] {:subject subject :predicate predicate :object object}]
            (when (str/blank? (or value ""))
              (throw (tools/validation-error "fact fields must be non-blank strings"
                                             {:field field}))))
          (save-fact-text
           (memory/save-memory-fact! memory-service
                                     {:subject subject
                                      :predicate predicate
                                      :object object}
                                     {:scope (or scope
                                                 {:type :session
                                                  :id (:session-id context)})
                                      :source-session-id (:session-id context)
                                      :source-request-id (:request-id context)})))

        :read-vault
        (do
          (ensure-permission! context :memory-read)
          (read-vault-text path (memory/read-vault-file memory-service path)))

        :write-vault
        (do
          (ensure-permission! context :memory-write)
          (write-vault-text path (memory/write-vault-file! memory-service path content)))))}))
