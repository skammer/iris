(ns agent.api.handlers.sessions
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.validation :as v]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.prompts :as prompts]
   [agent.runtime.compaction :as compaction]
   [agent.sessions.service :as session-service]))

(defn- with-state [system session]
  (assoc session :state (assoc (chat/session-state system (:id session))
                               :active-mode (:active-mode session))))

(defn create [system request]
  (let [{:keys [title]} (h/read-json-body request)
        session (session-service/create-session! system title)]
    (responses/json-response 201 (ser/session->response session))))

(defn list-sessions [system _request]
  (responses/json-response 200
                           {:data (mapv ser/session->response
                                        (map #(with-state system %)
                                             (session-service/list-sessions system)))}))

(defn get-session [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200
                           {:data (ser/session->response
                                   (with-state system
                                     (sqlite/get-session (:store system) session-id)))}))

(defn set-mode [system request session-id]
  (v/ensure-session-exists! system session-id)
  (let [mode (:mode (h/read-json-body request))]
    (when (and mode (not (some #{mode} (prompts/list-modes))))
      (throw (errors/api-error 400
                               "unknown_mode"
                               "Unknown prompt mode"
                               {:mode mode
                                :available_modes (prompts/list-modes)})))
    (responses/json-response
     200
     {:data (ser/session->response
             (with-state system
               (sqlite/set-session-active-mode! (:store system) session-id mode)))})))

(defn list-messages [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200
                           {:data (mapv ser/message->response
                                        (session-service/list-messages system session-id))}))

(defn append-entry [system request session-id]
  (v/ensure-session-exists! system session-id)
  (let [body (h/read-json-body request)
        entry (sqlite/append-entry! (:store system)
                                    session-id
                                    {:id (:id body)
                                     :parent-id (:parent_id body)
                                     :type (:type body)
                                     :payload (or (:payload body) {})
                                     :select-leaf? (not (false? (:select_leaf body)))})]
    (responses/json-response 201 {:data entry})))

(defn list-entries [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200 {:data (sqlite/list-entries (:store system) session-id)}))

(defn current-path [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200 {:data (sqlite/branch-path (:store system) session-id)}))

(defn tree [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200 {:data (sqlite/session-tree (:store system) session-id)}))

(defn select-leaf [system request session-id]
  (v/ensure-session-exists! system session-id)
  (let [body (h/read-json-body request)
        old-leaf (some-> (sqlite/leaf-entry (:store system) session-id) :id)
        new-leaf (:entry_id body)
        branch-summary? (true? (:branch_summary body))
        summary (when (and branch-summary? old-leaf new-leaf (not= old-leaf new-leaf))
                  (compaction/store-branch-summary! (:store system) session-id old-leaf new-leaf))
        entry (try
                (sqlite/select-leaf! (:store system) session-id new-leaf)
                (catch clojure.lang.ExceptionInfo e
                  (throw (errors/domain-error->api-error e))))]
    (responses/json-response 200
                             (cond-> {:data entry}
                               summary (assoc :branch_summary summary)))))

(defn compact [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response
   200
   {:data (compaction/compact-session! (:store system)
                                       session-id
                                       (get-in system [:config :chat :compaction]))}))
