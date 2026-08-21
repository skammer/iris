(ns agent.tools.common.memory
  (:require
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]
   [agent.memory.user-profile :as user-profile]
   [agent.persistence.sqlite :as sqlite]
   [agent.skills :as skills]
   [agent.tools.core :as tools]
   [agent.util :as util]
   [clojure.string :as str])
  (:import
   [java.time Instant]))

(def ^:private max-line-chars 600)
(def ^:private max-vault-chars 8000)
(def ^:private max-message-chars 800)
(def ^:private max-message-search-limit 100)
(def ^:private max-extract-session-limit 200)

(def ^:private scope-schema
  [:map {:closed true}
   [:type [:or
           [:enum :global :session :agent :project]
           [:enum "global" "session" "agent" "project"]]]
   [:id {:optional true} [:maybe :string]]])

(defn- ensure-permission! [context permission]
  (when-not (contains? (:permissions context) permission)
    (throw (tools/permission-error #{permission} (:permissions context)))))

(defn- parse-instant! [field value]
  (when value
    (try
      (Instant/parse value)
      (catch Exception _
        (throw (tools/validation-error
                (str (name field) " must be an ISO-8601 UTC instant")
                {field value}))))))

(defn- validate-message-search-input [input]
  (let [query (:query input)
        since (parse-instant! :since (:since input))
        until (parse-instant! :until (:until input))]
    (when (and (str/blank? (or query "")) (nil? since) (nil? until))
      (throw (tools/validation-error
              "query may be blank only when since or until is provided"
              {:query query :since (:since input) :until (:until input)})))
    (when (and since until (not (.isBefore since until)))
      (throw (tools/validation-error "since must be before until"
                                     {:since (:since input) :until (:until input)})))
    (cond-> input
      since (assoc :since (str since))
      until (assoc :until (str until)))))

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

(defn- extract-session-limit [requested]
  (min (positive-int requested 80) max-extract-session-limit))

(defn- memory-tool-limit [memory-service requested]
  (min (positive-int requested (or (:search-default-limit memory-service) 10))
       (or (:search-max-limit memory-service) 50)))

(defn- message-search-limit [requested]
  (min (positive-int requested 20) max-message-search-limit))

(defn- session-project-id [memory-service context]
  (some->> (:session-id context)
           (sqlite/get-session (:store memory-service))
           :metadata
           :project-id))

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
          (map (fn [{:keys [surface id text score scope status reason why]}]
                 (str "- " (name surface)
                      (when id
                        (str " #" id))
                      (format " score=%.3f" (double score))
                      " " (name status)
                      " " (name reason)
                      (when-let [breakdown (:score-breakdown why)]
                        (str " why=" (pr-str breakdown)))
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
          (map (fn [{:keys [path note-id chunk-id heading text iris-status iris-scope revision
                            reason score-breakdown]}]
                 (str "- " chunk-id
                      (when note-id (str " note=" note-id))
                      " " iris-status
                      " scope=" iris-scope
                      (when revision (str " revision=" revision))
                      (when reason (str " reason=" (name reason)))
                      (when score-breakdown (str " why=" (pr-str score-breakdown)))
                      " path=" path
                      (when heading (str " heading=" heading))
                      ": "
                      (truncate-text text max-line-chars)))
               results)))))

(defn- similar-notes-text [candidates]
  (if (empty? candidates)
    "No similar approved memory notes found."
    (str "Similar approved memory notes; lexical score is candidate generation only. Verify semantic equivalence before proposing a merge.\n"
         (str/join
          "\n"
          (map (fn [{:keys [score score-breakdown scope scope-owner left right]}]
                 (str "- score=" (format "%.3f" (double score))
                      " scope=" scope
                      (when scope-owner (str "/" scope-owner))
                      " why=" (pr-str score-breakdown)
                      "\n  left=" (:id left) " revision=" (:revision left)
                      " title=" (pr-str (:title left))
                      " body=" (pr-str (truncate-text (:body left) max-line-chars))
                      "\n  right=" (:id right) " revision=" (:revision right)
                      " title=" (pr-str (:title right))
                      " body=" (pr-str (truncate-text (:body right) max-line-chars))))
               candidates)))))

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
        (let [project-id (session-project-id memory-service context)
              results (:results
                       (recall/recall
                        memory-service
                        query
                        (cond-> {:limit (memory-tool-limit memory-service limit)}
                          scope (assoc :scope scope)
                          (:session-id context) (assoc :session-id (:session-id context))
                          project-id (assoc :project-id project-id)
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
      (let [project-id (session-project-id memory-service context)]
        (vault-search-text query
                           (memory/search-vault memory-service query
                                                (cond-> {:limit (memory-tool-limit memory-service limit)}
                                                  (:session-id context) (assoc :session-id (:session-id context))
                                                  project-id (assoc :project-id project-id))))))}))

(defn create-memory-find-similar-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_find_similar
     "Find same-scope approved memory notes that may describe the same durable subject. Scores only shortlist candidates; inspect both notes before proposing merge."
     :category :memory
     :input-schema [:map {:closed true}
                    [:threshold {:optional true} [:maybe [:or :int :double]]]
                    [:limit {:optional true} [:maybe :int]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [threshold limit]} context]
      (ensure-permission! context :memory-read)
      (similar-notes-text
       (memory/similar-vault-notes
        memory-service
        (cond-> {}
          threshold (assoc :threshold threshold)
          limit (assoc :limit limit)))))}))

(defn create-scratchpad-read-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :scratchpad_read
     "Read working memory before complex work or resuming a task. Returns full text and revision."
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
     "Store compact synthesized findings or partial deliverables in working memory using exact replace and expected revision."
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

(def ^:private memory-update-changes-schema
  [:map {:closed true}
   [:type {:optional true} :string]
   [:title {:optional true} :string]
   [:description {:optional true} :string]
   [:body {:optional true} :string]
   [:tags {:optional true} [:vector :string]]
   [:scope {:optional true}
    [:or [:enum :global :session :agent :project]
     [:enum "global" "session" "agent" "project"]]]])

(defn- update-evidence [context reason]
  {:user (get-in context [:magi-context :user-request])
   :assistant reason})

(defn- memory-update-text [update]
  (if (:noop update)
    (str "Memory update is a no-op for " (:target-id update)
         " revision=" (:base-revision update))
    (str "Memory proposal " (:id update)
         " operation=" (:operation update)
         " status=" (:status update)
         " target=" (:target-id update)
         " base=" (:base-revision update)
         " proposed=" (:proposed-revision update)
         "\n"
         (:diff update))))

(defn- proposal-opts [memory-service context reason]
  (cond-> {:source :tool
           :request-id (:request-id context)
           :session-id (:session-id context)
           :evidence (update-evidence context reason)}
    (session-project-id memory-service context)
    (assoc :project-id (session-project-id memory-service context))))

(defn create-memory-propose-create-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_propose_create
     "Draft a durable memory or Skill candidate for MAGI/user review. It is not indexed or activated before approval."
     :category :memory
     :input-schema [:map {:closed true}
                    [:type :string]
                    [:title :string]
                    [:description :string]
                    [:body :string]
                    [:tags {:optional true} [:vector :string]]
                    [:scope {:optional true} [:enum "global" "session" "agent" "project"]]
                    [:confidence {:optional true} [:or :int :double]]
                    [:reason :string]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [{:keys [reason] :as input} context]
      (ensure-permission! context :memory-write)
      (let [result (memory/propose-vault-note-create!
                    memory-service
                    (dissoc input :reason)
                    (proposal-opts memory-service context reason))]
        (str "Memory create proposal " (:id result)
             " status=" (:status result)
             " path=" (:path result))))}))

(defn create-memory-propose-update-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_propose_update
     "Propose partial changes to an approved memory note. The note stays active until MAGI approves the diff."
     :category :memory
     :input-schema [:map {:closed true}
                    [:note-id :string]
                    [:expected-revision :string]
                    [:changes memory-update-changes-schema]
                    [:reason :string]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [{:keys [note-id expected-revision changes reason]} context]
      (ensure-permission! context :memory-write)
      (memory-update-text
       (memory/propose-vault-note-update!
        memory-service
        note-id
        expected-revision
       changes
        (proposal-opts memory-service context reason))))}))

(defn create-memory-propose-move-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_propose_move
     "Propose moving an approved memory note. Files stay unchanged until approval."
     :category :memory
     :input-schema [:map {:closed true}
                    [:note-id :string]
                    [:expected-revision :string]
                    [:folder [:enum "inbox" "preferences" "decisions" "projects" "runbooks"
                              "sessions" "references" "archive"]]
                    [:reason :string]]
     :operation :act :approval-sensitive? false :source :builtin)
    :execute-fn
    (fn [{:keys [note-id expected-revision folder reason]} context]
      (ensure-permission! context :memory-write)
      (memory-update-text
       (memory/propose-vault-note-move! memory-service note-id expected-revision folder
                                        (proposal-opts memory-service context reason))))}))

(defn create-memory-propose-delete-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_propose_delete
     "Propose deleting an approved memory note. Files stay unchanged until approval."
     :category :memory
     :input-schema [:map {:closed true}
                    [:note-id :string]
                    [:expected-revision :string]
                    [:reason :string]]
     :operation :act :approval-sensitive? false :source :builtin)
    :execute-fn
    (fn [{:keys [note-id expected-revision reason]} context]
      (ensure-permission! context :memory-write)
      (memory-update-text
       (memory/propose-vault-note-delete! memory-service note-id expected-revision
                                          (proposal-opts memory-service context reason))))}))

(defn create-memory-propose-merge-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_propose_merge
     "Propose merging two approved notes into target and deleting source after approval. Provide exact merged target fields."
     :category :memory
     :input-schema [:map {:closed true}
                    [:target-id :string]
                    [:source-id :string]
                    [:expected-target-revision :string]
                    [:expected-source-revision :string]
                    [:changes memory-update-changes-schema]
                    [:reason :string]]
     :operation :act :approval-sensitive? false :source :builtin)
    :execute-fn
    (fn [{:keys [target-id source-id expected-target-revision expected-source-revision changes reason]} context]
      (ensure-permission! context :memory-write)
      (memory-update-text
       (memory/propose-vault-note-merge!
        memory-service target-id source-id expected-target-revision expected-source-revision changes
        (proposal-opts memory-service context reason))))}))

(defn create-skill-propose-update-tool [memory-service skills-registry]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :skill_propose_update
     "Propose replacing an existing SKILL.md. Skill stays unchanged until approval."
     :category :memory
     :input-schema [:map {:closed true}
                    [:skill-name :string]
                    [:expected-revision :string]
                    [:content :string]
                    [:reason :string]]
     :operation :act :approval-sensitive? false :source :builtin)
    :execute-fn
    (fn [{:keys [skill-name expected-revision content reason]} context]
      (ensure-permission! context :memory-write)
      (let [skill (get (skills/skill-map skills-registry) skill-name)
            _ (when-not skill
                (throw (ex-info "Skill not found" {:type :not-found :skill-name skill-name})))
            _ (skills/validate-proposed-skill! content)
            before (slurp (:path skill))
            actual-revision (skills/content-revision before)]
        (when-not (= expected-revision actual-revision)
          (throw (ex-info "Skill revision changed"
                          {:type :stale-skill-revision
                           :skill-name skill-name
                           :expected-revision expected-revision
                           :actual-revision actual-revision})))
        (memory-update-text
         (memory/create-memory-proposal!
          memory-service
          {:operation :skill-update
           :target-id skill-name
           :target-path (:path skill)
           :base-revision actual-revision
           :proposed-revision (skills/content-revision content)
           :changes {:skill-name skill-name}
           :proposed-content content
           :diff (str "SKILL UPDATE " skill-name "\n"
                      "--- current\n" before "\n"
                      "+++ proposed\n" content)}
          (proposal-opts memory-service context reason)))))}))

(defn- extract-session-text
  [{:keys [session-id total-message-count included-message-count note-count
           created-count update-count paths update-ids user-profile]}]
  (str "Memory extraction complete for session " session-id
       ". Messages scanned: " included-message-count "/" total-message-count
       ". Changes proposed: " note-count
       " (new: " created-count ", updates: " update-count ")"
       (when (seq paths)
         (str "\nNew notes:\n" (str/join "\n" (map #(str "- " %) paths))))
       (when (seq update-ids)
         (str "\nUpdate proposals:\n"
              (str/join "\n" (map #(str "- " %) update-ids))))
       (when user-profile
         (str "\nUser profile: " (name (:status user-profile))
              " (facts: " (:fact-count user-profile) ")"))))

(defn create-memory-extract-session-tool [memory-service provider user-profile-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :memory_extract_session
     "Run a bounded Dreaming pass over a completed session. Extracts memory, updates the learned USER.md profile when warranted, and returns both outcomes."
     :category :memory
     :input-schema [:map {:closed true}
                    [:session-id {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:request-id {:optional true} [:maybe :string]]
                    [:model {:optional true} [:maybe :string]]]
     :operation :act
     :approval-sensitive? false
     :source :builtin)
    :execute-fn
    (fn [{:keys [session-id limit request-id model]} context]
      (ensure-permission! context :memory-write)
      (let [session-id* (or session-id (:session-id context))
            limit* (extract-session-limit limit)
            extraction (memory/extract-session-and-save-notes!
                        memory-service
                        provider
                        {:session-id session-id*
                         :limit limit*
                         :request-id (or request-id (:request-id context))
                         :model model})
            profile-result (when (user-profile/enabled? user-profile-service)
                             (user-profile/learn-session!
                              user-profile-service session-id* limit*))]
        (extract-session-text (assoc extraction :user-profile profile-result))))}))

(defn create-memory-tools
  ([memory-service] (create-memory-tools memory-service nil))
  ([memory-service provider] (create-memory-tools memory-service provider nil))
  ([memory-service provider user-profile-service]
   (create-memory-tools memory-service provider user-profile-service nil))
  ([memory-service provider user-profile-service skills-registry]
   (cond-> [(create-memory-recall-tool memory-service)
            (create-vault-search-tool memory-service)
            (create-memory-find-similar-tool memory-service)
            (create-scratchpad-read-tool memory-service)
            (create-scratchpad-search-tool memory-service)
            (create-scratchpad-replace-tool memory-service)
            (create-memory-propose-create-tool memory-service)
            (create-memory-propose-update-tool memory-service)
            (create-memory-propose-move-tool memory-service)
            (create-memory-propose-delete-tool memory-service)
            (create-memory-propose-merge-tool memory-service)]
     skills-registry (conj (create-skill-propose-update-tool memory-service skills-registry))
     provider (conj (create-memory-extract-session-tool memory-service
                                                        provider
                                                        user-profile-service)))))

(defn- message-header [{:keys [id session-id session-kind session-title role created-at]}]
  (str "message #" id
       " session=" session-id
       " kind=" (name session-kind)
       " role=" role
       " created-at=" created-at
       (when-not (str/blank? session-title)
         (str " title=" (pr-str session-title)))))

(defn- message-search-text [query rows]
  (if (empty? rows)
    (str "No messages found" (when-not (str/blank? query) (str " for: " query)))
    (str "Message results" (when-not (str/blank? query) (str " for: " query)) "\n"
         (str/join "\n"
                   (map (fn [row]
                          (str "- " (message-header row) "\n  "
                               (focused-chunk query (:content row) max-message-chars)))
                        rows)))))

(defn- message-get-text [message]
  (if-not message
    "Message not found"
    (str (message-header message) "\ncontent:\n" (:content message))))

(defn create-message-search-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :message_search
     "Search persisted user and assistant text. Search defaults to the current session; set all-sessions=true for cross-session search. Cron runs default to all sessions. Use session-kind=chat to exclude cron transcripts. since is inclusive; until is exclusive. Blank query lists messages in the time range. Results include message/session metadata and trimmed content. Excludes tool payloads."
     :category :memory
     :input-schema [:map {:closed true}
                    [:query {:optional true} [:maybe :string]]
                    [:limit {:optional true} [:maybe :int]]
                    [:session-id {:optional true} [:maybe :string]]
                    [:all-sessions {:optional true} [:maybe :boolean]]
                    [:session-kind {:optional true} [:maybe [:enum :chat :cron "chat" "cron"]]]
                    [:since {:optional true} [:maybe :string]]
                    [:until {:optional true} [:maybe :string]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :validate-fn validate-message-search-input
    :execute-fn
    (fn [{:keys [query limit session-id all-sessions session-kind since until]} context]
      (ensure-permission! context :memory-read)
      (let [cross-session? (or all-sessions
                               (and (:cron-run-id context) (nil? session-id)))
            session-id* (when-not cross-session?
                          (or session-id (:session-id context)))
            rows (sqlite/search-messages (:store memory-service)
                                         query
                                         (cond-> {:limit (message-search-limit limit)
                                                  :include-tool-results? false
                                                  :session-kind session-kind
                                                  :since since
                                                  :until until}
                                           session-id* (assoc :session-id session-id*)))]
        (message-search-text query rows)))}))

(defn create-message-get-tool [memory-service]
  (tools/create-tool
   {:description
    (tools/create-tool-description
     :message_get
     "Get complete persisted user or assistant message content by message_search result ID. Content is not trimmed by this tool. Excludes tool payloads. Set all-sessions=true for cross-session IDs; cron runs default to all sessions."
     :category :memory
     :input-schema [:map {:closed true}
                    [:id :int]
                    [:session-id {:optional true} [:maybe :string]]
                    [:all-sessions {:optional true} [:maybe :boolean]]]
     :operation :read
     :parallel-safe? true
     :source :builtin)
    :execute-fn
    (fn [{:keys [id session-id all-sessions]} context]
      (ensure-permission! context :memory-read)
      (let [cross-session? (or all-sessions
                               (and (:cron-run-id context) (nil? session-id)))
            session-id* (when-not cross-session?
                          (or session-id (:session-id context)))]
        (message-get-text
         (sqlite/get-search-message (:store memory-service) id
                                    (cond-> {}
                                      session-id* (assoc :session-id session-id*))))))}))
