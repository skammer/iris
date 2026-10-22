(ns agent.persistence.sqlite.sessions.records
  "Session records, metadata, project discovery and completion logging."
  (:require
   [agent.persistence.sqlite.common :as common]
   [agent.persistence.sqlite.sessions.queries :as queries]))

(defn create-session!
  ([store] (create-session! store nil))
  ([store title] (create-session! store title {}))
  ([store title {:keys [kind metadata] :or {kind :chat}}]
   (let [session {:id (common/uuid-str)
                  :title title
                  :kind (name kind)
                  :metadata_json (common/json-string metadata)
                  :created_at (common/now-str)}]
     (common/with-connection store
       (fn [conn]
         (common/execute! conn (queries/create-session-sqlvec session))))
     {:id (:id session)
      :title title
      :active-mode nil
      :kind kind
      :metadata metadata
      :created-at (:created_at session)})))

(defn- row->session [{:keys [id title active_mode kind metadata_json created_at]}]
  {:id id
   :title title
   :active-mode active_mode
   :kind (keyword (or kind "chat"))
   :metadata (common/parse-json-string metadata_json)
   :created-at created_at})

(defn list-sessions
  ([store] (list-sessions store {}))
  ([store {:keys [kind limit offset] :or {kind :chat offset 0}}]
  (common/with-connection
    store
    (fn [conn]
      (mapv row->session
            (common/select-many conn (queries/list-sessions-sqlvec {:kind (name kind)
                                                          :limit (if (some? limit) (common/bounded-limit limit 50 1000) -1)
                                                          :offset (max 0 (long offset))}) identity))))))

(defn session-kind-counts [store]
  (common/with-connection store
    (fn [conn]
      (into {:chat 0 :cron 0}
            (map (fn [{:keys [kind n]}] [(keyword kind) (long n)]))
            (common/select-many conn (queries/session-kind-counts-sqlvec) identity)))))

(defn session-project-ids [store prefix]
  (let [prefix (subs (str prefix) 0 (min 64 (count (str prefix))))]
    (common/with-connection store
      (fn [conn]
        (mapv :project_id
              (common/select-many conn
                                  (queries/session-project-ids-sqlvec {:prefix prefix :prefix_upper (str prefix "\uffff")})
                                  identity))))))

(defn count-sessions [store]
  (common/count-rows store (queries/count-sessions-sqlvec)))

(defn get-session [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (queries/get-session-sqlvec {:id session-id}) identity)
              row->session))))

(defn set-session-active-mode!
  [store session-id mode]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (queries/update-session-active-mode-sqlvec
                        {:id session-id
                         :active_mode mode}))))
  (get-session store session-id))

(defn set-session-title-if-blank!
  [store session-id title]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (queries/update-session-title-sqlvec
                        {:id session-id
                         :title title}))))
  (get-session store session-id))

(defn compare-and-set-session-title!
  [store session-id expected-title title]
  (common/with-connection
    store
    (fn [conn]
      (pos? (common/execute! conn
                            (queries/compare-and-set-session-title-sqlvec
                             {:id session-id :expected_title expected-title :title title}))))))

(defn update-session-metadata!
  [store session-id metadata]
  (common/with-connection
    store
    (fn [conn]
      (common/execute! conn
                       (queries/update-session-metadata-sqlvec
                        {:id session-id
                         :metadata_json (common/json-string (or metadata {}))}))))
  (get-session store session-id))

(defn session-exists? [store session-id]
  (common/with-connection
    store
    (fn [conn]
      (boolean (common/select-one conn (queries/session-exists-sqlvec {:id session-id}) identity)))))

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
        (common/execute! conn (queries/insert-completion-sqlvec completion))))
    {:session-id session-id
     :provider provider
     :model model
     :prompt prompt
     :response response
     :created-at (:created_at completion)}))
