(ns agent.persistence.sqlite.events
  (:require
   [agent.persistence.sqlite.common :as common]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/events.sql")

(defn log-event! [store {:keys [event-type entity-type entity-id request-id payload created-at]}]
  (let [event {:event_type (common/normalize-name event-type)
               :entity_type (common/normalize-name entity-type)
               :entity_id entity-id
               :request_id request-id
               :payload (common/json-string payload)
               :created_at (or created-at (common/now-str))}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (insert-event-sqlvec event))))
    {:event-type (:event_type event)
     :entity-type (:entity_type event)
     :entity-id entity-id
     :request-id request-id
     :payload payload
     :created-at (:created_at event)}))

(defn- row->event [{:keys [id event_type entity_type entity_id request_id payload created_at]}]
  {:id id
   :event-type event_type
   :entity-type entity_type
   :entity-id entity_id
   :request-id request_id
   :payload (common/parse-json-string payload)
   :created-at created_at})

(defn list-events
  ([store] (list-events store {}))
  ([store {:keys [entity-type entity-id request-id event-type after-id limit]
           :or {limit 100}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->event
             (common/select-many conn
                                 (list-events-sqlvec {:entity_type (common/normalize-name entity-type)
                                                      :entity_id entity-id
                                                      :event_type (common/normalize-name event-type)
                                                      :request_id request-id
                                                      :after_id after-id
                                                      :limit limit})
                                 identity))))))

(defn search-events
  ([store query] (search-events store query {}))
  ([store query {:keys [limit] :or {limit 20}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->event
             (common/select-many conn
                                 (search-events-sqlvec {:needle (str "%" (or query "") "%")
                                                        :limit limit})
                                 identity))))))

(defn count-events [store]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (count-events-sqlvec) identity) :n int))))

(defn latest-event-id [store]
  (common/with-connection
    store
    (fn [conn]
      (long (or (some-> (common/select-one conn (latest-event-id-sqlvec) identity) :id)
                0)))))
