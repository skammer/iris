(ns agent.memory.policy)
  "Memory thresholds, limits and configuration switches."

(def default-search-limit 10)

(def default-min-search-score 0.3)

(def default-embedding-candidate-limit 1000)

(def default-low-confidence-threshold 0.6)

(def default-stale-days 180)

(def default-session-extract-limit 80)

(def max-session-extract-limit 200)

(def session-extract-message-chars 1200)

(def session-extract-transcript-chars 20000)

(def default-existing-note-context-limit 8)

(def default-similar-note-threshold 0.42)

(def default-similar-note-limit 20)

(def existing-note-context-chars 1200)

(def extraction-evidence-user-chars 800)

(def extraction-evidence-assistant-chars 0)

(def ^:no-doc allowed-vault-statuses #{"candidate" "approved" "auto_session" "rejected" "superseded"})

(def ^:no-doc allowed-vault-scopes #{"global" "session" "agent" "project"})

(def ^:no-doc allowed-vault-move-roots #{"inbox" "preferences" "decisions" "projects"
                                           "runbooks" "sessions" "references" "archive"})

(def ^:no-doc reviewable-vault-statuses #{"candidate" "approved" "auto_session"})

(def ^:no-doc promotable-vault-statuses #{"candidate" "rejected"})

(def ^:no-doc promotion-folders
  {"preference" "preferences"
   "decision" "decisions"
   "runbook" "runbooks"
   "projectnote" "projects"
   "reference" "references"})

(defn ^:no-doc positive-limit [value fallback]
  (if (and (integer? value) (pos? value))
    value
    fallback))

(defn ^:no-doc embeddings-enabled? [memory-service]
  (true? (get-in memory-service [:config :embeddings :enabled?])))
