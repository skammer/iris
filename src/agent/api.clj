(ns agent.api
  "Minimal HTTP API for rewritten runtime."
  (:require
   [agent.channels.core :as channel-adapters]
   [agent.llm.core :as llm-core]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.core :as runners]
   [agent.skills :as skills]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.runtime.core :as runtime]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress URLDecoder)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files)
   (java.util.concurrent ExecutorService Executors ThreadFactory)))

(def ^:private server-executors (atom {}))

(defn- daemon-thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable)
        (.setDaemon true)
        (.setName (str "clj-agent-http-" (System/nanoTime)))))))

(defn- read-json-body [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))]
    (if (str/blank? body)
      {}
      (json/parse-string body true))))

(defn- write-json! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/generate-string payload) StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- write-html! [^HttpExchange exchange status html]
  (let [bytes (.getBytes html StandardCharsets/UTF_8)]
    (.add (.getResponseHeaders exchange) "Content-Type" "text/html; charset=utf-8")
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- write-bytes! [^HttpExchange exchange status content-type bytes]
  (.add (.getResponseHeaders exchange) "Content-Type" content-type)
  (.sendResponseHeaders exchange status (long (count bytes)))
  (with-open [os (.getResponseBody exchange)]
    (.write os bytes)))

(defn- api-error
  ([status error-code message] (api-error status error-code message nil))
  ([status error-code message details]
   (ex-info message {:type ::api-error
                     :status status
                     :error error-code
                     :details details})))

(defn- write-error! [exchange error]
  (let [{:keys [type status details]
         error-code :error} (ex-data error)]
    (if (= ::api-error type)
      (write-json! exchange status
                   (cond-> {:error error-code
                            :message (.getMessage error)}
                     details (assoc :details details)))
      (write-json! exchange 500
                   {:error "internal_error"
                    :message (.getMessage error)}))))

(defn- not-found! [exchange]
  (write-json! exchange 404 {:error "not_found"}))

(defn- content-type-for-path [path]
  (cond
    (str/ends-with? path ".css") "text/css; charset=utf-8"
    (str/ends-with? path ".js") "application/javascript; charset=utf-8"
    (str/ends-with? path ".woff2") "font/woff2"
    (str/ends-with? path ".woff") "font/woff"
    (str/ends-with? path ".ttf") "font/ttf"
    (str/ends-with? path ".otf") "font/otf"
    :else "application/octet-stream"))

(defn- handle-public-file [exchange path]
  (let [relative (subs path (count "/public/"))
        file (io/file "public" relative)
        canonical (.getCanonicalPath file)
        root (.getCanonicalPath (io/file "public"))]
    (if (and (.startsWith canonical root) (.isFile file))
      (write-bytes! exchange 200 (content-type-for-path canonical) (Files/readAllBytes (.toPath file)))
      (not-found! exchange))))

(defn- split-path [^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))]
    (->> (str/split path #"/")
         (remove str/blank?)
         vec)))

(defn- decode-url-component [value]
  (URLDecoder/decode (or value "") StandardCharsets/UTF_8))

(defn- merge-param [m key value]
  (let [existing (get m key)]
    (cond
      (nil? existing) (assoc m key value)
      (vector? existing) (assoc m key (conj existing value))
      :else (assoc m key [existing value]))))

(defn- parse-urlencoded [value]
  (if (str/blank? value)
    {}
    (reduce
     (fn [acc pair]
       (let [[raw-k raw-v] (str/split pair #"=" 2)
             key (keyword (decode-url-component raw-k))
             val (decode-url-component raw-v)]
         (merge-param acc key val)))
     {}
     (str/split value #"&"))))

(defn- query-params [^HttpExchange exchange]
  (parse-urlencoded (.getRawQuery (.getRequestURI exchange))))

(defn- read-form-body [^HttpExchange exchange]
  (parse-urlencoded (slurp (.getRequestBody exchange))))

(defn- session->response [session]
  {:id (:id session)
   :title (:title session)
   :created_at (:created-at session)})

(defn- message->response [message]
  {:role (:role message)
   :content (:content message)
   :created_at (:created-at message)})

(defn- tool->response [tool]
  {:name (name (:name tool))
   :description (:description tool)
   :version (:version tool)
   :category (some-> (:category tool) name)
   :required_permissions (mapv name (:required-permissions tool))
   :source (some-> (:source tool) name)
   :source_details (:source-details tool)})

(defn- skill->response [skill]
  {:name (:name skill)
   :description (:description skill)
   :path (:path skill)
   :base_dir (:base-dir skill)
   :source (some-> (:source skill) name)})

(defn- channel-adapter->response [adapter]
  {:name (name (:name adapter))
   :display_name (:display-name adapter)
   :inbound_mode (name (:inbound-mode adapter))
   :capabilities (mapv name (:capabilities adapter))
   :public_url_required (:public-url-required? adapter)
   :source (some-> (:source adapter) name)})

(defn- agent->response [agent]
  {:id (:id agent)
   :name (:name agent)
   :role (:role agent)
   :parent_id (:parent-id agent)
   :status (:status agent)
   :created_at (:created-at agent)
   :message_count (:message-count agent)})

(defn- channel->response [channel]
  {:id (:id channel)
   :name (:name channel)
   :participants (vec (:participants channel))
   :created_at (:created-at channel)
   :message_count (:message-count channel)})

(defn- channel-message->response [message]
  {:id (:id message)
   :sender_id (:sender-id message)
   :channel_id (:channel-id message)
   :content (:content message)
   :created_at (:created-at message)})

(defn- event->response [event]
  {:id (:id event)
   :event_type (:event-type event)
   :entity_type (:entity-type event)
   :entity_id (:entity-id event)
   :request_id (:request-id event)
   :payload (:payload event)
   :created_at (:created-at event)})

(defn- approval->response [approval]
  {:id (:id approval)
   :tool_name (:tool-name approval)
   :status (:status approval)
   :input (:input approval)
   :requested_by (:requested-by approval)
   :reason (:reason approval)
   :actor (:actor approval)
   :decision_reason (:decision-reason approval)
   :created_at (:created-at approval)
   :decided_at (:decided-at approval)})

(defn- run->response [run]
  (when run
    {:id (:id run)
     :agent_id (:agent-id run)
     :parent_run_id (:parent-run-id run)
     :lease_id (:lease-id run)
     :name (:name run)
     :substrate (:substrate run)
     :status (:status run)
     :capabilities (:capabilities run)
     :network_identity (:network-identity run)
     :bootstrap_spec (:bootstrap-spec run)
     :runner_metadata (:runner-metadata run)
     :runner_options (:runner-options run)
     :requested_by (:requested-by run)
     :last_error (:last-error run)
     :created_at (:created-at run)
     :started_at (:started-at run)
     :finished_at (:finished-at run)
     :lease (some-> (:lease run)
                    (update-keys #(keyword (str/replace (name %) "-" "_"))))
     :heartbeat (some-> (:heartbeat run)
                        (update-keys #(keyword (str/replace (name %) "-" "_"))))
     :checkpoint (some-> (:checkpoint run)
                         (update-keys #(keyword (str/replace (name %) "-" "_"))))
     :pending_commands (mapv #(update-keys % (fn [k] (keyword (str/replace (name k) "-" "_"))))
                             (:pending-commands run))}))

(defn- heartbeat->response [heartbeat]
  {:run_id (:run-id heartbeat)
   :sequence_no (:sequence-no heartbeat)
   :status (:status heartbeat)
   :metrics (:metrics heartbeat)
   :observed_at (:observed-at heartbeat)})

(defn- checkpoint->response [checkpoint]
  {:id (:id checkpoint)
   :run_id (:run-id checkpoint)
   :sequence_no (:sequence-no checkpoint)
   :checkpoint_type (:checkpoint-type checkpoint)
   :state (:state checkpoint)
   :created_at (:created-at checkpoint)})

(defn- run-command->response [command]
  {:id (:id command)
   :run_id (:run-id command)
   :command_type (:command-type command)
   :payload (:payload command)
   :status (:status command)
   :created_at (:created-at command)
   :acknowledged_at (:acknowledged-at command)
   :completed_at (:completed-at command)
   :error (:error command)})

(defn- memory-surface->response [surface]
  {:name (name (:name surface))
   :type (name (:type surface))
   :writable (:writable surface)
   :enabled (:enabled surface)
   :paths (:paths surface)
   :default_limit (:default-limit surface)})

(defn- session-exists? [system session-id]
  (sqlite/session-exists? (:store system) session-id))

(def ^:private valid-roles #{"system" "user" "assistant" "tool"})

(defn- valid-message? [message]
  (and (map? message)
       (string? (:role message))
       (contains? valid-roles (:role message))
       (string? (:content message))
       (not (str/blank? (:content message)))))

(defn- ensure-session-id! [session-id]
  (when (and (some? session-id) (not (string? session-id)))
    (throw (api-error 400 "bad_request" "session_id must be a string"))))

(defn- ensure-session-exists! [system session-id]
  (when (and session-id (not (session-exists? system session-id)))
    (throw (api-error 404 "session_not_found" "Session not found"))))

(defn- ensure-string! [field value]
  (when-not (and (string? value) (not (str/blank? value)))
    (throw (api-error 400 "bad_request"
                      (str (name field) " must be a non-blank string")))))

(defn- ensure-string-vec! [field value]
  (when-not (and (vector? value) (every? string? value))
    (throw (api-error 400 "bad_request"
                      (str (name field) " must be a vector of strings")))))

(defn- tool-error->api-error [error]
  (case (:type (ex-data error))
    :tool-not-found (api-error 404 "tool_not_found" (.getMessage error))
    :permission-denied (api-error 403 "permission_denied" (.getMessage error)
                                  {:required_permissions (mapv name (:required-permissions (ex-data error)))
                                   :actual_permissions (mapv name (:actual-permissions (ex-data error)))})
    :validation-failed (api-error 400 "validation_failed" (.getMessage error) (dissoc (ex-data error) :type))
    :path-not-allowed (api-error 403 "path_not_allowed" (.getMessage error) (dissoc (ex-data error) :type))
    :not-found (api-error 404 "not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :not-directory (api-error 400 "not_directory" (.getMessage error) (dissoc (ex-data error) :type))
    :file-too-large (api-error 400 "file_too_large" (.getMessage error) (dissoc (ex-data error) :type))
    :timeout (api-error 408 "timeout" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-not-found (api-error 404 "approval_not_found" (.getMessage error) (dissoc (ex-data error) :type))
    :approval-not-approved (api-error 409 "approval_not_approved" (.getMessage error) (dissoc (ex-data error) :type))
    :tool-blocked (api-error 403 "tool_blocked" (.getMessage error) (dissoc (ex-data error) :type))
    error))

(defn- emit-system-event!
  [system event]
  (if-let [sink (:event-sink system)]
    (sink event)
    (sqlite/log-event! (:store system) event)))

(defn- append-session-message!
  [system session-id role content]
  (let [message (sqlite/append-message! (:store system) session-id role content)]
    (emit-system-event! system
                        {:event-type :message.appended
                         :entity-type :session
                         :entity-id session-id
                         :payload {:role role
                                   :content content}})
    message))

(defn- normalize-chat-request [body]
  (let [messages (:messages body)
        prompt (:prompt body)
        session-id (:session_id body)
        stream? (true? (:stream body))]
    (ensure-session-id! session-id)
    (when (and messages prompt)
      (throw (api-error 400 "bad_request" "Provide either messages or prompt, not both")))
    (cond
      (some? messages)
      (do
        (when-not (vector? messages)
          (throw (api-error 400 "bad_request" "messages must be a vector")))
        (when (empty? messages)
          (throw (api-error 400 "bad_request" "messages must not be empty")))
        (when-not (every? valid-message? messages)
          (throw (api-error 400 "bad_request"
                            "each message must include valid string role and content")))
        {:messages messages
         :session-id session-id
         :stream? stream?})

      (string? prompt)
      (do
        (when (str/blank? prompt)
          (throw (api-error 400 "bad_request" "prompt must not be blank")))
        {:messages [{:role "user" :content prompt}]
         :session-id session-id
         :stream? stream?})

      :else
      (throw (api-error 400 "bad_request" "Expected messages vector or prompt string")))))

(defn- session-context-messages [system session-id]
  (if session-id
    (mapv (fn [{:keys [role content]}]
            {:role role
             :content content})
          (sqlite/list-messages (:store system) session-id))
    []))

(defn- attach-session-context [system {:keys [messages session-id] :as request}]
  (if (and session-id (seq messages))
    (assoc request :messages (into (session-context-messages system session-id) messages))
    request))

(defn- latest-user-prompt [messages]
  (:content (last (filter #(= "user" (:role %)) messages))))

(defn- persist-user-message! [system messages session-id]
  (when-let [prompt (and session-id (latest-user-prompt messages))]
    (append-session-message! system session-id "user" prompt)))

(defn- persist-completion! [system messages content {:keys [session-id]}]
  (let [provider (name (get-in system [:config :llm :provider]))
        prompt (latest-user-prompt messages)]
    (when session-id
      (append-session-message! system session-id "assistant" content))
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider provider
                             :model (get-in system [:config :llm :model])
                             :prompt prompt
                             :response content})
    (emit-system-event! system
                        {:event-type :completion.completed
                         :entity-type :session
                         :entity-id session-id
                         :payload {:provider provider
                                   :model (get-in system [:config :llm :model])}})))

(defn- complete! [system messages {:keys [session-id]}]
  (persist-user-message! system messages session-id)
  (let [provider (:llm-provider system)
        content (llm-core/complete provider messages {})]
    (persist-completion! system messages content {:session-id session-id})
    {:content content}))

(defn- openai-style-completion [system session-id content]
  {:id (str "chatcmpl-" (System/currentTimeMillis))
   :object "chat.completion"
   :session_id session-id
   :provider (name (get-in system [:config :llm :provider]))
   :model (get-in system [:config :llm :model])
   :choices [{:index 0
              :finish_reason "stop"
              :message {:role "assistant"
                        :content content}}]})

(defn- write-sse-bytes! [stream text]
  (.write stream (.getBytes text StandardCharsets/UTF_8))
  (.flush stream))

(defn- write-sse-chunk! [stream payload]
  (write-sse-bytes! stream (str "data: " (json/generate-string payload) "\n\n")))

(defn- write-sse-done! [stream]
  (write-sse-bytes! stream "data: [DONE]\n\n"))

(defn- compact-html [html]
  (-> html
      (str/replace #"\s+" " ")
      str/trim))

(defn- write-datastar-patch! [stream html]
  (write-sse-bytes! stream
                    (str "event: datastar-patch-elements\n"
                         "data: elements "
                         (compact-html html)
                         "\n\n")))

(defn- handle-chat-completions-stream [system exchange messages session-id]
  (let [stream-id (str "chatcmpl-" (System/currentTimeMillis))
        provider (name (get-in system [:config :llm :provider]))
        model (get-in system [:config :llm :model])
        chunks (llm-core/stream (:llm-provider system) messages {})]
    (try
      (persist-user-message! system messages session-id)
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (loop [parts []]
          (if-let [chunk (async/<!! chunks)]
            (if (map? chunk)
              (do
                (write-sse-chunk! stream {:error "stream_error"
                                          :message (or (:error chunk) "Provider stream failed")})
                (write-sse-done! stream))
              (do
                (write-sse-chunk! stream {:id stream-id
                                          :object "chat.completion.chunk"
                                          :session_id session-id
                                          :provider provider
                                          :model model
                                          :choices [{:index 0
                                                     :delta {:content chunk}
                                                     :finish_reason nil}]})
                (recur (conj parts chunk))))
            (let [content (apply str parts)]
              (persist-completion! system messages content {:session-id session-id})
              (write-sse-chunk! stream {:id stream-id
                                        :object "chat.completion.chunk"
                                        :session_id session-id
                                        :provider provider
                                        :model model
                                        :choices [{:index 0
                                                   :delta {}
                                                   :finish_reason "stop"}]})
              (write-sse-done! stream)))))
      (catch Exception e
        (throw e)))))

(defn- handle-health [system exchange]
  (write-json! exchange 200 {:ok true
                             :llm (llm-core/health-check (:llm-provider system))
                             :storage (sqlite/health-check (:store system))
                             :tools (tools/registry-health (:tool-registry system))
                             :skills (skills/registry-health (:skills-registry system))
                             :memory (memory/health-check (:memory-service system))
                             :channel-adapters (channel-adapters/registry-health (:channel-adapter-registry system))
                             :orchestrator (orchestrator/health-check (:orchestrator system))
                             :provider (get-in system [:config :llm :provider])}))

(defn- handle-list-tools [system exchange]
  (write-json! exchange 200 {:data (mapv tool->response
                                         (tools/list-tools (:tool-registry system)))}))

(defn- handle-list-skills [system exchange]
  (write-json! exchange 200 {:data (mapv skill->response
                                         (skills/list-skills (:skills-registry system)))}))

(defn- handle-list-channel-adapters [system exchange]
  (write-json! exchange 200 {:data (mapv channel-adapter->response
                                         (channel-adapters/list-adapters (:channel-adapter-registry system)))}))

(defn- handle-list-events [system exchange]
  (write-json! exchange 200 {:data (mapv event->response
                                         (sqlite/list-events (:store system) {:limit 100}))}))

(defn- handle-events-stream [system exchange]
  (let [stream-id (str "events-" (System/currentTimeMillis))
        ch (async/chan 64)]
    (async/tap (get-in system [:event-bus :mult]) ch)
    (try
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (loop []
          (when-let [event (async/<!! ch)]
            (write-sse-chunk! stream {:id stream-id
                                      :object "event.chunk"
                                      :event (event->response event)})
            (recur))))
      (catch Exception e
        (throw e))
      (finally
        (async/untap (get-in system [:event-bus :mult]) ch)
        (async/close! ch)))))

(defn- handle-memory-surfaces [system exchange]
  (write-json! exchange 200 {:data (mapv memory-surface->response
                                         (memory/list-surfaces (:memory-service system)))}))

(defn- handle-memory-prompt [system exchange]
  (write-json! exchange 200
               (memory/read-prompt-memory (:memory-service system))))

(defn- handle-memory-search [system exchange]
  (let [body (read-json-body exchange)
        query (:query body)
        limit (:limit body)]
    (ensure-string! :query query)
    (when (and (some? limit) (not (integer? limit)))
      (throw (api-error 400 "bad_request" "limit must be an integer")))
    (write-json! exchange 200
                 (memory/search-memory (:memory-service system) query
                                       (cond-> {}
                                         limit (assoc :limit limit))))))

(defn- normalize-graph-fact [body]
  (doseq [field [:subject :predicate :object]]
    (ensure-string! field (get body field)))
  (cond-> {:subject (:subject body)
           :predicate (:predicate body)
           :object (:object body)}
    (:id body) (assoc :id (:id body))
    (:type body) (assoc :type (:type body))
    (:source body) (assoc :source (:source body))
    (:session_id body) (assoc :session-id (:session_id body))
    (:tags body) (assoc :tags (vec (:tags body)))))

(defn- handle-memory-graph-save [system exchange]
  (let [fact (normalize-graph-fact (read-json-body exchange))]
    (try
      (write-json! exchange 201
                   {:data (memory/save-graph-fact! (:memory-service system) fact)})
      (catch Exception e
        (if (= :graph-memory-disabled (:type (ex-data e)))
          (throw (api-error 409 "graph_memory_disabled" "Graph memory backend is disabled"))
          (throw e))))))

(defn- handle-memory-graph-query [system exchange]
  (let [body (read-json-body exchange)
        query (:query body)
        limit (:limit body)]
    (when (and (some? query) (not (string? query)))
      (throw (api-error 400 "bad_request" "query must be a string")))
    (when (and (some? limit) (not (integer? limit)))
      (throw (api-error 400 "bad_request" "limit must be an integer")))
    (write-json! exchange 200
                 {:data (memory/query-graph-memory (:memory-service system)
                                                   query
                                                   (cond-> {}
                                                     limit (assoc :limit limit)))})))

(defn- handle-create-agent [system exchange]
  (let [body (read-json-body exchange)
        name (or (:name body) "Subagent")
        role (or (:role body) "worker")
        parent-id (:parent_id body)
        system-prompt (:system_prompt body)]
    (when parent-id
      (ensure-string! :parent_id parent-id))
    (when system-prompt
      (ensure-string! :system_prompt system-prompt))
    (write-json! exchange 201
                 (agent->response
                  (orchestrator/spawn-agent! (:orchestrator system)
                                             {:name name
                                              :role role
                                              :parent-id parent-id
                                              :system-prompt system-prompt})))))

(defn- handle-list-agents [system exchange]
  (write-json! exchange 200 {:data (mapv agent->response
                                         (orchestrator/list-agents (:orchestrator system)))}))

(defn- handle-agent-messages [system exchange agent-id]
  (try
    (write-json! exchange 200 {:data (mapv message->response
                                           (orchestrator/list-agent-messages (:orchestrator system) agent-id))})
    (catch Exception e
      (if (= :agent-not-found (:type (ex-data e)))
        (throw (api-error 404 "agent_not_found" "Agent not found"))
        (throw e)))))

(defn- handle-agent-message [system exchange agent-id]
  (let [body (read-json-body exchange)
        role (or (:role body) "user")
        content (:content body)]
    (ensure-string! :content content)
    (try
      (let [result (orchestrator/send-agent-message! (:orchestrator system)
                                                     (:llm-provider system)
                                                     agent-id
                                                     {:role role
                                                      :content content})]
        (write-json! exchange 200
                     {:agent (agent->response (:agent result))
                      :input (message->response (:input result))
                      :response (message->response (:response result))}))
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (api-error 404 "agent_not_found" "Agent not found"))
          (throw e))))))

(defn- handle-consume-agent-inbox [system exchange agent-id]
  (try
    (let [result (orchestrator/consume-agent-inbox! (:orchestrator system)
                                                    (:llm-provider system)
                                                    agent-id)]
      (write-json! exchange 200
                   {:agent (agent->response (:agent result))
                    :consumed (:consumed result)
                    :response (some-> (:response result) message->response)}))
    (catch Exception e
      (if (= :agent-not-found (:type (ex-data e)))
        (throw (api-error 404 "agent_not_found" "Agent not found"))
        (throw e)))))

(defn- handle-create-channel [system exchange]
  (let [body (read-json-body exchange)
        name (or (:name body) "Channel")
        participants (or (:participants body) [])]
    (ensure-string-vec! :participants participants)
    (try
      (write-json! exchange 201
                   (channel->response
                    (orchestrator/create-channel! (:orchestrator system)
                                                  {:name name
                                                   :participants participants})))
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (api-error 404 "agent_not_found" "Channel participant not found"))
          (throw e))))))

(defn- handle-list-channels [system exchange]
  (write-json! exchange 200 {:data (mapv channel->response
                                         (orchestrator/list-channels (:orchestrator system)))}))

(defn- handle-channel-messages [system exchange channel-id]
  (try
    (write-json! exchange 200 {:data (mapv channel-message->response
                                           (orchestrator/list-channel-messages (:orchestrator system) channel-id))})
    (catch Exception e
      (if (= :channel-not-found (:type (ex-data e)))
        (throw (api-error 404 "channel_not_found" "Channel not found"))
        (throw e)))))

(defn- handle-channel-message [system exchange channel-id]
  (let [body (read-json-body exchange)
        sender-id (:sender_id body)
        content (:content body)]
    (ensure-string! :sender_id sender-id)
    (ensure-string! :content content)
    (try
      (write-json! exchange 201
                   (channel-message->response
                    (orchestrator/post-channel-message! (:orchestrator system)
                                                        channel-id
                                                        {:sender-id sender-id
                                                         :content content})))
      (catch Exception e
        (case (:type (ex-data e))
          :channel-not-found (throw (api-error 404 "channel_not_found" "Channel not found"))
          :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
          :permission-denied (throw (api-error 403 "permission_denied" "Sender is not a participant"))
          (throw e))))))

(defn- handle-create-session [system exchange]
  (let [body (read-json-body exchange)
        title (:title body)]
    (when (and (some? title) (not (string? title)))
      (throw (api-error 400 "bad_request" "title must be a string")))
    (let [session (sqlite/create-session! (:store system) title)]
      (emit-system-event! system
                          {:event-type :session.created
                           :entity-type :session
                           :entity-id (:id session)
                           :payload {:title title}})
      (write-json! exchange 201 (session->response session)))))

(defn- handle-list-sessions [system exchange]
  (write-json! exchange 200 {:data (mapv session->response (sqlite/list-sessions (:store system)))}))

(defn- handle-list-messages [system exchange session-id]
  (ensure-session-exists! system session-id)
  (write-json! exchange 200 {:data (mapv message->response
                                         (sqlite/list-messages (:store system) session-id))}))

(defn- system-list-runs [system]
  (runtime/list-runs (:runtime-service system)))

(defn- system-get-run [system run-id]
  (runtime/get-run (:runtime-service system) run-id))

(defn- system-request-run! [system request]
  (runtime/request-run! (:runtime-service system)
                        (runtime/create-run-request request)))

(defn- system-runner-status [system run-id]
  (when-let [run (system-get-run system run-id)]
    (when-let [runner (get (:runner-registry system) (keyword (:substrate run)))]
      (runners/status runner run-id))))

(defn- system-launch-run! [system run-id]
  (let [run (or (system-get-run system run-id)
                (throw (api-error 404 "run_not_found" "Run not found")))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (api-error 404 "runner_not_found" "Runner not found")))]
    (let [launch-result (runners/launch runner
                                        (runners/create-run-spec
                                         {:run-id (:id run)
                                          :agent-id (:agent-id run)
                                          :parent-run-id (:parent-run-id run)
                                          :lease-id (:lease-id run)
                                          :name (:name run)
                                          :substrate (keyword (:substrate run))
                                          :capabilities (:capabilities run)
                                          :network-identity (:network-identity run)
                                          :bootstrap-token (:bootstrap-token run)
                                          :bootstrap-spec (:bootstrap-spec run)
                                          :requested-by (:requested-by run)
                                          :runner-options (:runner-options run)}))]
      (runtime/transition-run! (:runtime-service system) run-id :launched {:runner-metadata launch-result})
      (system-get-run system run-id))))

(defn- system-signal-run! [system run-id command]
  (let [run (or (system-get-run system run-id)
                (throw (api-error 404 "run_not_found" "Run not found")))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (api-error 404 "runner_not_found" "Runner not found")))
        signal-result (runners/signal runner run-id command)
        command-type (keyword (:command-type command))]
    (when (contains? #{:cancel :terminate :kill} command-type)
      (runtime/transition-run! (:runtime-service system)
                               run-id
                               :cancelled
                               {:runner-metadata (merge (:runner-metadata run) signal-result)}))
    signal-result))

(defn- normalize-run-request [body]
  (let [capabilities (:capabilities body)
        runner-options (:runner_options body)
        network-identity (:network_identity body)]
    (when (and (some? capabilities)
               (not (and (vector? capabilities) (every? string? capabilities))))
      (throw (api-error 400 "bad_request" "capabilities must be a vector of strings")))
    (when (and (some? runner-options) (not (map? runner-options)))
      (throw (api-error 400 "bad_request" "runner_options must be an object")))
    (when (and (some? network-identity) (not (map? network-identity)))
      (throw (api-error 400 "bad_request" "network_identity must be an object")))
    {:agent-id (:agent_id body)
     :parent-run-id (:parent_run_id body)
     :name (:name body)
     :substrate (or (some-> (:substrate body) keyword) :local-process)
     :capabilities (or capabilities [])
     :network-identity network-identity
     :runner-options runner-options
     :requested-by (or (:requested_by body) "api")
     :auto-launch? (true? (:auto_launch body))}))

(defn- handle-list-runs [system exchange]
  (write-json! exchange 200 {:data (mapv run->response (system-list-runs system))}))

(defn- handle-get-run [system exchange run-id]
  (if-let [run (system-get-run system run-id)]
    (write-json! exchange 200
                 {:data (assoc (run->response run)
                               :runner_status (system-runner-status system run-id))})
    (throw (api-error 404 "run_not_found" "Run not found"))))

(defn- handle-create-run [system exchange]
  (let [request (normalize-run-request (read-json-body exchange))
        run (system-request-run! system request)
        launched-run (when (:auto-launch? request)
                       (system-launch-run! system (:id run)))]
    (write-json! exchange
                 201
                 {:data (run->response (or launched-run (system-get-run system (:id run))))})))

(defn- handle-launch-run [system exchange run-id]
  (write-json! exchange 200 {:data (run->response (system-launch-run! system run-id))}))

(defn- handle-signal-run [system exchange run-id]
  (let [body (read-json-body exchange)
        command-type (:command_type body)]
    (ensure-string! :command_type command-type)
    (write-json! exchange 200
                 {:data (system-signal-run! system run-id {:command-type command-type})})))

(defn- parse-int-param [value field]
  (when (some? value)
    (try
      (Integer/parseInt (str value))
      (catch Exception _
        (throw (api-error 400 "bad_request" (str field " must be an integer")))))))

(defn- handle-run-heartbeats [system exchange run-id]
  (let [params (query-params exchange)
        limit (parse-int-param (:limit params) "limit")
        since-sequence (parse-int-param (:since_sequence params) "since_sequence")]
    (write-json! exchange 200
                 {:data (mapv heartbeat->response
                              (runtime/list-heartbeats (:runtime-service system) run-id
                                                       (cond-> {}
                                                         limit (assoc :limit limit)
                                                         since-sequence (assoc :since-sequence since-sequence))))})))

(defn- handle-run-checkpoints [system exchange run-id]
  (let [params (query-params exchange)
        limit (parse-int-param (:limit params) "limit")
        since-sequence (parse-int-param (:since_sequence params) "since_sequence")]
    (write-json! exchange 200
                 {:data (mapv checkpoint->response
                              (runtime/list-checkpoints (:runtime-service system) run-id
                                                        (cond-> {}
                                                          limit (assoc :limit limit)
                                                          since-sequence (assoc :since-sequence since-sequence))))})))

(defn- handle-run-commands [system exchange run-id]
  (let [params (query-params exchange)
        limit (parse-int-param (:limit params) "limit")
        status (:status params)]
    (write-json! exchange 200
                 {:data (mapv run-command->response
                              (runtime/list-commands (:runtime-service system) run-id
                                                     (cond-> {}
                                                       limit (assoc :limit limit)
                                                       status (assoc :status status))))})))

(defn- handle-run-events [system exchange run-id]
  (let [params (query-params exchange)
        limit (parse-int-param (:limit params) "limit")]
    (write-json! exchange 200
                 {:data (mapv event->response
                              (sqlite/list-events (:store system)
                                                  (cond-> {:entity-type :agent_run
                                                           :entity-id run-id}
                                                    limit (assoc :limit limit))))})))

(defn- handle-chat-completions [system exchange]
  (let [{:keys [messages session-id stream?]}
        (attach-session-context system
                                (normalize-chat-request (read-json-body exchange)))]
    (ensure-session-exists! system session-id)
    (if stream?
      (handle-chat-completions-stream system exchange messages session-id)
      (let [result (complete! system messages {:session-id session-id})]
        (write-json! exchange 200 (openai-style-completion system session-id (:content result)))))))

(defn- normalize-permissions [value]
  (cond
    (nil? value) #{}
    (string? value) #{(keyword value)}
    (vector? value) (set (map keyword value))
    :else (throw (api-error 400 "bad_request" "permissions must be a string or vector of strings"))))

(defn- tool-input-from-map [tool-name body]
  (case tool-name
    :fs (cond-> {:action (:action body)
                 :path (:path body)}
          (contains? body :content) (assoc :content (:content body)))
    :shell (cond-> {:command (:command body)}
             (not (str/blank? (:working_dir body))) (assoc :working-dir (:working_dir body)))
    (throw (api-error 400 "bad_request" "Unsupported tool"))))

(defn- execution-context [tool-name input {:keys [permissions approval-id user request-id]}]
  (let [granted (if approval-id
                  (tool-approvals/granted-permissions tool-name input)
                  permissions)]
    {:permissions granted
     :approval-id approval-id
     :user (or user "api")
     :request-id request-id}))

(defn- handle-execute-tool [system exchange tool-name]
  (let [body (read-json-body exchange)
        input (:input body)
        permissions (normalize-permissions (:permissions body))
        approval-id (:approval_id body)
        tool-key (keyword tool-name)]
    (when-not (map? input)
      (throw (api-error 400 "bad_request" "input must be an object")))
    (try
      (write-json! exchange 200
                   {:data (tools/execute-tool (:tool-registry system)
                                              tool-key
                                              input
                                              (execution-context tool-key input
                                                                 {:permissions permissions
                                                                  :approval-id approval-id
                                                                  :user "api"}))})
      (catch Exception e
        (throw (tool-error->api-error e))))))

(defn- handle-ui-index [exchange]
  (write-html! exchange 200 (ui/index-page)))

(defn- handle-ui-shell [system exchange]
  (write-html! exchange 200
               (ui/shell-fragment system (keyword (:tab (query-params exchange))))))

(defn- handle-ui-dashboard [system exchange]
  (write-html! exchange 200 (ui/dashboard-fragment system)))

(defn- handle-ui-sessions [system exchange]
  (write-html! exchange 200 (ui/sessions-fragment system)))

(defn- handle-ui-create-session [system exchange]
  (let [body (read-form-body exchange)
        title (:title body)
        session (sqlite/create-session! (:store system) (not-empty title))]
    (emit-system-event! system
                        {:event-type :session.created
                         :entity-type :session
                         :entity-id (:id session)
                         :payload {:title (not-empty title)
                                   :source :ui}})
    (write-html! exchange 201
                 (str (ui/dashboard-fragment system)
                      (ui/sessions-fragment system)
                      (ui/session-detail-fragment system (:id session))))))

(defn- handle-ui-session-detail [system exchange]
  (write-html! exchange 200
               (ui/session-detail-fragment system (:session_id (query-params exchange)))))

(defn- handle-ui-session-messages [system exchange]
  (let [session-id (:session_id (query-params exchange))]
    (ensure-string! :session_id session-id)
    (ensure-session-exists! system session-id)
    (write-html! exchange 200
                 (ui/session-messages-fragment system session-id))))

(defn- relevant-session-event? [event session-id]
  (and (= "session" (:entity-type event))
       (= session-id (:entity-id event))
       (contains? #{"message.appended" "completion.completed" "session.created"} (:event-type event))))

(defn- handle-ui-session-live [system exchange]
  (let [session-id (:session_id (query-params exchange))
        ch (async/chan 64)]
    (ensure-string! :session_id session-id)
    (ensure-session-exists! system session-id)
    (async/tap (get-in system [:event-bus :mult]) ch)
    (try
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (write-datastar-patch! stream (ui/session-detail-fragment system session-id))
        (loop []
          (when-let [event (async/<!! ch)]
            (when (relevant-session-event? event session-id)
              (write-datastar-patch! stream (ui/session-detail-fragment system session-id)))
            (recur))))
      (finally
        (async/untap (get-in system [:event-bus :mult]) ch)
        (async/close! ch)))))

(defn- handle-ui-chat [system exchange]
  (let [body (read-form-body exchange)
        session-id (:session_id body)
        prompt (:prompt body)]
    (ensure-string! :session_id session-id)
    (ensure-string! :prompt prompt)
    (ensure-session-exists! system session-id)
    (complete! system [{:role "user" :content prompt}] {:session-id session-id})
    (write-html! exchange 200
                 (str (ui/dashboard-fragment system)
                      (ui/session-detail-fragment system session-id)
                      (ui/sessions-fragment system)))))

(defn- handle-ui-events [system exchange]
  (write-html! exchange 200 (ui/events-fragment system)))

(defn- handle-ui-events-live [system exchange]
  (let [ch (async/chan 64)]
    (async/tap (get-in system [:event-bus :mult]) ch)
    (try
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (write-datastar-patch! stream (ui/events-fragment system))
        (loop []
          (when (async/<!! ch)
            (write-datastar-patch! stream (ui/events-fragment system))
            (recur))))
      (finally
        (async/untap (get-in system [:event-bus :mult]) ch)
        (async/close! ch)))))

(defn- handle-ui-memory-prompt [system exchange]
  (write-html! exchange 200 (ui/memory-prompt-fragment system)))

(defn- handle-ui-memory-search [system exchange]
  (let [body (read-form-body exchange)
        query (:query body)]
    (ensure-string! :query query)
    (write-html! exchange 200
                 (ui/memory-search-results-fragment
                  (memory/search-memory (:memory-service system) query)))))

(defn- split-command-plain [command]
  (let [trimmed (str/trim (or command ""))]
    (when (str/blank? trimmed)
      (throw (api-error 400 "bad_request" "command must be a non-blank string")))
    (when (re-find #"[|&;<>$`(){}\[\]\\'\"]" trimmed)
      (throw (api-error 400 "bad_request" "command contains unsupported shell metacharacters")))
    (vec (remove str/blank? (str/split trimmed #"\s+")))))

(defn- split-command-optional [command]
  (let [trimmed (str/trim (or command ""))]
    (when-not (str/blank? trimmed)
      (split-command-plain trimmed))))

(defn- form-bool [value]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str value))))

(defn- handle-ui-runs [system exchange]
  (write-html! exchange 200 (ui/runs-fragment system)))

(defn- handle-ui-run-detail [system exchange]
  (write-html! exchange 200
               (ui/run-detail-fragment system (:run_id (query-params exchange)))))

(defn- handle-ui-create-run [system exchange]
  (let [body (read-form-body exchange)
        run (system-request-run! system
                                 {:agent-id (not-empty (:agent_id body))
                                  :name (not-empty (:name body))
                                  :substrate (keyword (or (:substrate body) "local-process"))
                                  :runner-options (cond-> {:working-dir (or (:working_dir body) ".")}
                                                    (split-command-optional (:command body))
                                                    (assoc :command (split-command-optional (:command body)))
                                                    (not-empty (:image body))
                                                    (assoc :image (:image body))
                                                    (form-bool (:share_network body))
                                                    (assoc :share-network? true))
                                  :requested-by "ui"})
        _ (system-launch-run! system (:id run))]
    (write-html! exchange 201
                 (str (ui/runs-fragment system)
                      (ui/run-detail-fragment system (:id run))))))

(defn- handle-ui-run-launch [system exchange run-id]
  (system-launch-run! system run-id)
  (write-html! exchange 200
               (str (ui/runs-fragment system)
                    (ui/run-detail-fragment system run-id))))

(defn- handle-ui-run-signal [system exchange run-id]
  (system-signal-run! system run-id {:command-type "cancel"})
  (write-html! exchange 200
               (str (ui/runs-fragment system)
                    (ui/run-detail-fragment system run-id))))

(defn- ui-tool-input [body]
  (tool-input-from-map (keyword (:tool body)) body))

(defn- handle-ui-tools [system exchange]
  (write-html! exchange 200 (ui/tools-fragment system)))

(defn- handle-list-tool-approvals [system exchange]
  (write-json! exchange 200
               {:data (mapv approval->response
                            (tool-approvals/list-requests (:store system)
                                                          {:status (:status (query-params exchange))
                                                           :limit 100}))}))

(defn- handle-create-tool-approval [system exchange]
  (let [body (read-json-body exchange)
        tool-name (keyword (:tool body))
        input (:input body)]
    (when-not (map? input)
      (throw (api-error 400 "bad_request" "input must be an object")))
    (write-json! exchange 201
                 {:data (approval->response
                         (tool-approvals/create-request!
                          (:store system)
                          {:tool-name tool-name
                           :input input
                           :requested-by (or (:requested_by body) "api")
                           :reason (:reason body)}))})))

(defn- handle-decide-tool-approval [system exchange approval-id status]
  (let [body (read-json-body exchange)
        actor (or (:actor body) "api")
        reason (:reason body)
        updated (case status
                  :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                  :denied (tool-approvals/deny! (:store system) approval-id actor reason))]
    (emit-system-event! system
                        {:event-type (keyword (str "tool.approval." (name status)))
                         :entity-type :tool_approval
                         :entity-id approval-id
                         :payload {:tool-name (:tool-name updated)
                                   :actor actor}})
    (write-json! exchange 200 {:data (approval->response updated)})))

(defn- handle-ui-tool-approvals [system exchange]
  (write-html! exchange 200
               (ui/tool-approvals-fragment
                (tool-approvals/list-requests (:store system) {:limit 50}))))

(defn- handle-ui-tool-approval-request [system exchange]
  (let [body (read-form-body exchange)
        tool-name (keyword (:tool body))
        input (ui-tool-input body)
        approval (tool-approvals/create-request!
                  (:store system)
                  {:tool-name tool-name
                   :input input
                   :requested-by "ui"
                   :reason (:reason body)})]
    (emit-system-event! system
                        {:event-type :tool.approval.requested
                         :entity-type :tool_approval
                         :entity-id (:id approval)
                         :payload {:tool-name (name tool-name)}})
    (write-html! exchange 201
                 (str (ui/tool-approvals-fragment
                       (tool-approvals/list-requests (:store system) {:limit 50}))
                      (ui/tool-results-fragment
                       tool-name
                       201
                       {:approval_id (:id approval)
                        :status (:status approval)})))))

(defn- handle-ui-tool-approval-decision [system exchange approval-id status]
  (let [body (read-form-body exchange)
        actor (or (:actor body) "operator")
        reason (:reason body)
        updated (case status
                  :approved (tool-approvals/approve! (:store system) approval-id actor reason)
                  :denied (tool-approvals/deny! (:store system) approval-id actor reason))]
    (emit-system-event! system
                        {:event-type (keyword (str "tool.approval." (name status)))
                         :entity-type :tool_approval
                         :entity-id approval-id
                         :payload {:tool-name (:tool-name updated)
                                   :actor actor}})
    (write-html! exchange 200
                 (str (ui/tool-approvals-fragment
                       (tool-approvals/list-requests (:store system) {:limit 50}))
                      (ui/tool-results-fragment
                       (keyword (:tool-name updated))
                       200
                       {:approval_id approval-id
                        :status (:status updated)})))))

(defn- handle-ui-tool-approval-run [system exchange approval-id]
  (let [{:keys [tool-name input permissions]} (tool-approvals/resolve-approved-request (:store system) approval-id)]
    (try
      (write-html! exchange 200
                   (str (ui/tool-approvals-fragment
                         (tool-approvals/list-requests (:store system) {:limit 50}))
                        (ui/tool-results-fragment
                         tool-name
                         200
                         {:result (tools/execute-tool (:tool-registry system)
                                                      tool-name
                                                      input
                                                      (execution-context tool-name input
                                                                         {:approval-id approval-id
                                                                          :permissions permissions
                                                                          :user "ui"}))})))
      (catch Exception e
        (let [api-e (tool-error->api-error e)]
          (write-html! exchange
                       (:status (ex-data api-e))
                       (ui/tool-results-fragment
                        tool-name
                        (:status (ex-data api-e))
                        {:error (:error (ex-data api-e))
                         :message (.getMessage api-e)
                         :details (:details (ex-data api-e))})))))))

(defn create-handler
  [system]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod exchange)
              path (split-path exchange)
              raw-path (.getPath (.getRequestURI exchange))]
          (cond
            (and (= method "GET") (str/starts-with? raw-path "/public/"))
            (handle-public-file exchange raw-path)

            (and (= method "GET") (empty? path))
            (handle-ui-index exchange)

            (and (= method "GET") (= path ["health"]))
            (handle-health system exchange)

            (and (= method "GET") (= path ["ui" "shell"]))
            (handle-ui-shell system exchange)

            (and (= method "GET") (= path ["ui" "dashboard"]))
            (handle-ui-dashboard system exchange)

            (and (= method "GET") (= path ["ui" "sessions"]))
            (handle-ui-sessions system exchange)

            (and (= method "POST") (= path ["ui" "sessions"]))
            (handle-ui-create-session system exchange)

            (and (= method "GET") (= path ["ui" "runs"]))
            (handle-ui-runs system exchange)

            (and (= method "POST") (= path ["ui" "runs"]))
            (handle-ui-create-run system exchange)

            (and (= method "GET") (= path ["ui" "run-detail"]))
            (handle-ui-run-detail system exchange)

            (and (= method "POST") (= 4 (count path))
                 (= ["ui" "runs"] (subvec path 0 2))
                 (= "launch" (nth path 3)))
            (handle-ui-run-launch system exchange (nth path 2))

            (and (= method "POST") (= 4 (count path))
                 (= ["ui" "runs"] (subvec path 0 2))
                 (= "signal" (nth path 3)))
            (handle-ui-run-signal system exchange (nth path 2))

            (and (= method "GET") (= path ["ui" "session-detail"]))
            (handle-ui-session-detail system exchange)

            (and (= method "GET") (= path ["ui" "session-messages"]))
            (handle-ui-session-messages system exchange)

            (and (= method "GET") (= path ["ui" "session" "live"]))
            (handle-ui-session-live system exchange)

            (and (= method "POST") (= path ["ui" "chat"]))
            (handle-ui-chat system exchange)

            (and (= method "GET") (= path ["ui" "events"]))
            (handle-ui-events system exchange)

            (and (= method "GET") (= path ["ui" "events" "live"]))
            (handle-ui-events-live system exchange)

            (and (= method "GET") (= path ["ui" "memory" "prompt"]))
            (handle-ui-memory-prompt system exchange)

            (and (= method "POST") (= path ["ui" "memory" "search"]))
            (handle-ui-memory-search system exchange)

            (and (= method "GET") (= path ["ui" "tools"]))
            (handle-ui-tools system exchange)

            (and (= method "GET") (= path ["ui" "tool-approvals"]))
            (handle-ui-tool-approvals system exchange)

            (and (= method "POST") (= path ["ui" "tool-approvals" "request"]))
            (handle-ui-tool-approval-request system exchange)

            (and (= method "POST") (= 4 (count path))
                 (= ["ui" "tool-approvals"] (subvec path 0 2))
                 (= "approve" (nth path 3)))
            (handle-ui-tool-approval-decision system exchange (nth path 2) :approved)

            (and (= method "POST") (= 4 (count path))
                 (= ["ui" "tool-approvals"] (subvec path 0 2))
                 (= "deny" (nth path 3)))
            (handle-ui-tool-approval-decision system exchange (nth path 2) :denied)

            (and (= method "POST") (= 4 (count path))
                 (= ["ui" "tool-approvals"] (subvec path 0 2))
                 (= "run" (nth path 3)))
            (handle-ui-tool-approval-run system exchange (nth path 2))

            (and (= method "GET") (= path ["v1" "sessions"]))
            (handle-list-sessions system exchange)

            (and (= method "POST") (= path ["v1" "sessions"]))
            (handle-create-session system exchange)

            (and (= method "GET") (= path ["v1" "runs"]))
            (handle-list-runs system exchange)

            (and (= method "POST") (= path ["v1" "runs"]))
            (handle-create-run system exchange)

            (and (= method "GET") (= 3 (count path))
                 (= ["v1" "runs"] (subvec path 0 2)))
            (handle-get-run system exchange (nth path 2))

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "runs"] (subvec path 0 2))
                 (= "heartbeats" (nth path 3)))
            (handle-run-heartbeats system exchange (nth path 2))

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "runs"] (subvec path 0 2))
                 (= "checkpoints" (nth path 3)))
            (handle-run-checkpoints system exchange (nth path 2))

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "runs"] (subvec path 0 2))
                 (= "commands" (nth path 3)))
            (handle-run-commands system exchange (nth path 2))

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "runs"] (subvec path 0 2))
                 (= "events" (nth path 3)))
            (handle-run-events system exchange (nth path 2))

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "runs"] (subvec path 0 2))
                 (= "launch" (nth path 3)))
            (handle-launch-run system exchange (nth path 2))

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "runs"] (subvec path 0 2))
                 (= "signal" (nth path 3)))
            (handle-signal-run system exchange (nth path 2))

            (and (= method "GET") (= path ["v1" "tools"]))
            (handle-list-tools system exchange)

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "tools"] (subvec path 0 2))
                 (= "execute" (nth path 3)))
            (handle-execute-tool system exchange (nth path 2))

            (and (= method "GET") (= path ["v1" "tool-approvals"]))
            (handle-list-tool-approvals system exchange)

            (and (= method "POST") (= path ["v1" "tool-approvals"]))
            (handle-create-tool-approval system exchange)

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "tool-approvals"] (subvec path 0 2))
                 (= "approve" (nth path 3)))
            (handle-decide-tool-approval system exchange (nth path 2) :approved)

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "tool-approvals"] (subvec path 0 2))
                 (= "deny" (nth path 3)))
            (handle-decide-tool-approval system exchange (nth path 2) :denied)

            (and (= method "GET") (= path ["v1" "skills"]))
            (handle-list-skills system exchange)

            (and (= method "GET") (= path ["v1" "channel-adapters"]))
            (handle-list-channel-adapters system exchange)

            (and (= method "GET") (= path ["v1" "events"]))
            (handle-list-events system exchange)

            (and (= method "GET") (= path ["v1" "events" "stream"]))
            (handle-events-stream system exchange)

            (and (= method "GET") (= path ["v1" "memory" "surfaces"]))
            (handle-memory-surfaces system exchange)

            (and (= method "GET") (= path ["v1" "memory" "prompt"]))
            (handle-memory-prompt system exchange)

            (and (= method "POST") (= path ["v1" "memory" "search"]))
            (handle-memory-search system exchange)

            (and (= method "POST") (= path ["v1" "memory" "graph" "facts"]))
            (handle-memory-graph-save system exchange)

            (and (= method "POST") (= path ["v1" "memory" "graph" "query"]))
            (handle-memory-graph-query system exchange)

            (and (= method "GET") (= path ["v1" "agents"]))
            (handle-list-agents system exchange)

            (and (= method "POST") (= path ["v1" "agents"]))
            (handle-create-agent system exchange)

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "agents"] (subvec path 0 2))
                 (= "messages" (nth path 3)))
            (handle-agent-messages system exchange (nth path 2))

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "agents"] (subvec path 0 2))
                 (= "messages" (nth path 3)))
            (handle-agent-message system exchange (nth path 2))

            (and (= method "POST") (= 5 (count path))
                 (= ["v1" "agents"] (subvec path 0 2))
                 (= "inbox" (nth path 3))
                 (= "consume" (nth path 4)))
            (handle-consume-agent-inbox system exchange (nth path 2))

            (and (= method "GET") (= path ["v1" "channels"]))
            (handle-list-channels system exchange)

            (and (= method "POST") (= path ["v1" "channels"]))
            (handle-create-channel system exchange)

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "channels"] (subvec path 0 2))
                 (= "messages" (nth path 3)))
            (handle-channel-messages system exchange (nth path 2))

            (and (= method "POST") (= 4 (count path))
                 (= ["v1" "channels"] (subvec path 0 2))
                 (= "messages" (nth path 3)))
            (handle-channel-message system exchange (nth path 2))

            (and (= method "GET") (= 4 (count path))
                 (= ["v1" "sessions"] (subvec path 0 2))
                 (= "messages" (nth path 3)))
            (handle-list-messages system exchange (nth path 2))

            (and (= method "POST") (= path ["v1" "chat" "completions"]))
            (handle-chat-completions system exchange)

            :else
            (not-found! exchange)))
        (catch Exception e
          (write-error! exchange e))))))

(defn start-server!
  [system {:keys [host port]}]
  (let [server (HttpServer/create (InetSocketAddress. host (int port)) 0)
        handler (create-handler system)
        executor (Executors/newCachedThreadPool (daemon-thread-factory))]
    (.createContext server "/" handler)
    (.setExecutor server executor)
    (.start server)
    (swap! server-executors assoc server executor)
    server))

(defn stop-server!
  [^HttpServer server]
  (when server
    (.stop server 0)
    (when-let [^ExecutorService executor (get @server-executors server)]
      (.shutdownNow executor)
      (swap! server-executors dissoc server))))
