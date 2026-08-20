(ns agent.persistence.sqlite.events
  (:require
   [agent.persistence.sqlite.common :as common]
   [clojure.string :as str]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/events.sql")

(defn log-event! [store {:keys [event-type entity-type entity-id request-id payload created-at]}]
  (when (str/blank? (common/normalize-name event-type))
    (throw (ex-info "event-type is required"
                    {:type :invalid-event
                     :field :event-type})))
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
                                                      :limit (common/bounded-limit limit)})
                                 identity))))))

(defn get-event [store id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-event-sqlvec {:id id}) identity)
              row->event))))

(defn list-memory-events-window
  [store {:keys [session-id after-id through-id limit] :or {limit 40}}]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->event
            (common/select-many conn
                                (list-memory-events-window-sqlvec
                                 {:session_id session-id
                                  :after_id (long (or after-id 0))
                                  :through_id (long through-id)
                                  :limit (common/bounded-limit limit 40 200)})
                                identity)))))

(defn search-events
  ([store query] (search-events store query {}))
  ([store query {:keys [limit entity-type entity-id] :or {limit 20}}]
   (let [fts-query (common/fts5-query query)]
     (common/with-connection
       store
       (fn [conn]
         (mapv row->event
               (common/select-many conn
                                   (if fts-query
                                     (search-events-fts-sqlvec {:query fts-query
                                                                 :entity_type (common/normalize-name entity-type)
                                                                 :entity_id entity-id
                                                                 :limit (common/bounded-limit limit 20 100)})
                                     (search-events-like-sqlvec {:needle (str "%" (or query "") "%")
                                                                  :entity_type (common/normalize-name entity-type)
                                                                  :entity_id entity-id
                                                                  :limit (common/bounded-limit limit 20 100)}))
                                   identity)))))))

(defn count-events [store]
  (common/count-rows store (count-events-sqlvec)))

(defn latest-event-id [store]
  (common/with-connection
    store
    (fn [conn]
      (long (or (some-> (common/select-one conn (latest-event-id-sqlvec) identity) :id)
                0)))))
