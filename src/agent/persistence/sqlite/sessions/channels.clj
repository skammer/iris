(ns agent.persistence.sqlite.sessions.channels
  "Channel mappings, session rotation, offsets and inbox state."
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.sessions.queries :as queries]
   [agent.persistence.sqlite.sessions.records :as records]))

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
                                  (queries/get-channel-session-mapping-sqlvec
                                   {:source (common/normalize-name source)
                                    :external_chat_id (str external-chat-id)})
                                  identity)
              row->channel-session))))

(defn list-channel-session-mappings [store source]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->channel-session
            (common/select-many conn
                                (queries/list-channel-session-mappings-sqlvec
                                 {:source (common/normalize-name source)})
                                identity)))))

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
        (common/execute! conn (queries/upsert-channel-session-mapping-sqlvec mapping))))
    (row->channel-session mapping)))

(defn ensure-channel-session!
  [store {:keys [source external-chat-id title metadata now touch? rotation-reason-fn]}]
  (common/with-transaction
    store
    (fn [conn]
      (let [source* (common/normalize-name source)
            external-chat-id* (str external-chat-id)
            now* (or now (common/now-str))
            lookup {:source source*
                    :external_chat_id external-chat-id*}
            existing-row (common/select-one conn
                                            (queries/get-channel-session-mapping-sqlvec lookup)
                                            identity)
            existing (some-> existing-row row->channel-session)
            reset-reason (when (and existing rotation-reason-fn)
                           (rotation-reason-fn existing))]
        (cond
          reset-reason
          (let [previous-session-id (:session-id existing)
                session-id (common/uuid-str)
                session {:id session-id
                         :title title
                         :created_at now*}
                mapping {:source source*
                         :external_chat_id external-chat-id*
                         :session_id session-id
                         :metadata_json (common/json-string metadata)
                         :created_at now*
                         :updated_at now*}]
            (common/execute! conn (queries/insert-session-ignore-sqlvec session))
            (common/execute! conn (queries/upsert-channel-session-mapping-sqlvec mapping))
            (assoc (row->channel-session
                    (common/select-one conn
                                       (queries/get-channel-session-mapping-sqlvec lookup)
                                       identity))
                   :reset-reason reset-reason
                   :previous-session-id previous-session-id))

          existing
          (do
            (when touch?
              (common/execute! conn
                               (queries/touch-channel-session-mapping-sqlvec
                                {:source source*
                                 :external_chat_id external-chat-id*
                                 :metadata_json (common/json-string metadata)
                                 :updated_at now*})))
            (if touch?
              (row->channel-session
               (common/select-one conn
                                  (queries/get-channel-session-mapping-sqlvec lookup)
                                  identity))
              existing))

          :else
          (let [session-id (common/uuid-str)
                session {:id session-id
                         :title title
                         :created_at now*}
                mapping {:source source*
                         :external_chat_id external-chat-id*
                         :session_id session-id
                         :metadata_json (common/json-string metadata)
                         :created_at now*
                         :updated_at now*}]
            (common/execute! conn (queries/insert-session-ignore-sqlvec session))
            (common/execute! conn (queries/insert-channel-session-mapping-ignore-sqlvec mapping))
            (row->channel-session
             (common/select-one conn
                                (queries/get-channel-session-mapping-sqlvec lookup)
                                identity))))))))

(defn reset-channel-session!
  [store {:keys [source external-chat-id title metadata]}]
  (let [session (records/create-session! store title)]
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
                                  (queries/get-channel-offset-sqlvec
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
        (common/execute! conn (queries/upsert-channel-offset-sqlvec row))))
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
        (common/execute! conn (queries/upsert-channel-inbox-sqlvec row))))
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
        (common/execute! conn (queries/update-channel-inbox-status-sqlvec row))))
    row))

(defn get-channel-inbox-update [store source update-id]
  (common/with-connection
    store
    (fn [conn]
      (common/select-one conn
                         (queries/get-channel-inbox-update-sqlvec
                          {:source (common/normalize-name source)
                           :update_id (long update-id)})
                         identity))))
