(ns agent.api.errors
  "API error helpers — ex-info construction with HTTP status, error code, and details.")

(defn api-error
  ([status error-code message] (api-error status error-code message nil))
  ([status error-code message details]
   (ex-info message {:type ::api-error
                     :status status
                     :error error-code
                     :details details})))

(defn domain-error->api-error [error]
  (case (:type (ex-data error))
    :tool-not-found (api-error 404 "tool_not_found" (.getMessage error))
    :permission-denied (api-error 403 "permission_denied" (.getMessage error)
                                  {:required_permissions (mapv name (:required-permissions (ex-data error)))
                                   :actual_permissions (mapv name (:actual-permissions (ex-data error)))})
    :validation-failed (api-error 400 "validation_failed" (.getMessage error) (dissoc (ex-data error) :type))
    :path-not-allowed (api-error 403 "path_not_allowed" (.getMessage error) (dissoc (ex-data error) :type))
    :not-found (api-error 404 "not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :not-directory (api-error 400 "not_directory" (.getMessage error) (dissoc (ex-data error) :type))
    :file-too-large (api-error 400 "file_too_large" (.getMessage error) (dissoc (ex-data error) :type))
    :timeout (api-error 408 "timeout" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-not-found (api-error 404 "approval_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-not-approved (api-error 409 "approval_not_approved" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-decision-conflict (api-error 409 "approval_decision_conflict" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-expired (api-error 403 "approval_expired" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-forbidden (api-error 403 "approval_forbidden" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-invalid (api-error 403 "approval_invalid" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-required (api-error 403 "approval_required" (.getMessage error) (dissoc (ex-data error) :type))
    :tool-blocked (api-error 403 "tool_blocked" (.getMessage error) (dissoc (ex-data error) :type))
    :run-not-found (api-error 404 "run_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :runner-not-found (api-error 404 "runner_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :agent-not-found (api-error 404 "agent_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :channel-not-found (api-error 404 "channel_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :peer-not-found (api-error 404 "peer_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :lease-not-found (api-error 404 "lease_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :activity-not-found (api-error 404 "activity_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :orchestrator-disabled (api-error 404 "orchestrator_disabled" (.getMessage error) (dissoc (ex-data error) :type))
    :illegal-run-transition (api-error 409 "illegal_run_transition" (.getMessage error) (dissoc (ex-data error) :type))
	    :vault-read-only (api-error 403 "vault_read_only" (.getMessage error) (dissoc (ex-data error) :type))
	    :invalid-memory-fact (api-error 400 "invalid_memory_fact" (.getMessage error) (dissoc (ex-data error) :type))
	    :invalid-memory-fact-selector (api-error 400 "invalid_memory_fact_selector" (.getMessage error) (dissoc (ex-data error) :type))
	    :invalid-memory-scope (api-error 400 "invalid_memory_scope" (.getMessage error) (dissoc (ex-data error) :type))
	    :unknown-provider (api-error 404 "unknown_provider" (.getMessage error) (dissoc (ex-data error) :type))
	    :entry-not-found (api-error 404 "entry_not_found" (.getMessage error) (dissoc (ex-data error) :type))
	    :command-not-found (api-error 404 "command_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    error))

(def tool-error->api-error domain-error->api-error)
