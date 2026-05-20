(ns agent.api.handlers.sessions
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.validation :as v]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.runtime.compaction :as compaction]))

(defn- with-state [system session]
  (assoc session :state (chat/session-state system (:id session))))

(defn create [system request]
  (let [{:keys [title]} (h/read-json-body request)
        session (sqlite/create-session! (:store system) title)]
    (v/emit-system-event! system
                          {:event-type :session.created
                           :entity-type :session
                           :entity-id (:id session)
                           :payload {:title title}})
    (responses/json-response 201 (ser/session->response session))))

(defn list-sessions [system _request]
  (responses/json-response 200
                           {:data (mapv ser/session->response
                                        (map #(with-state system %)
                                             (sqlite/list-sessions (:store system))))}))

(defn get-session [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200
                           {:data (ser/session->response
                                   (with-state system
                                     (sqlite/get-session (:store system) session-id)))}))

(defn list-messages [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200
                           {:data (mapv ser/message->response
                                        (sqlite/list-messages (:store system) session-id))}))

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
        entry (sqlite/select-leaf! (:store system) session-id new-leaf)]
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
