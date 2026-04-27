(ns agent.api.handlers.memory
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.api.validation :as v]
   [agent.memory.core :as memory]))

(defn surfaces [system _request]
  (responses/json-response 200
                           {:data (mapv ser/memory-surface->response
                                        (memory/list-surfaces (:memory-service system)))}))

(defn prompt [system _request]
  (responses/json-response 200
                           (memory/read-prompt-memory (:memory-service system))))

(defn search [system request]
  (let [{:keys [query limit]} (h/read-json-body request)]
    (responses/json-response 200
                             (memory/search-memory (:memory-service system) query
                                                   (cond-> {}
                                                     limit (assoc :limit limit))))))

(defn- normalize-memory-scope [body]
  (when-let [scope (:scope body)]
    (when-not (map? scope)
      (throw (errors/api-error 400 "bad_request" "scope must be an object")))
    {:type (or (:type scope) "global")
     :id (:id scope)}))

(defn fact-save [system request]
  (let [body (h/read-json-body request)]
    (responses/json-response 201
                             {:data (ser/fact->response
                                     (memory/save-memory-fact!
                                      (:memory-service system)
                                      {:subject (:subject body)
                                       :predicate (:predicate body)
                                       :object (:object body)
                                       :confidence (:confidence body)}
                                      (cond-> {:source-session-id (:source_session_id body)
                                               :source-message-ids (:source_message_ids body)
                                               :source-request-id (:source_request_id body)}
                                        (:scope body) (assoc :scope (normalize-memory-scope body)))))})))

(defn fact-search [system request]
  (let [{:keys [query limit] :as body} (h/read-json-body request)]
    (responses/json-response 200
                             {:data (mapv ser/fact->response
                                          (memory/search-facts
                                           (:memory-service system)
                                           query
                                           (cond-> {}
                                             limit (assoc :limit limit)
                                             (:scope body) (assoc :scope (normalize-memory-scope body))
                                             (:all_scopes body) (assoc :all-scopes? true))))})))

(defn vault-read [system request]
  (let [{:keys [path]} (h/read-json-body request)]
    (responses/json-response 200
                             {:data (memory/read-vault-file (:memory-service system) path)})))

(defn vault-write [system request]
  (let [{:keys [path content]} (h/read-json-body request)]
    (responses/json-response 201
                             {:data (memory/write-vault-file! (:memory-service system) path content)})))

(defn- normalize-graph-fact [body]
  (cond-> {:subject (:subject body)
           :predicate (:predicate body)
           :object (:object body)}
    (:id body) (assoc :id (:id body))
    (:type body) (assoc :type (:type body))
    (:source body) (assoc :source (:source body))
    (:session_id body) (assoc :session-id (:session_id body))
    (:tags body) (assoc :tags (vec (:tags body)))))

(defn graph-save [system request]
  (let [fact (normalize-graph-fact (h/read-json-body request))]
    (try
      (responses/json-response 201
                               {:data (memory/save-graph-fact! (:memory-service system) fact)})
      (catch Exception e
        (if (= :graph-memory-disabled (:type (ex-data e)))
          (throw (errors/api-error 409 "graph_memory_disabled" "Graph memory backend is disabled"))
          (throw e))))))

(defn graph-query [system request]
  (let [{:keys [query limit]} (h/read-json-body request)]
    (responses/json-response 200
                             {:data (memory/query-graph-memory (:memory-service system)
                                                               query
                                                               (cond-> {}
                                                                 limit (assoc :limit limit)))})))
