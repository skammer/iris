(ns agent.api.errors
  "API error helpers — ex-info construction with HTTP status, error code, and details.")

(defn api-error
  ([status error-code message] (api-error status error-code message nil))
  ([status error-code message details]
   (ex-info message {:type ::api-error
                     :status status
                     :error error-code
                     :details details})))

(defn- error-details
  [error]
  (dissoc (ex-data error) :type))

(defn- permission-denied-details
  [error]
  {:required_permissions (mapv name (:required-permissions (ex-data error)))
   :actual_permissions (mapv name (:actual-permissions (ex-data error)))})

(defn- unknown-prompt-mode-details
  [error]
  (let [{:keys [mode available-modes]} (ex-data error)]
    {:mode mode
     :available_modes available-modes}))

(def ^:private domain-error-table
  "Domain ex-data :type → [status error-code details-fn?]. Default details-fn
   is ex-data minus :type."
  {:tool-not-found [404 "tool_not_found" (constantly nil)]
   :permission-denied [403 "permission_denied" permission-denied-details]
   :validation-failed [400 "validation_failed"]
   :path-not-allowed [403 "path_not_allowed"]
   :not-found [404 "not_found"]
   :not-directory [400 "not_directory"]
   :file-too-large [400 "file_too_large"]
   :timeout [408 "timeout"]
   :approval-not-found [404 "approval_not_found"]
   :approval-not-approved [409 "approval_not_approved"]
   :approval-decision-conflict [409 "approval_decision_conflict"]
   :approval-expired [403 "approval_expired"]
   :approval-forbidden [403 "approval_forbidden"]
   :approval-invalid [403 "approval_invalid"]
   :approval-required [403 "approval_required"]
   :tool-blocked [403 "tool_blocked"]
   :agent-not-found [404 "agent_not_found"]
   :channel-not-found [404 "channel_not_found"]
   :peer-not-found [404 "peer_not_found"]
   :orchestrator-disabled [404 "orchestrator_disabled"]
   :vault-read-only [403 "vault_read_only"]
   :invalid-memory-fact [400 "invalid_memory_fact"]
   :invalid-memory-fact-selector [400 "invalid_memory_fact_selector"]
   :invalid-memory-scope [400 "invalid_memory_scope"]
   :unknown-provider [404 "unknown_provider"]
   :entry-not-found [404 "entry_not_found"]
   :unknown-prompt-mode [400 "unknown_mode" unknown-prompt-mode-details]})

(defn domain-error->api-error
  "Translate a domain ex-info into an api-error when its :type is known;
   otherwise return the error unchanged."
  [error]
  (if-let [[status error-code details-fn] (get domain-error-table (:type (ex-data error)))]
    (api-error status error-code (.getMessage ^Throwable error)
               ((or details-fn error-details) error))
    error))
