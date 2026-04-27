(ns agent.api.errors
  "API error helpers — ex-info construction with HTTP status, error code, and details.")

(defn api-error
  ([status error-code message] (api-error status error-code message nil))
  ([status error-code message details]
   (ex-info message {:type ::api-error
                     :status status
                     :error error-code
                     :details details})))

(defn tool-error->api-error [error]
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
    :approval-required (api-error 403 "approval_required" (.getMessage error) (dissoc (ex-data error) :type))
    :tool-blocked (api-error 403 "tool_blocked" (.getMessage error) (dissoc (ex-data error) :type))
    error))
