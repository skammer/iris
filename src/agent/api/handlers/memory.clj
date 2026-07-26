(ns agent.api.handlers.memory
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.memory.core :as memory]
   [agent.memory.recall :as recall]))

(defn surfaces [system _request]
  (responses/json-response 200
                           {:data (mapv ser/memory-surface->response
                                        (memory/list-surfaces (:memory-service system)))}))

(defn- normalize-memory-scope [body]
  (when-let [scope (:scope body)]
    (when-not (map? scope)
      (throw (errors/api-error 400 "bad_request" "scope must be an object")))
    {:type (or (:type scope) "global")
     :id (:id scope)}))

(defn recall [system request]
  (let [{:keys [query limit] :as body} (h/read-json-body request)]
    (responses/json-response 200
                             (recall/recall (:memory-service system)
                                            query
                                            (cond-> {}
                                              limit (assoc :limit limit)
                                              (:scope body) (assoc :scope (normalize-memory-scope body)))))))

(defn vault-read [system request]
  (let [{:keys [path]} (h/read-json-body request)]
    (responses/json-response 200
                             {:data (memory/read-vault-file (:memory-service system) path)})))

(defn vault-propose-update [system request]
  (let [{:keys [note_id expected_revision changes evidence]} (h/read-json-body request)]
    (responses/json-response 201
                             {:data (memory/propose-vault-note-update!
                                     (:memory-service system)
                                     note_id
                                     expected_revision
                                     changes
                                     {:source :api
                                      :evidence evidence})})))

(defn vault-reindex [system _request]
  (responses/json-response 200
                           {:data (memory/reindex-vault! (:memory-service system))}))
