(ns agent.api.handlers.sessions
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.validation :as v]
   [agent.chat :as chat]
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
                                     (session-service/get-session system session-id)))}))

(defn set-mode [system request session-id]
  (v/ensure-session-exists! system session-id)
  (let [mode (:mode (h/read-json-body request))]
    (try
      (responses/json-response
       200
       {:data (ser/session->response
               (with-state system
                 (session-service/set-mode! system session-id mode)))})
      (catch clojure.lang.ExceptionInfo e
        (throw (errors/domain-error->api-error e))))))

(defn list-messages [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200
                           {:data (mapv ser/message->response
                                        (session-service/list-messages system session-id))}))

(defn append-entry [system request session-id]
  (v/ensure-session-exists! system session-id)
  (let [body (h/read-json-body request)
        entry (session-service/append-entry! system
                                             session-id
                                             {:id (:id body)
                                              :parent-id (:parent_id body)
                                              :type (:type body)
                                              :payload (:payload body)
                                              :select-leaf? (:select_leaf body)})]
    (responses/json-response 201 {:data entry})))

(defn list-entries [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200 {:data (session-service/list-entries system session-id)}))

(defn current-path [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200 {:data (session-service/current-path system session-id)}))

(defn tree [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response 200 {:data (session-service/tree system session-id)}))

(defn select-leaf [system request session-id]
  (v/ensure-session-exists! system session-id)
  (let [body (h/read-json-body request)
        new-leaf (:entry_id body)
        result (try
                 (session-service/select-leaf! system
                                               session-id
                                               new-leaf
                                               {:branch-summary? (true? (:branch_summary body))})
                (catch clojure.lang.ExceptionInfo e
                  (throw (errors/domain-error->api-error e))))]
    (responses/json-response 200
                             (cond-> {:data (:entry result)}
                               (:branch-summary result)
                               (assoc :branch_summary (:branch-summary result))))))

(defn compact [system _request session-id]
  (v/ensure-session-exists! system session-id)
  (responses/json-response
   200
   {:data (session-service/compact! system session-id)}))
