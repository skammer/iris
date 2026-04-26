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

(defn- row->channel-session
  [{:keys [source external_chat_id session_id metadata_json created_at updated_at]}]
  {:source source
   :external-chat-id external_chat_id
   :session-id session_id
   :metadata (common/parse-json-string metadata_json)
   :created-at created_at
   :updated-at updated_at})

(defn get-channel-session-mapping [store source external-chat-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                  (get-channel-session-mapping-sqlvec
                                   {:source (common/normalize-name source)
                                    :external_chat_id (str external-chat-id)})
                                  identity)
              row->channel-session))))

(defn upsert-channel-session-mapping!
  [store {:keys [source external-chat-id session-id metadata]}]
  (let [now (common/now-str)
        mapping {:source (common/normalize-name source)
                 :external_chat_id (str external-chat-id)
                 :session_id session-id
                 :metadata_json (common/json-string metadata)
                 :created_at now
                 :updated_at now}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (upsert-channel-session-mapping-sqlvec mapping))))
    (row->channel-session mapping)))

(defn ensure-channel-session!
  [store {:keys [source external-chat-id title metadata]}]
  (common/with-transaction
    store
    (fn [conn]
      (let [source* (common/normalize-name source)
            external-chat-id* (str external-chat-id)
            now (common/now-str)
            existing (common/select-one
                      conn
                      (get-channel-session-mapping-sqlvec
                       {:source source*
                        :external_chat_id external-chat-id*})
                      identity)]
        (if existing
          (row->channel-session existing)
          (let [session-id (common/uuid-str)
                session {:id session-id
                         :title title
                         :created_at now}
                mapping {:source source*
                         :external_chat_id external-chat-id*
                         :session_id session-id
                         :metadata_json (common/json-string metadata)
                         :created_at now
                         :updated_at now}]
            (common/execute! conn (insert-session-ignore-sqlvec session))
            (common/execute! conn (insert-channel-session-mapping-ignore-sqlvec mapping))
            (row->channel-session
             (common/select-one conn
                                (get-channel-session-mapping-sqlvec
                                 {:source source*
                                  :external_chat_id external-chat-id*})
                                identity))))))))

(defn reset-channel-session!
  [store {:keys [source external-chat-id title metadata]}]
  (let [session (create-session! store title)]
    (upsert-channel-session-mapping!
     store
     {:source source
      :external-chat-id external-chat-id
      :session-id (:id session)
      :metadata metadata})))

(defn get-channel-offset [store source]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn
                                  (get-channel-offset-sqlvec
                                   {:source (common/normalize-name source)})
                                  identity)
              (update :next_offset long)))))

(defn save-channel-offset!
  [store source next-offset]
  (let [row {:source (common/normalize-name source)
             :next_offset (long next-offset)
             :updated_at (common/now-str)}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (upsert-channel-offset-sqlvec row))))
    {:source (:source row)
     :next-offset (:next_offset row)
     :updated-at (:updated_at row)}))

(defn upsert-channel-inbox-update!
  [store source update-id update]
  (let [now (common/now-str)
        row {:source (common/normalize-name source)
             :update_id (long update-id)
             :status "received"
             :raw_json (common/json-string update)
             :attempts 0
             :last_error nil
             :created_at now
             :updated_at now}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (upsert-channel-inbox-sqlvec row))))
    row))

(defn mark-channel-inbox-update!
  [store source update-id status last-error]
  (let [row {:source (common/normalize-name source)
             :update_id (long update-id)
             :status (common/normalize-name status)
             :attempt_delta (if (= "failed" (common/normalize-name status)) 1 0)
             :last_error last-error
             :updated_at (common/now-str)}]
    (common/with-connection store
      (fn [conn]
        (common/execute! conn (update-channel-inbox-status-sqlvec row))))
    row))

(defn get-channel-inbox-update [store source update-id]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one conn
                         (get-channel-inbox-update-sqlvec
                          {:source (common/normalize-name source)
                           :update_id (long update-id)})
                         identity))))
