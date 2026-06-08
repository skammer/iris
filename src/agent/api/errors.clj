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

(defn- unknown-prompt-mode-details
  [error]
  (let [{:keys [mode available-modes]} (ex-data error)]
    {:mode mode
     :available_modes available-modes}))

(defn domain-error->api-error [error]
  (case (:type (ex-data error))
    :tool-not-found (api-error 404 "tool_not_found" (.getMessage error))
    :permission-denied (api-error 403 "permission_denied" (.getMessage error)
                                  {:required_permissions (mapv name (:required-permissions (ex-data error)))
                                   :actual_permissions (mapv name (:actual-permissions (ex-data error)))})
    :validation-failed (api-error 400 "validation_failed" (.getMessage error) (dissoc (ex-data error) :type))
    :path-not-allowed (api-error 403 "path_not_allowed" (.getMessage error) (error-details error))
    :not-found (api-error 404 "not_found" (.getMessage error) (error-details error))
    :not-directory (api-error 400 "not_directory" (.getMessage error) (error-details error))
    :file-too-large (api-error 400 "file_too_large" (.getMessage error) (error-details error))
    :timeout (api-error 408 "timeout" (.getMessage error) (error-details error))
    :approval-not-found (api-error 404 "approval_not_found" (.getMessage error) (error-details error))
    :approval-not-approved (api-error 409 "approval_not_approved" (.getMessage error) (error-details error))
    :approval-decision-conflict (api-error 409 "approval_decision_conflict" (.getMessage error) (error-details error))
    :approval-expired (api-error 403 "approval_expired" (.getMessage error) (error-details error))
    :approval-forbidden (api-error 403 "approval_forbidden" (.getMessage error) (error-details error))
    :approval-invalid (api-error 403 "approval_invalid" (.getMessage error) (error-details error))
    :approval-required (api-error 403 "approval_required" (.getMessage error) (error-details error))
    :tool-blocked (api-error 403 "tool_blocked" (.getMessage error) (error-details error))
    :run-not-found (api-error 404 "run_not_found" (.getMessage error) (error-details error))
    :agent-not-found (api-error 404 "agent_not_found" (.getMessage error) (error-details error))
    :channel-not-found (api-error 404 "channel_not_found" (.getMessage error) (error-details error))
    :peer-not-found (api-error 404 "peer_not_found" (.getMessage error) (error-details error))
    :lease-not-found (api-error 404 "lease_not_found" (.getMessage error) (error-details error))
    :activity-not-found (api-error 404 "activity_not_found" (.getMessage error) (error-details error))
    :orchestrator-disabled (api-error 404 "orchestrator_disabled" (.getMessage error) (error-details error))
    :illegal-run-transition (api-error 409 "illegal_run_transition" (.getMessage error) (error-details error))
    :vault-read-only (api-error 403 "vault_read_only" (.getMessage error) (error-details error))
    :invalid-memory-fact (api-error 400 "invalid_memory_fact" (.getMessage error) (error-details error))
    :invalid-memory-fact-selector (api-error 400 "invalid_memory_fact_selector" (.getMessage error) (error-details error))
    :invalid-memory-scope (api-error 400 "invalid_memory_scope" (.getMessage error) (error-details error))
    :unknown-provider (api-error 404 "unknown_provider" (.getMessage error) (error-details error))
    :entry-not-found (api-error 404 "entry_not_found" (.getMessage error) (error-details error))
    :command-not-found (api-error 404 "command_not_found" (.getMessage error) (error-details error))
    :unknown-prompt-mode (api-error 400 "unknown_mode" (.getMessage error) (unknown-prompt-mode-details error))
    error))

(def tool-error->api-error domain-error->api-error)
