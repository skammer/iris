(ns agent.tools.common.memory.format
  "Bounded text presentations of recall, vault, scratchpad and message results."
  (:require
   [agent.util :as util]
   [clojure.string :as str]))

(def ^:private max-line-chars 600)

(def ^:private max-vault-chars 8000)

(def ^:private max-message-chars 800)

(defn- truncate-text [value max-chars]
  (util/truncate value max-chars #(str " [truncated " % " chars]")))

(defn- compact-whitespace [value]
  (str/trim (str/replace (str (or value "")) #"\s+" " ")))

(defn- tokens [value]
  (->> (str/split (str/lower-case (or value "")) #"\W+")
       (remove str/blank?)
       set))

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

(defn ^:no-doc recall-results-text [query results]
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

(defn ^:no-doc vault-search-text [query results]
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

(defn ^:no-doc similar-notes-text [candidates]
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

(defn ^:no-doc scratchpad-read-text [{:keys [scope path revision content]}]
  (str "Scratchpad " (:type scope)
       (when-let [id (:id scope)] (str "/" id))
       " revision=" revision
       " path=" path
       "\n"
       (truncate-text content max-vault-chars)))

(defn ^:no-doc scratchpad-search-text [{:keys [query scope revision snippets]}]
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

(defn ^:no-doc scratchpad-replace-text [{:keys [scope path revision previous-revision]}]
  (str "Updated scratchpad " (:type scope)
       (when-let [id (:id scope)] (str "/" id))
       " revision=" revision
       " previous=" previous-revision
       " path=" path))

(defn ^:no-doc memory-update-text [update]
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

(defn ^:no-doc extract-session-text
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

(defn- message-header [{:keys [id session-id session-kind session-title role created-at]}]
  (str "message #" id
       " session=" session-id
       " kind=" (name session-kind)
       " role=" role
       " created-at=" created-at
       (when-not (str/blank? session-title)
         (str " title=" (pr-str session-title)))))

(defn ^:no-doc message-search-text [query rows]
  (if (empty? rows)
    (str "No messages found" (when-not (str/blank? query) (str " for: " query)))
    (str "Message results" (when-not (str/blank? query) (str " for: " query)) "\n"
         (str/join "\n"
                   (map (fn [row]
                          (str "- " (message-header row) "\n  "
                               (focused-chunk query (:content row) max-message-chars)))
                        rows)))))

(defn ^:no-doc message-get-text [message]
  (if-not message
    "Message not found"
    (str (message-header message) "\ncontent:\n" (:content message))))
