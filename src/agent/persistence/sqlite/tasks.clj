(ns agent.persistence.sqlite.tasks
  (:require
   [agent.persistence.sqlite.common :as common]
   [hugsql.core :as hugsql]))

(declare insert-task-sqlvec
         get-task-sqlvec
         get-task-by-idempotency-key-sqlvec
         list-tasks-sqlvec
         mark-task-started-sqlvec
         finish-task-sqlvec
         cancel-task-sqlvec
         cancel-session-tasks-sqlvec
         count-tasks-sqlvec)

(hugsql/def-sqlvec-fns "agent/persistence/sqlite/tasks.sql")

(def terminal-states
  #{"TASK_STATE_COMPLETED" "TASK_STATE_FAILED" "TASK_STATE_CANCELED" "TASK_STATE_REJECTED"})

(defn terminal-state? [status]
  (contains? terminal-states status))

(defn- row->task
  [{:keys [id session_id request_id idempotency_key message_id status prompt
           request_json result_json error created_at started_at finished_at updated_at]}]
  {:id id
   :session-id session_id
   :request-id request_id
   :idempotency-key idempotency_key
   :message-id message_id
   :status status
   :prompt prompt
   :request (common/parse-json-string request_json)
   :result (common/parse-json-string result_json)
   :error error
   :created-at created_at
   :started-at started_at
   :finished-at finished_at
   :updated-at updated_at})

(defn create-task!
  [store {:keys [id session-id request-id idempotency-key message-id status prompt request]}]
  (let [now (common/now-str)
        task {:id (or id (common/uuid-str))
              :session_id session-id
              :request_id request-id
              :idempotency_key idempotency-key
              :message_id message-id
              :status (or status "TASK_STATE_SUBMITTED")
              :prompt prompt
              :request_json (common/json-string request)
              :result_json nil
              :error nil
              :created_at now
              :started_at nil
              :finished_at nil
              :updated_at now}]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (insert-task-sqlvec task))))
    (row->task task)))

(defn get-task [store task-id]
  (common/with-connection
    store
    (fn [conn]
      (some-> (common/select-one conn (get-task-sqlvec {:id task-id}) identity)
              row->task))))

(defn get-task-by-idempotency-key [store idempotency-key]
  (when idempotency-key
    (common/with-connection
      store
      (fn [conn]
        (some-> (common/select-one conn
                                   (get-task-by-idempotency-key-sqlvec
                                    {:idempotency_key idempotency-key})
                                   identity)
                row->task)))))

(defn list-tasks
  ([store] (list-tasks store {}))
  ([store {:keys [session-id limit] :or {limit 50}}]
   (common/with-connection
     store
     (fn [conn]
       (mapv row->task
             (common/select-many conn
                                 (list-tasks-sqlvec
                                  {:session_id session-id
                                   :limit (common/bounded-limit limit 50 100)})
                                 identity))))))

(defn mark-task-started! [store task-id]
  (let [now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn
                         (mark-task-started-sqlvec
                          {:id task-id
                           :status "TASK_STATE_WORKING"
                           :started_at now
                           :updated_at now})))))
  (get-task store task-id))

(defn finish-task!
  [store task-id {:keys [status result error]}]
  (let [now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn
                         (finish-task-sqlvec
                          {:id task-id
                           :status status
                           :result_json (common/json-string result)
                           :error error
                           :finished_at now
                           :updated_at now})))))
  (get-task store task-id))

(defn cancel-task! [store task-id]
  (let [now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (cancel-task-sqlvec {:id task-id
                                                  :finished_at now
                                                  :updated_at now})))))
  (get-task store task-id))

(defn cancel-session-tasks! [store session-id]
  (let [now (common/now-str)]
    (common/with-connection
      store
      (fn [conn]
        (common/execute! conn (cancel-session-tasks-sqlvec {:session_id session-id
                                                           :finished_at now
                                                           :updated_at now}))))))

(defn count-tasks [store]
  (common/count-rows store (count-tasks-sqlvec)))
