(ns agent.api.handlers.a2a
  (:require
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.chat :as chat]
   [agent.persistence.sqlite :as sqlite]
   [agent.sessions.service :as sessions]
   [clojure.string :as str])
  (:import
   (java.util UUID)))

(def ^:private a2a-content-type "application/a2a+json")

(def ^:private terminal-states
  #{"TASK_STATE_COMPLETED" "TASK_STATE_FAILED" "TASK_STATE_CANCELED" "TASK_STATE_REJECTED"})

(defn- nonblank [value]
  (when (some? value)
    (let [value* (str/trim (str value))]
      (when-not (str/blank? value*) value*))))

(defn- a2a-response [status payload]
  (responses/json-response status payload {"Content-Type" a2a-content-type}))

(defn- status-name [status]
  (case status
    400 "BAD_REQUEST"
    401 "UNAUTHORIZED"
    404 "NOT_FOUND"
    409 "CONFLICT"
    500 "INTERNAL"
    "UNKNOWN"))

(defn- a2a-error-response
  ([status reason message] (a2a-error-response status reason message nil))
  ([status reason message metadata]
   (a2a-response
    status
    {:error
     (cond-> {:code status
              :status (status-name status)
              :message message
              :details [{"@type" "type.googleapis.com/google.rpc.ErrorInfo"
                         :reason reason
                         :domain "a2a-protocol.org"}]}
       (seq metadata)
       (assoc-in [:details 0 :metadata] metadata))})))

(defn- request-base-url [request]
  (let [scheme (name (or (:scheme request) :http))
        host (or (h/header request "host")
                 (str (:server-name request)
                      (when-let [port (:server-port request)]
                        (str ":" port))))]
    (str scheme "://" host)))

(defn agent-card [system request]
  (let [base-url (request-base-url request)
        api-key? (boolean (nonblank (get-in system [:config :api :key])))]
    (a2a-response
     200
     (cond-> {:name "Iris"
              :description "Iris asynchronous HTTP task interface"
              :version "0.1.0"
              :supportedInterfaces [{:url base-url
                                     :protocolBinding "HTTP+JSON"
                                     :protocolVersion "1.0"}]
              :capabilities {:streaming false
                             :pushNotifications false
                             :extendedAgentCard false}
              :defaultInputModes ["text/plain"]
              :defaultOutputModes ["text/plain"]
              :skills [{:id "chat"
                        :name "Chat task"
                        :description "Run an Iris chat turn asynchronously and poll the task result."
                        :tags ["chat" "agent" "automation"]
                        :examples ["Summarize recent events for this session"]
                        :inputModes ["text/plain"]
                        :outputModes ["text/plain"]}]}
       api-key?
       (assoc :securitySchemes {:apiKey {:apiKeySecurityScheme {:location "header"
                                                                :name "X-API-Key"}}}
              :securityRequirements [{:apiKey []}])))))

(defn- text-part? [part]
  (string? (:text part)))

(defn- text-prompt [parts]
  (->> parts
       (map :text)
       (str/join "\n")
       nonblank))

(defn- normalize-message [message]
  (let [message-id (nonblank (:messageId message))
        role (:role message)
        parts (:parts message)]
    (cond
      (not message-id)
      {:error (a2a-error-response 400 "INVALID_ARGUMENT" "message.messageId is required")}

      (not= "ROLE_USER" role)
      {:error (a2a-error-response 400 "INVALID_ARGUMENT" "message.role must be ROLE_USER")}

      (not (and (vector? parts) (seq parts)))
      {:error (a2a-error-response 400 "INVALID_ARGUMENT" "message.parts must be a non-empty array")}

      (not-every? text-part? parts)
      {:error (a2a-error-response 400 "CONTENT_TYPE_NOT_SUPPORTED" "Only text parts are supported")}

      :else
      (if-let [prompt (text-prompt parts)]
        {:message-id message-id
         :context-id (nonblank (:contextId message))
         :task-id (nonblank (:taskId message))
         :prompt prompt
         :parts parts}
        {:error (a2a-error-response 400 "INVALID_ARGUMENT" "message.parts text must not be blank")}))))

(defn- task-context-id [system context-id task-id]
  (cond
    task-id
    (if-let [task (sqlite/get-task (:store system) task-id)]
      (let [task-context (:session-id task)]
        (if (and context-id (not= context-id task-context))
          {:error (a2a-error-response 400 "INVALID_ARGUMENT" "message.contextId does not match message.taskId"
                                      {:taskId task-id
                                       :contextId context-id})}
          {:context-id task-context}))
      {:error (a2a-error-response 404 "TASK_NOT_FOUND" "Task not found" {:taskId task-id})})

    context-id
    (if (sessions/session-exists? system context-id)
      {:context-id context-id}
      {:error (a2a-error-response 404 "TASK_NOT_FOUND" "Context not found" {:contextId context-id})})

    :else
    {:context-id (:id (sessions/create-session! system "A2A task"))}))

(defn- idempotency-key [request message-id]
  (or (nonblank (h/header request "idempotency-key"))
      message-id))

(defn- finish-status [result]
  (cond
    (:cancelled? result) "TASK_STATE_CANCELED"
    (:error? result) "TASK_STATE_FAILED"
    :else "TASK_STATE_COMPLETED"))

(defn- run-task-async! [system task messages session-id request-id]
  (future
    (try
      (let [result (chat/run! system {:messages messages
                                      :session-id session-id
                                      :request-id request-id})]
        (sqlite/finish-task! (:store system)
                             (:id task)
                             {:status (finish-status result)
                              :result result
                              :error (when (:error? result) (:content result))}))
      (catch Throwable t
        (sqlite/finish-task! (:store system)
                             (:id task)
                             {:status "TASK_STATE_FAILED"
                              :error (or (.getMessage t) "Task failed")})))))

(defn- live-task-state [system task]
  (let [status (:status task)]
    (if (contains? terminal-states status)
      status
      (let [session-state (chat/session-state system (:session-id task))]
        (if (= (:request-id task) (:active-request-id session-state))
          "TASK_STATE_WORKING"
          status)))))

(defn- task-timestamp [system task state]
  (if (= "TASK_STATE_WORKING" state)
    (or (:active-started-at (chat/session-state system (:session-id task)))
        (:updated-at task))
    (:updated-at task)))

(defn- task-artifacts [task]
  (when (and (= "TASK_STATE_COMPLETED" (:status task))
             (nonblank (get-in task [:result :content])))
    [{:artifactId (str (:id task) "-final")
      :name "final"
      :parts [{:text (get-in task [:result :content])
               :mediaType "text/plain"}]}]))

(defn- status-message [task state]
  (when (or (= "TASK_STATE_FAILED" state)
            (= "TASK_STATE_CANCELED" state))
    {:messageId (str (:id task) "-status")
     :contextId (:session-id task)
     :taskId (:id task)
     :role "ROLE_AGENT"
     :parts [{:text (or (:error task)
                        (case state
                          "TASK_STATE_CANCELED" "Task canceled"
                          "Task failed"))}]}))

(defn- message->a2a [session-id task-id message]
  {:messageId (str "iris-message-" (:id message))
   :contextId session-id
   :taskId task-id
   :role (if (= "user" (:role message)) "ROLE_USER" "ROLE_AGENT")
   :parts [{:text (:content message)
            :mediaType "text/plain"}]})

(defn- parse-long* [value]
  (when-let [value* (nonblank value)]
    (try
      (Long/parseLong value*)
      (catch NumberFormatException _
        nil))))

(defn- query-param [request k]
  (or (get-in request [:parameters :query k])
      (get-in request [:query-params (name k)])
      (get-in request [:query-params (str k)])))

(defn- task->a2a
  ([system task] (task->a2a system task nil))
  ([system task {:keys [history-length]}]
   (let [state (live-task-state system task)
         history (when (and history-length (pos? history-length))
                   (let [messages (->> (sessions/list-messages system (:session-id task))
                                       (filter #(= (:request-id task)
                                                   (get-in % [:metadata :request-id])))
                                       (take-last history-length))]
                     (mapv #(message->a2a (:session-id task) (:id task) %) messages)))]
     (cond-> {:id (:id task)
              :contextId (:session-id task)
              :status (cond-> {:state state
                               :timestamp (task-timestamp system task state)}
                        (status-message task state)
                        (assoc :message (status-message task state)))
              :metadata {:iris/requestId (:request-id task)
                         :iris/messageId (:message-id task)}}
       (seq (task-artifacts task)) (assoc :artifacts (task-artifacts task))
       (seq history) (assoc :history history)))))

(defn send-message [system request]
  (let [body (h/read-json-body request)
        message (:message body)]
    (if-not (map? message)
      (a2a-error-response 400 "INVALID_ARGUMENT" "message is required")
      (let [{:keys [error message-id context-id task-id prompt]}
            (normalize-message message)]
        (if error
          error
          (let [idem-key (idempotency-key request message-id)
                store (:store system)]
            (if-let [existing (sqlite/get-task-by-idempotency-key store idem-key)]
              (a2a-response 200 {:task (task->a2a system existing)})
              (let [{:keys [error context-id]} (task-context-id system context-id task-id)]
                (if error
                  error
                  (let [request-id (str (UUID/randomUUID))
                        messages [{:role "user" :content prompt}]
                        task (sqlite/create-task! store
                                                  {:session-id context-id
                                                   :request-id request-id
                                                   :idempotency-key idem-key
                                                   :message-id message-id
                                                   :prompt prompt
                                                   :request {:message message
                                                             :configuration (:configuration body)
                                                             :metadata (:metadata body)}})]
                    (run-task-async! system task messages context-id request-id)
                    (a2a-response 200 {:task (task->a2a system task)})))))))))))

(defn get-task [system request task-id]
  (if-let [task (sqlite/get-task (:store system) task-id)]
    (a2a-response 200
                  {:task (task->a2a system task
                                    {:history-length (parse-long* (query-param request :historyLength))})})
    (a2a-error-response 404 "TASK_NOT_FOUND" "Task not found" {:taskId task-id})))

(defn list-tasks [system request]
  (let [context-id (nonblank (query-param request :contextId))
        status (nonblank (query-param request :status))
        page-size (or (parse-long* (query-param request :pageSize)) 50)
        history-length (parse-long* (query-param request :historyLength))
        tasks (->> (sqlite/list-tasks (:store system)
                                      {:session-id context-id
                                       :limit 100})
                   (map #(task->a2a system % {:history-length history-length}))
                   (filter #(or (nil? status) (= status (get-in % [:status :state]))))
                   vec)
        page (vec (take (min 100 (max 1 page-size)) tasks))]
    (a2a-response 200 {:tasks page
                       :nextPageToken ""
                       :pageSize (count page)
                       :totalSize (count tasks)})))

(defn- cancel-task-id [task-op]
  (when-let [op (nonblank task-op)]
    (when (str/ends-with? op ":cancel")
      (nonblank (subs op 0 (- (count op) (count ":cancel")))))))

(defn task-operation [system _request task-op]
  (if-let [task-id (cancel-task-id task-op)]
    (if-let [task (sqlite/get-task (:store system) task-id)]
      (do
        (chat/cancel-session! system (:session-id task))
        (sqlite/cancel-session-tasks! (:store system) (:session-id task))
        (a2a-response 200 {:task (task->a2a system (or (sqlite/get-task (:store system) task-id)
                                                       task))}))
      (a2a-error-response 404 "TASK_NOT_FOUND" "Task not found" {:taskId task-id}))
    (a2a-error-response 400 "UNSUPPORTED_OPERATION" "Unsupported task operation")))
