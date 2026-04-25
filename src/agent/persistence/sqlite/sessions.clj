(ns agent.persistence.sqlite.sessions
  (:require
   [agent.persistence.sqlite.common :as common]
   [hugsql.core :as hugsql]))

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/sessions.sql")

(defn create-session!
  ([store] (create-session! store nil))
  ([store title]
   (let [session {:id (common/uuid-str)
                  :title title
                  :created_at (common/now-str)}]
     (common/with-connection store
       (fn [conn]
         (common/execute! conn (create-session-sqlvec session))))
     {:id (:id session)
      :title title
      :created-at (:created_at session)})))

(defn list-sessions [store]
  (common/with-connection
    store
    (fn [conn]
      (mapv (fn [{:keys [id title created_at]}]
              {:id id
               :title title
               :created-at created_at})
            (common/select-many conn (list-sessions-sqlvec) identity)))))

(defn session-exists? [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (boolean (common/select-one conn (session-exists-sqlvec {:id session-id}) identity)))))

(defn append-message! [store session-id role content]
  (let [message {:session_id session-id
                 :role role
                 :content content
                 :created_at (common/now-str)}]
    (let [id (common/with-transaction
      store
      (fn [conn]
              (common/execute! conn (insert-message-sqlvec message))
              (:id (common/select-one conn (last-insert-row-id-sqlvec) identity))))]
      {:id id
       :session-id session-id
     :role role
     :content content
       :created-at (:created_at message)})))

(defn list-messages [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (mapv (fn [{:keys [id role content created_at]}]
              {:id id
               :role role
               :content content
               :created-at created_at})
            (common/select-many conn (list-messages-sqlvec {:session_id session-id}) identity)))))

(defn search-messages
  ([store query] (search-messages store query {}))
  ([store query {:keys [limit session-id] :or {limit 20}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv (fn [{:keys [id session_id role content created_at]}]
               {:id id
                :session-id session_id
                :role role
                :content content
                :created-at created_at})
             (common/select-many conn
                                 (search-messages-sqlvec {:needle (str "%" (or query "") "%")
                                                          :session_id session-id
                                                          :limit limit})
                                 identity))))))

(defn log-completion! [store {:keys [session-id provider model prompt response]}]
  (let [completion {:session_id session-id
                    :provider (name provider)
                    :model model
                    :prompt prompt
                    :response response
                    :created_at (common/now-str)}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (insert-completion-sqlvec completion))))
    {:session-id session-id
     :provider provider
     :model model
     :prompt prompt
     :response response
     :created-at (:created_at completion)}))
