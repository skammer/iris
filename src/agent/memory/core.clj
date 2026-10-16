(ns agent.memory.core
  "Memory service facade. Reads vault
   files from bounded roots, recalls relevant context for chat, and extracts
   candidate vault notes from explicit memory consolidation runs."
  (:require
   [agent.memory.scratchpad :as scratchpad]
   [agent.persistence.sqlite :as sqlite]
   [agent.memory.extraction :as extraction]
   [agent.memory.notes :as notes]
   [agent.memory.paths :as paths]
   [agent.memory.policy :as policy]
   [agent.memory.proposals :as proposals]
   [agent.memory.quality :as quality]
   [agent.memory.search :as search]))

(def default-search-limit policy/default-search-limit)
(def default-min-search-score policy/default-min-search-score)
(def default-embedding-candidate-limit policy/default-embedding-candidate-limit)
(def default-low-confidence-threshold policy/default-low-confidence-threshold)
(def default-stale-days policy/default-stale-days)
(def default-session-extract-limit policy/default-session-extract-limit)
(def max-session-extract-limit policy/max-session-extract-limit)
(def session-extract-message-chars policy/session-extract-message-chars)
(def session-extract-transcript-chars policy/session-extract-transcript-chars)
(def default-existing-note-context-limit policy/default-existing-note-context-limit)
(def default-similar-note-threshold policy/default-similar-note-threshold)
(def default-similar-note-limit policy/default-similar-note-limit)
(def existing-note-context-chars policy/existing-note-context-chars)
(def extraction-evidence-user-chars policy/extraction-evidence-user-chars)
(def extraction-evidence-assistant-chars policy/extraction-evidence-assistant-chars)
(def search-memory search/search-memory)
(def reindex-vault! notes/reindex-vault!)
(def search-vault search/search-vault)
(def read-vault-file notes/read-vault-file)
(def similar-vault-notes notes/similar-vault-notes)
(def update-vault-note-iris! notes/update-vault-note-iris!)
(def promote-vault-note! notes/promote-vault-note!)
(def approved-inbox-notes notes/approved-inbox-notes)
(def move-vault-note! notes/move-vault-note!)
(def generate-vault-indexes! notes/generate-vault-indexes!)
(def backup-vault! notes/backup-vault!)
(def cleanup-vault! notes/cleanup-vault!)
(def propose-vault-note-create! proposals/propose-vault-note-create!)
(def get-memory-note-update proposals/get-memory-note-update)
(def list-memory-note-updates proposals/list-memory-note-updates)
(def create-memory-proposal! proposals/create-memory-proposal!)
(def propose-vault-note-move! proposals/propose-vault-note-move!)
(def propose-vault-note-delete! proposals/propose-vault-note-delete!)
(def propose-vault-note-merge! proposals/propose-vault-note-merge!)
(def propose-vault-note-update! proposals/propose-vault-note-update!)
(def decide-memory-note-update! proposals/decide-memory-note-update!)
(def apply-memory-note-update! proposals/apply-memory-note-update!)
(def apply-memory-note-proposal! proposals/apply-memory-note-proposal!)
(def extract-notes extraction/extract-notes)
(def extract-and-save-notes! extraction/extract-and-save-notes!)
(def extract-session-and-save-notes! extraction/extract-session-and-save-notes!)
(def record-recall-latency! quality/record-recall-latency!)
(def recall-latency-metrics quality/recall-latency-metrics)
(def quality-report quality/quality-report)
(def health-check quality/health-check)

(defn create-memory-service
  ([cfg store] (create-memory-service cfg store {}))
  ([{:keys [search vault fs-roots] :as cfg} store {:keys [embedding-provider embedding-model]}]
  (let [{:keys [default-limit max-limit]} (search/search-limit-config search)]
    {:config cfg
     :search-default-limit default-limit
     :search-max-limit max-limit
     :search-min-score (search/search-min-score-config search)
     :vault-roots (paths/canonical-roots (get vault :paths []))
     :vault-writable? (true? (:writable? vault))
     :fs-roots (paths/canonical-roots (or fs-roots []))
     :embedding-provider embedding-provider
     :embedding-model embedding-model
     :update-lock (Object.)
     :recall-metrics (atom {:count 0
                            :last-latency-ms nil
                            :max-latency-ms nil})
     :store store})))

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
