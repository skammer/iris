(ns agent.api
  "Minimal HTTP API for rewritten runtime."
  (:require
   [agent.api.exchange :as exchange-adapter]
   [agent.api.middleware :as middleware]
   [agent.api.responses :as responses]
   [agent.api.routes :as routes]
   [agent.broker.core :as broker]
   [agent.channels.core :as channel-adapters]
   [agent.kernel :as kernel]
   [agent.kernel.ops :as kernel-ops]
   [agent.kernel.runtime :as kernel-runtime]
   [agent.federation.http :as federation-http]
   [agent.llm.core :as llm-core]
   [agent.logging :as logging]
   [agent.memory.core :as memory]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]
   [agent.runners.docker-podman :as docker-podman]
   [agent.runners.core :as runners]
   [agent.runners.options :as runner-options]
   [agent.skills :as skills]
   [agent.telemetry :as telemetry]
   [agent.tools.approvals :as tool-approvals]
   [agent.tools.core :as tools]
   [agent.ui :as ui]
   [agent.runtime.core :as runtime]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [org.httpkit.server :as http-kit]
   [reitit.ring :as ring])
  (:import
   (com.sun.net.httpserver HttpExchange)
   (java.net URLDecoder)
   (java.nio.charset StandardCharsets)
   (java.nio.file Files)))

(declare execution-context
         configured-tool-permissions
         parse-urlencoded
         parse-int-param
         relevant-run-event?
         relevant-session-event?
         system-get-run
         exchange-header
         run->response
         ensure-string!
         ensure-session-exists!
         event->response)

(defn- read-json-body [^HttpExchange exchange]
  (let [body (slurp (.getRequestBody exchange))]
    (if (str/blank? body)
      {}
      (json/parse-string body true))))

(defn- write-json! [^HttpExchange exchange status payload]
  (let [bytes (.getBytes (json/generate-string payload) StandardCharsets/UTF_8)]
    (.setAttribute exchange exchange-adapter/response-status-attr status)
    (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- write-html! [^HttpExchange exchange status html]
  (let [bytes (.getBytes html StandardCharsets/UTF_8)]
    (.setAttribute exchange exchange-adapter/response-status-attr status)
    (.add (.getResponseHeaders exchange) "Content-Type" "text/html; charset=utf-8")
    (.sendResponseHeaders exchange status (long (count bytes)))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- write-bytes! [^HttpExchange exchange status content-type bytes]
  (.setAttribute exchange exchange-adapter/response-status-attr status)
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

(defn- public-file-response [request]
  (let [path (:uri request)
        relative (subs path (count "/public/"))
        file (io/file "public" relative)
        canonical (.getCanonicalPath file)
        root (.getCanonicalPath (io/file "public"))]
    (if (and (.startsWith canonical root) (.isFile file))
      (responses/bytes-response 200
                                (content-type-for-path canonical)
                                (Files/readAllBytes (.toPath file)))
      (responses/not-found-response))))

(defn- request-query-params [request]
  (parse-urlencoded (:query-string request)))

(defn- invoke-exchange [request handler-fn]
  (exchange-adapter/invoke-exchange-handler request handler-fn))

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
   :kind (:kind agent)
   :role (:role agent)
   :parent_id (:parent-id agent)
   :logical_address (:logical-address agent)
   :capabilities (vec (:capabilities agent))
   :tool_access (vec (:tool-access agent))
   :memory_scopes (vec (:memory-scopes agent))
   :budgets (:budgets agent)
   :task (:task agent)
   :state (:state agent)
   :allow_direct (:allow-direct? agent)
   :status (:status agent)
   :created_at (:created-at agent)
   :message_count (:message-count agent)})

(defn- interop->response [interop]
  {:id (:id interop)
   :origin_message_id (:origin-message-id interop)
   :request_id (:request-id interop)
   :message_type (:message-type interop)
   :delivery_mode (:delivery-mode interop)
   :from_agent_id (:from-agent-id interop)
    :from_peer_id (:from-peer-id interop)
   :to_agent_id (:to-agent-id interop)
   :to_peer_id (:to-peer-id interop)
   :remote_agent_id (:remote-agent-id interop)
   :from_address (:from-address interop)
   :to_address (:to-address interop)
   :route (:route interop)
   :content (:content interop)
   :status (:status interop)
   :delivery_count (:delivery-count interop)
   :created_at (:created-at interop)
   :last_delivered_at (:last-delivered-at interop)
   :forwarded_at (:forwarded-at interop)
   :acked_at (:acked-at interop)
   :acknowledged_by (:acknowledged-by interop)
   :ack_type (:ack-type interop)
   :last_error (:last-error interop)})

(defn- federated-peer->response [peer]
  {:id (:id peer)
   :name (:name peer)
   :base_url (:base-url peer)
   :logical_address_prefix (:logical-address-prefix peer)
   :capabilities (:capabilities peer)
   :key_ids (:key-ids peer)
   :status (:status peer)
   :created_at (:created-at peer)})

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
     :idempotency_key (:idempotency-key run)
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
   :request_id (:request-id command)
   :response (:response command)
   :status (:status command)
   :created_at (:created-at command)
   :acknowledged_at (:acknowledged-at command)
   :completed_at (:completed-at command)
   :error (:error command)})

(defn- run-recovery [system run-id]
  (runtime/recovery-plan (:runtime-service system) run-id))

(defn- run-container-contract [run]
  (when (#{"docker" "podman"} (:substrate run))
    (docker-podman/image-contract (:runner-options run))))

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

(defn- normalize-trust-policies-body [policies]
  (reduce-kv (fn [acc peer-ref policy]
               (let [message-types (vec (or (:message_types policy)
                                            (:message-types policy)
                                            []))
                     routes (vec (or (:routes policy) []))
                     required-capabilities (vec (or (:required_capabilities policy)
                                                    (:required-capabilities policy)
                                                    []))]
                 (ensure-string-vec! :message_types message-types)
                 (ensure-string-vec! :routes routes)
                 (ensure-string-vec! :required_capabilities required-capabilities)
                 (assoc acc (if (keyword? peer-ref) (name peer-ref) (str peer-ref))
                        {:message-types message-types
                         :routes routes
                         :required-capabilities required-capabilities})))
             {}
             (or policies {})))

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
        content (telemetry/complete-with-telemetry! (:telemetry system)
                                                    provider
                                                    messages
                                                    {}
                                                    {:agent-id "api"
                                                     :model (get-in system [:config :llm :model])})]
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

(defn- sse-response
  ([request on-open on-close]
   (sse-response request on-open on-close ":\n\n"))
  ([request on-open on-close initial-body]
  (http-kit/as-channel
   request
   {:on-open (fn [channel]
               (http-kit/send! channel
                               {:status 200
                                :headers {"Content-Type" "text/event-stream"
                                          "Cache-Control" "no-cache"}
                                :body initial-body}
                               false)
               (when on-open
                 (on-open channel)))
    :on-close (fn [channel status]
                (when on-close
                  (on-close channel status)))})))

(defn- send-sse-text! [channel text]
  (http-kit/send! channel text false))

(defn- send-sse-chunk! [channel payload]
  (send-sse-text! channel (str "data: " (json/generate-string payload) "\n\n")))

(defn- send-sse-done! [channel]
  (send-sse-text! channel "data: [DONE]\n\n"))

(defn- send-datastar-patch! [channel html]
  (send-sse-text! channel
                  (str "event: datastar-patch-elements\n"
                       "data: elements "
                       (compact-html html)
                       "\n\n")))

(defn- chat-completions-stream-response
  [system request messages session-id]
  (let [stream-id (str "chatcmpl-" (System/currentTimeMillis))
        provider (name (get-in system [:config :llm :provider]))
        model (get-in system [:config :llm :model])
        chunks (llm-core/stream (:llm-provider system) messages {})]
    (persist-user-message! system messages session-id)
    (sse-response
     request
     (fn [channel]
       (future
         (loop [parts []]
           (if-let [chunk (async/<!! chunks)]
             (if (map? chunk)
               (do
                 (send-sse-chunk! channel {:error "stream_error"
                                           :message (or (:error chunk) "Provider stream failed")})
                 (send-sse-done! channel)
                 (http-kit/close channel))
               (do
                 (send-sse-chunk! channel {:id stream-id
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
               (send-sse-chunk! channel {:id stream-id
                                         :object "chat.completion.chunk"
                                         :session_id session-id
                                         :provider provider
                                         :model model
                                         :choices [{:index 0
                                                    :delta {}
                                                    :finish_reason "stop"}]})
               (send-sse-done! channel)
               (http-kit/close channel))))))
     nil)))

(defn- events-stream-response
  [system request]
  (let [stream-id (str "events-" (System/currentTimeMillis))
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (sse-response
     request
     (fn [channel]
       (future
         (try
           (loop []
             (when @open?
               (when-let [event (some-> (async/<!! ch) :payload)]
                 (send-sse-chunk! channel {:id stream-id
                                           :object "event.chunk"
                                           :event (event->response event)})
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn- run-events-stream-response
  [system run-id request]
  (let [params (request-query-params request)
        broker-instance (or (:event-bus system) (:broker system))
        after-id (parse-int-param (:after_id params) "after_id")
        replay-limit (or (parse-int-param (:replay_limit params) "replay_limit") 100)
        replay-messages (broker/replay! broker-instance
                                        (broker/run-events-subject run-id)
                                        {:after-id after-id
                                         :limit replay-limit})
        subscription (broker/subscribe! broker-instance (broker/all-runs-subject))
        ch (:channel subscription)
        open? (atom true)]
    (sse-response
     request
     (fn [channel]
       (future
         (try
           (when-let [run (system-get-run system run-id)]
             (send-sse-chunk! channel {:type "snapshot"
                                       :run (run->response run)}))
           (doseq [message replay-messages]
             (when (relevant-run-event? (:payload message) run-id)
               (send-sse-chunk! channel {:type "event"
                                         :data (event->response (:payload message))})))
           (loop []
             (when @open?
               (when-let [event (async/<!! ch)]
                 (when (relevant-run-event? (:payload event) run-id)
                   (send-sse-chunk! channel {:type "event"
                                             :data (event->response (:payload event))}))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn- ui-session-live-response
  [system request]
  (let [session-id (:session_id (request-query-params request))
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (ensure-string! :session_id session-id)
    (ensure-session-exists! system session-id)
    (sse-response
     request
     (fn [channel]
       (future
         (try
           (send-datastar-patch! channel (ui/session-detail-fragment system session-id))
           (loop []
             (when @open?
               (when-let [event (some-> (async/<!! ch) :payload)]
                 (when (relevant-session-event? event session-id)
                   (send-datastar-patch! channel (ui/session-detail-fragment system session-id)))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

(defn- ui-events-live-response
  [system request]
  (let [broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)
        open? (atom true)]
    (sse-response
     request
     (fn [channel]
       (future
         (try
           (send-datastar-patch! channel (ui/events-fragment system))
           (loop []
             (when @open?
               (when (async/<!! ch)
                 (send-datastar-patch! channel (ui/events-fragment system))
                 (recur))))
           (finally
             (broker/unsubscribe! broker-instance subscription)))))
     (fn [_ _]
       (reset! open? false)
       (broker/unsubscribe! broker-instance subscription)))))

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
                             :telemetry (telemetry/health-check (:telemetry system))
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

(defn- handle-telemetry [system exchange]
  (write-json! exchange 200 {:data (telemetry/snapshot (:telemetry system))}))

(defn- handle-events-stream [system exchange]
  (let [stream-id (str "events-" (System/currentTimeMillis))
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)]
    (try
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (loop []
          (when-let [event (some-> (async/<!! ch) :payload)]
            (write-sse-chunk! stream {:id stream-id
                                      :object "event.chunk"
                                      :event (event->response event)})
            (recur))))
      (catch Exception e
        (throw e))
      (finally
        (broker/unsubscribe! broker-instance subscription)))))

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
        kind (:kind body)
        role (or (:role body) "worker")
        parent-id (:parent_id body)
        system-prompt (:system_prompt body)
        capabilities (or (:capabilities body) [])
        tool-access (or (:tool_access body) [])
        memory-scopes (or (:memory_scopes body) [])
        budgets (or (:budgets body) {})
        task (:task body)
        allow-direct? (boolean (:allow_direct body))
        trusted-peers (or (:trusted_peers body) [])
        trust-policies (normalize-trust-policies-body (:trust_policies body))
        rate-limit-per-minute (:rate_limit_per_minute body)]
    (when kind
      (ensure-string! :kind kind))
    (when parent-id
      (ensure-string! :parent_id parent-id))
    (when system-prompt
      (ensure-string! :system_prompt system-prompt))
    (when task
      (when-not (map? task)
        (throw (api-error 400 "bad_request" "task must be an object"))))
    (ensure-string-vec! :capabilities capabilities)
    (ensure-string-vec! :tool_access tool-access)
    (ensure-string-vec! :memory_scopes memory-scopes)
    (ensure-string-vec! :trusted_peers trusted-peers)
      (write-json! exchange 201
                   (agent->response
                    (orchestrator/spawn-agent! (:orchestrator system)
                                               {:name name
                                                :kind kind
                                                :role role
                                                :parent-id parent-id
                                                :system-prompt system-prompt
                                                :capabilities capabilities
                                                :tool-access tool-access
                                                :memory-scopes memory-scopes
                                                :budgets budgets
                                                :task task
                                                :allow-direct? allow-direct?
                                                :trusted-peers trusted-peers
                                                :trust-policies trust-policies
                                                :interop-rate-limit-per-minute rate-limit-per-minute})))))

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

(defn- handle-agent-tool-execute [system exchange agent-id tool-name]
  (let [body (read-json-body exchange)
        input (:input body)
        approval-id (:approval_id body)
        tool-key (keyword tool-name)]
    (try
      (write-json! exchange 200
                   {:data (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)]
                            (when-not agent
                              (throw (ex-info "Agent not found" {:type :agent-not-found
                                                                 :agent-id agent-id})))
                            (tools/execute-tool (:tool-registry system)
                                                tool-key
                                                input
                                                (merge (execution-context system :agent tool-key input
                                                                          {:approval-id approval-id
                                                                           :user (str "agent:" agent-id)
                                                                           :request-id (:request_id body)
                                                                           :activity (:activity body)})
                                                       {:allowed-tools (set (:tool-access agent))})))})
      (catch Exception e
        (let [data (ex-data e)]
          (case (:type data)
            :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
            :tool-blocked (throw (api-error 403 "tool_blocked" (.getMessage e) (dissoc data :type)))
            (throw (tool-error->api-error e))))))))

(defn- handle-orchestrator-spawn-worker [system exchange agent-id]
  (let [body (read-json-body exchange)
        name (:name body)
        role (:role body)
        task (:task body)
        capabilities (or (:capabilities body) [])
        tool-access (or (:tool_access body) [])
        memory-scopes (or (:memory_scopes body) [])
        budgets (or (:budgets body) {})
        system-prompt (:system_prompt body)]
    (when name
      (ensure-string! :name name))
    (when role
      (ensure-string! :role role))
    (when-not (map? task)
      (throw (api-error 400 "bad_request" "task must be an object")))
    (ensure-string-vec! :capabilities capabilities)
    (ensure-string-vec! :tool_access tool-access)
    (ensure-string-vec! :memory_scopes memory-scopes)
    (try
      (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)]
        (when-not agent
          (throw (ex-info "Agent not found" {:type :agent-not-found
                                             :agent-id agent-id})))
        (when-not (= "orchestrator" (:kind agent))
          (throw (ex-info "Agent is not an orchestrator" {:type :validation-failed
                                                          :agent-id agent-id})))
        (let [step (kernel/orchestrator-spawn-worker-step
                    {:task task
                     :worker-name (or name "Task Worker")
                     :worker-role (or role "worker")
                     :capability-bundle {:capabilities capabilities
                                         :tool-access tool-access}
                     :memory-scopes memory-scopes
                     :budgets budgets
                     :system-prompt system-prompt})
              spawn (-> step :directives first :payload)
              worker (orchestrator/spawn-agent! (:orchestrator system)
                                                {:name (:name spawn)
                                                 :kind "worker"
                                                 :role (:role spawn)
                                                 :parent-id agent-id
                                                 :system-prompt (:system-prompt spawn)
                                                 :capabilities capabilities
                                                 :tool-access tool-access
                                                 :memory-scopes memory-scopes
                                                 :budgets budgets
                                                 :task task})
              receipt {:directive :spawn-worker
                       :status :ok
                       :worker-id (:id worker)}]
          ((:event-sink system)
           {:event-type :agent.kernel.step.executed
            :entity-type :agent
            :entity-id agent-id
            :payload {:directive-count 2
                      :receipt-count 1
                      :receipts [receipt]}})
          (write-json! exchange 201 {:data {:worker (agent->response worker)
                                            :receipts [receipt]}})))
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
          :validation-failed (throw (api-error 409 "invalid_orchestrator" (.getMessage e)))
          (throw e))))))

(defn- normalize-step-body [body]
  (let [directives (or (:directives body) [])]
    (when-not (vector? directives)
      (throw (api-error 400 "bad_request" "directives must be a vector")))
    {:schema-version (or (:schema-version body) (:schema_version body))
     :state (or (:state body) {})
     :directives (mapv (fn [directive]
                         (when-not (map? directive)
                           (throw (api-error 400 "bad_request" "directive must be an object")))
                         (kernel/directive (keyword (:type directive))
                                           (or (:payload directive) {})))
                       directives)
     :receipts (vec (or (:receipts body) []))}))

(defrecord ApiKernelOps [system]
  kernel-ops/KernelOps
  (spawn-task-worker! [_ {:keys [task name role capability-bundle memory-scopes budgets system-prompt parent-id]}]
    (orchestrator/spawn-agent! (:orchestrator system)
                               {:name name
                                :kind "worker"
                                :role role
                                :parent-id parent-id
                                :system-prompt system-prompt
                                :capabilities (vec (or (:capabilities capability-bundle) []))
                                :tool-access (vec (or (:tool-access capability-bundle) []))
                                :memory-scopes (vec memory-scopes)
                                :budgets budgets
                                :task task}))
  (execute-agent-tool! [_ target-agent-id tool-name input context]
    (let [target-agent (orchestrator/get-agent (:orchestrator system) target-agent-id)]
      (when-not target-agent
        (throw (ex-info "Agent not found" {:type :agent-not-found
                                           :agent-id target-agent-id})))
      (tools/execute-tool (:tool-registry system)
                          tool-name
                          input
                          (merge context
                                 {:allowed-tools (set (:tool-access target-agent))
                                  :permissions (configured-tool-permissions system :agent)
                                  :user (or (:user context) (str "agent:" target-agent-id))}))))
  (send-agent-message! [_ agent-id message]
    (orchestrator/send-agent-message! (:orchestrator system)
                                      (:llm-provider system)
                                      agent-id
                                      message))
  (patch-agent-state! [_ agent-id patch]
    (orchestrator/patch-agent-state! (:orchestrator system) agent-id patch))
  (set-agent-status! [_ agent-id status]
    (orchestrator/set-agent-status! (:orchestrator system) agent-id status))
  (emit-kernel-event! [_ event]
    ((:event-sink system) event)))

(defn- handle-agent-step-execute [system exchange agent-id]
  (let [agent (orchestrator/get-agent (:orchestrator system) agent-id)
        body (read-json-body exchange)
        step (normalize-step-body body)
        yolo-override (if (contains? body :yolo?)
                        (:yolo? body)
                        (:yolo body))
        opts {:yolo? (if (or (contains? body :yolo?) (contains? body :yolo))
                       (true? yolo-override)
                       (true? (get-in system [:config :tools :yolo?])))}]
    (when-not agent
      (throw (api-error 404 "agent_not_found" "Agent not found")))
    (let [ops (->ApiKernelOps system)]
      (write-json! exchange 200
                   {:data (kernel-runtime/execute-step! ops agent-id step opts)}))))

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

(defn- handle-agent-interop [system exchange agent-id]
  (try
    (write-json! exchange 200 {:data (orchestrator/describe-agent-interop (:orchestrator system) agent-id)})
    (catch Exception e
      (if (= :agent-not-found (:type (ex-data e)))
        (throw (api-error 404 "agent_not_found" "Agent not found"))
        (throw e)))))

(defn- handle-agent-interop-capabilities [system exchange agent-id]
  (let [body (read-json-body exchange)
        capabilities (or (:capabilities body) [])
        tool-access (or (:tool_access body) [])
        memory-scopes (or (:memory_scopes body) [])
        budgets (or (:budgets body) {})
        allow-direct? (boolean (:allow_direct body))
        trusted-peers (or (:trusted_peers body) [])
        trust-policies (normalize-trust-policies-body (:trust_policies body))
        rate-limit-per-minute (:rate_limit_per_minute body)]
    (ensure-string-vec! :capabilities capabilities)
    (ensure-string-vec! :tool_access tool-access)
    (ensure-string-vec! :memory_scopes memory-scopes)
    (ensure-string-vec! :trusted_peers trusted-peers)
    (try
      (write-json! exchange 200
                   {:data (orchestrator/register-agent-capabilities! (:orchestrator system)
                                                                     agent-id
                                                                     {:capabilities capabilities
                                                                      :tool-access tool-access
                                                                      :memory-scopes memory-scopes
                                                                      :budgets budgets
                                                                      :allow-direct? allow-direct?
                                                                      :trusted-peers trusted-peers
                                                                      :trust-policies trust-policies
                                                                      :interop-rate-limit-per-minute rate-limit-per-minute})})
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (api-error 404 "agent_not_found" "Agent not found"))
          (throw e))))))

(defn- handle-list-federated-peers [system exchange]
  (write-json! exchange 200
               {:data (mapv federated-peer->response
                            (orchestrator/list-federated-peers (:orchestrator system)))}))

(defn- handle-create-federated-peer [system exchange]
  (let [body (read-json-body exchange)
        id (:id body)
        name (:name body)
        base-url (:base_url body)
        logical-address-prefix (:logical_address_prefix body)
        capabilities (or (:capabilities body) [])
        status (or (:status body) "online")
        key-id (:key_id body)
        public-key (:public_key body)
        private-key (:private_key body)]
    (when id
      (ensure-string! :id id))
    (when name
      (ensure-string! :name name))
    (when base-url
      (ensure-string! :base_url base-url))
    (when logical-address-prefix
      (ensure-string! :logical_address_prefix logical-address-prefix))
    (when key-id
      (ensure-string! :key_id key-id))
    (when public-key
      (ensure-string! :public_key public-key))
    (when private-key
      (ensure-string! :private_key private-key))
    (ensure-string-vec! :capabilities capabilities)
    (let [peer (orchestrator/register-federated-peer! (:orchestrator system)
                                                      {:id id
                                                       :name name
                                                       :base-url base-url
                                                       :logical-address-prefix logical-address-prefix
                                                       :capabilities capabilities
                                                       :status status
                                                       :key-id key-id
                                                       :public-key public-key
                                                       :private-key private-key})]
      (when (and (:store system) public-key)
        (sqlite/upsert-federation-peer-key!
         (:store system)
         {:peer-id (:id peer)
          :key-id (or key-id "default")
          :public-key public-key
          :status "active"}))
      (write-json! exchange 201
                   {:data (federated-peer->response peer)}))))

(defn- handle-federation-inbox [system exchange]
  (let [body (read-json-body exchange)
        peer-id (:peer_id body)
        to-agent-ref (:to_agent_ref body)
        envelope (:envelope body)]
    (ensure-string! :peer_id peer-id)
    (ensure-string! :to_agent_ref to-agent-ref)
    (when-not (map? envelope)
      (throw (api-error 400 "bad_request" "envelope must be an object")))
    (try
      (federation-http/verify-request!
       {:store (:store system)
        :peer (orchestrator/get-federated-peer (:orchestrator system) peer-id)}
       body)
      (write-json! exchange 202
                   {:data (interop->response
                           (orchestrator/receive-federated-message! (:orchestrator system)
                                                                    peer-id
                                                                    to-agent-ref
                                                                    envelope))})
      (catch Exception e
        (case (:type (ex-data e))
          :peer-not-found (throw (api-error 404 "peer_not_found" "Federated peer not found"))
          :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
          :signature-missing (throw (api-error 401 "signature_missing" "Federation signature missing"))
          :signature-invalid (throw (api-error 401 "signature_invalid" "Federation signature invalid"))
          :timestamp-skew (throw (api-error 401 "timestamp_skew" "Federation timestamp outside skew"))
          :nonce-replay (throw (api-error 409 "nonce_replay" "Federation nonce replay"))
          (throw e))))))

(defn- handle-agent-interop-message [system exchange agent-id]
  (let [body (read-json-body exchange)
        from-agent-id (:from_agent_id body)
        to-agent-ref (or (:to_agent_ref body) agent-id)
        content (:content body)
        message-type (or (:message_type body) "request")
        route (:route body)
        delivery-mode (or (:delivery_mode body) "at-most-once")
        request-id (:request_id body)]
    (ensure-string! :from_agent_id from-agent-id)
    (ensure-string! :to_agent_ref to-agent-ref)
    (ensure-string! :content content)
    (try
      (write-json! exchange 201
                   {:data (interop->response
                           (orchestrator/send-interop-message! (:orchestrator system)
                                                               from-agent-id
                                                               to-agent-ref
                                                               {:message-type message-type
                                                                :route route
                                                                :delivery-mode delivery-mode
                                                                :request-id request-id
                                                                :content content}))})
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
          :permission-denied (throw (api-error 403 "permission_denied" "Direct interop denied"))
          :rate-limited (throw (api-error 429 "rate_limited" "Interop rate limit exceeded"))
          :validation-failed (throw (api-error 400 "validation_failed" (.getMessage e)))
          (throw e))))))

(defn- handle-agent-interop-messages [system exchange agent-id]
  (let [params (query-params exchange)
        direction (some-> (:direction params) str/lower-case keyword)
        status (:status params)]
    (try
      (write-json! exchange 200
                   {:data (mapv interop->response
                                (orchestrator/list-interop-messages (:orchestrator system)
                                                                    agent-id
                                                                    (cond-> {}
                                                                      direction (assoc :direction direction)
                                                                      status (assoc :status status))))})
      (catch Exception e
        (if (= :agent-not-found (:type (ex-data e)))
          (throw (api-error 404 "agent_not_found" "Agent not found"))
          (throw e))))))

(defn- handle-agent-interop-ack [system exchange agent-id message-id]
  (let [body (read-json-body exchange)
        ack-type (or (:ack_type body) "ack")]
    (try
      (write-json! exchange 200
                   {:data (interop->response
                           (orchestrator/acknowledge-interop-message! (:orchestrator system)
                                                                      agent-id
                                                                      message-id
                                                                      {:ack-type ack-type}))})
      (catch Exception e
        (case (:type (ex-data e))
          :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
          :interop-message-not-found (throw (api-error 404 "interop_message_not_found" "Interop message not found"))
          :permission-denied (throw (api-error 403 "permission_denied" "Interop ack denied"))
          :validation-failed (throw (api-error 400 "validation_failed" (.getMessage e)))
          (throw e))))))

(defn- handle-agent-interop-retry [system exchange agent-id message-id]
  (try
    (write-json! exchange 200
                 {:data (interop->response
                         (orchestrator/retry-interop-message! (:orchestrator system)
                                                              agent-id
                                                              message-id))})
    (catch Exception e
      (case (:type (ex-data e))
        :agent-not-found (throw (api-error 404 "agent_not_found" "Agent not found"))
        :interop-message-not-found (throw (api-error 404 "interop_message_not_found" "Interop message not found"))
        :permission-denied (throw (api-error 403 "permission_denied" "Interop retry denied"))
        :validation-failed (throw (api-error 400 "validation_failed" (.getMessage e)))
        :rate-limited (throw (api-error 429 "rate_limited" "Interop rate limit exceeded"))
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
                   (throw (api-error 404 "runner_not_found" "Runner not found")))
        checkpoint-seq (or (get-in run [:checkpoint :sequence-no]) 0)]
    (try
      (let [run-spec (runners/create-run-spec
                      {:run-id (:id run)
                       :agent-id (:agent-id run)
                       :parent-run-id (:parent-run-id run)
                       :lease-id (:lease-id run)
                       :name (:name run)
                       :substrate (keyword (:substrate run))
                       :capabilities (:capabilities run)
                       :network-identity (:network-identity run)
                       :bootstrap-token (:bootstrap-token run)
                       :bootstrap-spec (assoc (:bootstrap-spec run)
                                         :checkpoint-seq checkpoint-seq)
                       :requested-by (:requested-by run)
                       :runner-options (runner-options/prepare-runner-options system run)})
            launch-result (:result (runtime/execute-activity!
                                    (:runtime-service system)
                                    {:run-id run-id
                                     :activity-name :runner.launch
                                     :input run-spec}
                                    #(runners/launch runner run-spec)))]
        (runtime/transition-run! (:runtime-service system) run-id :launched {:runner-metadata launch-result})
        (system-get-run system run-id))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          :validation-failed (throw (api-error 400 "bad_request" (.getMessage e) (ex-data e)))
          (throw e))))))

(defn- system-signal-run! [system run-id command]
  (let [run (or (system-get-run system run-id)
                (throw (api-error 404 "run_not_found" "Run not found")))
        runner (or (get (:runner-registry system) (keyword (:substrate run)))
                   (throw (api-error 404 "runner_not_found" "Runner not found")))
        command-type (keyword (:command-type command))]
    (let [signal-result (:result (runtime/execute-activity!
                                  (:runtime-service system)
                                  {:run-id run-id
                                   :activity-name (keyword (str "runner.signal." (name command-type)))
                                   :input command}
                                  #(runners/signal runner run-id command)))]
    (when (contains? #{:cancel :terminate :kill} command-type)
      (runtime/transition-run! (:runtime-service system)
                               run-id
                               :cancelled
                               {:runner-metadata (merge (:runner-metadata run) signal-result)}))
    signal-result)))

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
     :idempotency-key (:idempotency_key body)
     :name (:name body)
     :substrate (or (some-> (:substrate body) keyword) :local-unsandboxed)
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
                               :runner_status (system-runner-status system run-id)
                               :recovery (run-recovery system run-id)
                               :container_contract (run-container-contract run))})
    (throw (api-error 404 "run_not_found" "Run not found"))))

(defn- handle-create-run [system exchange]
  (let [body (read-json-body exchange)
        request (cond-> (normalize-run-request body)
                  (and (nil? (:idempotency_key body))
                       (exchange-header exchange "Idempotency-Key"))
                  (assoc :idempotency-key (exchange-header exchange "Idempotency-Key")))
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
        status (:status params)
        request-id (:request_id params)]
    (write-json! exchange 200
                 {:data (mapv run-command->response
                              (runtime/list-commands (:runtime-service system) run-id
                                                     (cond-> {}
                                                       limit (assoc :limit limit)
                                                       request-id (assoc :request-id request-id)
                                                       status (assoc :status status))))})))

(defn- exchange-header [^HttpExchange exchange name]
  (.getFirst (.getRequestHeaders exchange) name))

(defn- bearer-token [value]
  (when value
    (second (re-matches #"(?i)^Bearer\s+(.+)$" value))))

(defn- control-token [exchange]
  (or (bearer-token (exchange-header exchange "Authorization"))
      (exchange-header exchange "X-Agent-Bootstrap-Token")))

(defn- ensure-run-control! [system exchange run-id]
  (let [run (or (system-get-run system run-id)
                (throw (api-error 404 "run_not_found" "Run not found")))
        token (control-token exchange)]
    (when-not (and token (= token (:bootstrap-token run)))
      (throw (api-error 401 "unauthorized" "Invalid run control token")))
    run))

(defn- body-value [body & ks]
  (some #(get body %) ks))

(defn- handle-run-control-register [system exchange run-id]
  (ensure-run-control! system exchange run-id)
  (let [body (read-json-body exchange)
        run (runtime/register-run! (:runtime-service system)
                                   run-id
                                   {:capabilities (or (:capabilities body) [])
                                    :network-identity (body-value body :network-identity :network_identity)
                                    :runner-metadata (body-value body :runner-metadata :runner_metadata)})]
    (write-json! exchange 200 {:data (run->response run)})))

(defn- handle-run-control-heartbeat [system exchange run-id]
  (ensure-run-control! system exchange run-id)
  (let [body (read-json-body exchange)
        heartbeat (runtime/heartbeat! (:runtime-service system)
                                      run-id
                                      {:sequence-no (body-value body :sequence-no :sequence_no)
                                       :status (keyword (or (:status body) "running"))
                                       :metrics (:metrics body)
                                       :lease-id (body-value body :lease-id :lease_id)})]
    (write-json! exchange 200 {:data (heartbeat->response heartbeat)})))

(defn- handle-run-control-checkpoint [system exchange run-id]
  (ensure-run-control! system exchange run-id)
  (let [body (read-json-body exchange)
        checkpoint (runtime/checkpoint! (:runtime-service system)
                                        run-id
                                        {:sequence-no (body-value body :sequence-no :sequence_no)
                                         :checkpoint-type (keyword (or (body-value body :checkpoint-type :checkpoint_type)
                                                                       "state"))
                                         :state (:state body)})]
    (write-json! exchange 200 {:data (checkpoint->response checkpoint)})))

(defn- handle-run-control-commands [system exchange run-id]
  (ensure-run-control! system exchange run-id)
  (write-json! exchange 200
               {:data (mapv run-command->response
                            (runtime/pending-commands (:runtime-service system) run-id))}))

(defn- handle-run-control-command-ack [system exchange run-id command-id]
  (ensure-run-control! system exchange run-id)
  (runtime/acknowledge-command! (:runtime-service system) run-id command-id)
  (write-json! exchange 200 {:data {:id command-id
                                    :status "acknowledged"}}))

(defn- handle-run-control-command-complete [system exchange run-id command-id]
  (ensure-run-control! system exchange run-id)
  (let [body (read-json-body exchange)
        status (keyword (or (:status body) "completed"))
        command (runtime/complete-command! (:runtime-service system)
                                           run-id
                                           command-id
                                           status
                                           (:error body)
                                           (:response body))]
    (write-json! exchange 200 {:data (run-command->response command)})))

(defn- handle-run-control-transition [system exchange run-id]
  (ensure-run-control! system exchange run-id)
  (let [body (read-json-body exchange)
        status (keyword (or (:status body) "running"))
        run (runtime/transition-run! (:runtime-service system)
                                     run-id
                                     status
                                     {:last-error (body-value body :last-error :last_error)
                                      :runner-metadata (body-value body :runner-metadata :runner_metadata)})]
    (write-json! exchange 200 {:data (run->response run)})))

(defn- handle-run-events [system exchange run-id]
  (let [params (query-params exchange)
        limit (parse-int-param (:limit params) "limit")
        after-id (parse-int-param (:after_id params) "after_id")]
    (write-json! exchange 200
                 {:data (mapv event->response
                              (sqlite/list-events (:store system)
                                                  (cond-> {:entity-type :agent_run
                                                           :entity-id run-id}
                                                    after-id (assoc :after-id after-id)
                                                    limit (assoc :limit limit))))})))

(defn- relevant-run-event? [event run-id]
  (and (= "agent_run" (:entity-type event))
       (= run-id (:entity-id event))))

(defn- handle-run-events-stream [system exchange run-id]
  (let [params (query-params exchange)
        broker-instance (or (:event-bus system) (:broker system))
        after-id (parse-int-param (:after_id params) "after_id")
        replay-limit (or (parse-int-param (:replay_limit params) "replay_limit") 100)
        replay-messages (broker/replay! broker-instance
                                        (broker/run-events-subject run-id)
                                        {:after-id after-id
                                         :limit replay-limit})
        subscription (broker/subscribe! broker-instance (broker/all-runs-subject))
        ch (:channel subscription)]
    (try
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (when-let [run (system-get-run system run-id)]
          (write-sse-chunk! stream {:type "snapshot"
                                    :run (run->response run)}))
        (doseq [message replay-messages]
          (when (relevant-run-event? (:payload message) run-id)
            (write-sse-chunk! stream {:type "event"
                                      :data (event->response (:payload message))})))
        (loop []
          (when-let [event (async/<!! ch)]
            (when (relevant-run-event? (:payload event) run-id)
              (write-sse-chunk! stream {:type "event"
                                        :data (event->response (:payload event))}))
            (recur))))
      (finally
        (broker/unsubscribe! broker-instance subscription)))))

(defn- handle-run-wait [system exchange run-id]
  (let [params (query-params exchange)
        timeout-ms (or (parse-int-param (:timeout_ms params) "timeout_ms") 15000)
        interval-ms (or (parse-int-param (:interval_ms params) "interval_ms") 250)]
    (if-let [run (runtime/wait-for-run! (:runtime-service system) run-id {:timeout-ms timeout-ms
                                                                          :interval-ms interval-ms})]
      (write-json! exchange 200
                   {:data (assoc (run->response run)
                                 :recovery (run-recovery system run-id)
                                 :container_contract (run-container-contract run))})
      (throw (api-error 404 "run_not_found" "Run not found")))))

(defn- handle-run-recover [system exchange run-id]
  (if-let [_ (system-get-run system run-id)]
    (write-json! exchange 202
                 {:data {:recovery (run-recovery system run-id)
                         :replacement_run (run->response (runtime/retry-run! (:runtime-service system) run-id))}})
    (throw (api-error 404 "run_not_found" "Run not found"))))

(defn- handle-reclaim-stale-runs [system exchange]
  (write-json! exchange 200
               {:data (mapv (fn [{:keys [reclaimed replacement]}]
                              {:reclaimed (run->response reclaimed)
                               :replacement (some-> replacement run->response)})
                            (runtime/reclaim-stale-runs! (:runtime-service system)))}))

(defn- handle-chat-completions [system exchange]
  (let [{:keys [messages session-id stream?]}
        (attach-session-context system
                                (normalize-chat-request (read-json-body exchange)))]
    (ensure-session-exists! system session-id)
    (if stream?
      (handle-chat-completions-stream system exchange messages session-id)
      (let [result (complete! system messages {:session-id session-id})]
        (write-json! exchange 200 (openai-style-completion system session-id (:content result)))))))

(defn- chat-completions-response
  [system request]
  (let [{:keys [messages session-id stream?]}
        (attach-session-context system
                                (normalize-chat-request
                                 (if-let [body (:body request)]
                                   (let [raw (slurp body)]
                                     (if (str/blank? raw)
                                       {}
                                       (json/parse-string raw true)))
                                   {})))]
    (ensure-session-exists! system session-id)
    (if stream?
      (chat-completions-stream-response system request messages session-id)
      (let [result (complete! system messages {:session-id session-id})]
        (responses/json-response 200
                                 (openai-style-completion system session-id (:content result)))))))

(declare split-command-plain)

(defn- tool-input-from-map [tool-name body]
  (case tool-name
    :fs (cond-> {:action (:action body)
                 :path (:path body)}
          (contains? body :content) (assoc :content (:content body)))
    :shell (cond-> {:argv (or (:argv body)
                              (split-command-plain (:command body)))}
             (not (str/blank? (:working_dir body))) (assoc :working-dir (:working_dir body)))
    (throw (api-error 400 "bad_request" "Unsupported tool"))))

(defn- configured-tool-permissions [system profile]
  (set (get-in system [:config :tools :permissions profile] #{})))

(defn- execution-context [system profile tool-name input {:keys [approval-id user request-id activity]}]
  (let [granted (if approval-id
                  (tool-approvals/granted-permissions tool-name input)
                  (configured-tool-permissions system profile))]
    (cond-> {:permissions granted
             :approval-id approval-id
             :user (or user "api")
             :request-id request-id}
      activity (assoc :activity activity))))

(defn- handle-execute-tool [system exchange tool-name]
  (let [body (read-json-body exchange)
        input (:input body)
        approval-id (:approval_id body)
        tool-key (keyword tool-name)]
    (when-not (map? input)
      (throw (api-error 400 "bad_request" "input must be an object")))
    (try
      (write-json! exchange 200
                   {:data (tools/execute-tool (:tool-registry system)
                                              tool-key
                                              input
                                              (execution-context system :api tool-key input
                                                                 {:approval-id approval-id
                                                                  :user "api"
                                                                  :activity (:activity body)}))})
      (catch Exception e
        (throw (tool-error->api-error e))))))

(defn- handle-ui-index [exchange]
  (write-html! exchange 200 (ui/index-page)))

(defn- handle-ui-shell [system exchange]
  (write-html! exchange 200
               (ui/shell-fragment system (keyword (:tab (query-params exchange))))))

(defn- handle-ui-dashboard [system exchange]
  (write-html! exchange 200 (ui/dashboard-fragment system)))

(defn- handle-ui-operator-board [system exchange]
  (write-html! exchange 200 (ui/operator-board-fragment system)))

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
        broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)]
    (ensure-string! :session_id session-id)
    (ensure-session-exists! system session-id)
    (try
      (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
      (.add (.getResponseHeaders exchange) "Cache-Control" "no-cache")
      (.sendResponseHeaders exchange 200 0)
      (with-open [stream (.getResponseBody exchange)]
        (write-datastar-patch! stream (ui/session-detail-fragment system session-id))
        (loop []
          (when-let [event (some-> (async/<!! ch) :payload)]
            (when (relevant-session-event? event session-id)
              (write-datastar-patch! stream (ui/session-detail-fragment system session-id)))
            (recur))))
      (finally
        (broker/unsubscribe! broker-instance subscription)))))

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
  (let [broker-instance (or (:event-bus system) (:broker system))
        subscription (broker/subscribe! broker-instance (broker/all-events-subject))
        ch (:channel subscription)]
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
        (broker/unsubscribe! broker-instance subscription)))))

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

(defn- handle-ui-run-detail-body [system exchange]
  (write-html! exchange 200
               (ui/run-detail-body system (:run_id (query-params exchange)))))

(defn- handle-ui-create-run [system exchange]
  (let [body (read-form-body exchange)
        run (system-request-run! system
                                 {:agent-id (not-empty (:agent_id body))
                                  :name (not-empty (:name body))
                                  :substrate (keyword (or (:substrate body) "local-unsandboxed"))
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
  (let [{:keys [tool-name input]} (tool-approvals/resolve-approved-request (:store system) approval-id)]
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
                                                      (execution-context system :ui tool-name input
                                                                         {:approval-id approval-id
                                                                          :user "ui"
                                                                          :activity (:activity input)}))})))
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

(defn- exchange-handler
  [request f]
  (invoke-exchange request f))

(defn- exchange-handler-map
  [system]
  {:health (fn [request] (exchange-handler request #(handle-health system %)))
   :ui-shell (fn [request] (exchange-handler request #(handle-ui-shell system %)))
   :ui-dashboard (fn [request] (exchange-handler request #(handle-ui-dashboard system %)))
   :ui-operator-board (fn [request] (exchange-handler request #(handle-ui-operator-board system %)))
   :ui-sessions (fn [request] (exchange-handler request #(handle-ui-sessions system %)))
   :ui-create-session (fn [request] (exchange-handler request #(handle-ui-create-session system %)))
   :ui-runs (fn [request] (exchange-handler request #(handle-ui-runs system %)))
   :ui-create-run (fn [request] (exchange-handler request #(handle-ui-create-run system %)))
   :ui-run-detail (fn [request] (exchange-handler request #(handle-ui-run-detail system %)))
   :ui-run-detail-body (fn [request] (exchange-handler request #(handle-ui-run-detail-body system %)))
   :ui-run-launch (fn [request] (exchange-handler request #(handle-ui-run-launch system % (get-in request [:path-params :run-id]))))
   :ui-run-signal (fn [request] (exchange-handler request #(handle-ui-run-signal system % (get-in request [:path-params :run-id]))))
   :ui-session-detail (fn [request] (exchange-handler request #(handle-ui-session-detail system %)))
   :ui-session-messages (fn [request] (exchange-handler request #(handle-ui-session-messages system %)))
   :ui-chat (fn [request] (exchange-handler request #(handle-ui-chat system %)))
   :ui-events (fn [request] (exchange-handler request #(handle-ui-events system %)))
   :ui-memory-prompt (fn [request] (exchange-handler request #(handle-ui-memory-prompt system %)))
   :ui-memory-search (fn [request] (exchange-handler request #(handle-ui-memory-search system %)))
   :ui-tools (fn [request] (exchange-handler request #(handle-ui-tools system %)))
   :ui-tool-approvals (fn [request] (exchange-handler request #(handle-ui-tool-approvals system %)))
   :ui-tool-approval-request (fn [request] (exchange-handler request #(handle-ui-tool-approval-request system %)))
   :ui-tool-approval-approve (fn [request] (exchange-handler request #(handle-ui-tool-approval-decision system % (get-in request [:path-params :approval-id]) :approved)))
   :ui-tool-approval-deny (fn [request] (exchange-handler request #(handle-ui-tool-approval-decision system % (get-in request [:path-params :approval-id]) :denied)))
   :ui-tool-approval-run (fn [request] (exchange-handler request #(handle-ui-tool-approval-run system % (get-in request [:path-params :approval-id]))))
   :list-sessions (fn [request] (exchange-handler request #(handle-list-sessions system %)))
   :create-session (fn [request] (exchange-handler request #(handle-create-session system %)))
   :list-session-messages (fn [request] (exchange-handler request #(handle-list-messages system % (get-in request [:path-params :session-id]))))
   :list-runs (fn [request] (exchange-handler request #(handle-list-runs system %)))
   :create-run (fn [request] (exchange-handler request #(handle-create-run system %)))
   :get-run (fn [request] (exchange-handler request #(handle-get-run system % (get-in request [:path-params :run-id]))))
   :launch-run (fn [request] (exchange-handler request #(handle-launch-run system % (get-in request [:path-params :run-id]))))
   :signal-run (fn [request] (exchange-handler request #(handle-signal-run system % (get-in request [:path-params :run-id]))))
   :run-heartbeats (fn [request] (exchange-handler request #(handle-run-heartbeats system % (get-in request [:path-params :run-id]))))
   :run-checkpoints (fn [request] (exchange-handler request #(handle-run-checkpoints system % (get-in request [:path-params :run-id]))))
   :run-commands (fn [request] (exchange-handler request #(handle-run-commands system % (get-in request [:path-params :run-id]))))
   :run-control-register (fn [request] (exchange-handler request #(handle-run-control-register system % (get-in request [:path-params :run-id]))))
   :run-control-heartbeat (fn [request] (exchange-handler request #(handle-run-control-heartbeat system % (get-in request [:path-params :run-id]))))
   :run-control-checkpoint (fn [request] (exchange-handler request #(handle-run-control-checkpoint system % (get-in request [:path-params :run-id]))))
   :run-control-commands (fn [request] (exchange-handler request #(handle-run-control-commands system % (get-in request [:path-params :run-id]))))
   :run-control-command-ack (fn [request] (exchange-handler request #(handle-run-control-command-ack system % (get-in request [:path-params :run-id]) (get-in request [:path-params :command-id]))))
   :run-control-command-complete (fn [request] (exchange-handler request #(handle-run-control-command-complete system % (get-in request [:path-params :run-id]) (get-in request [:path-params :command-id]))))
   :run-control-transition (fn [request] (exchange-handler request #(handle-run-control-transition system % (get-in request [:path-params :run-id]))))
   :run-events (fn [request] (exchange-handler request #(handle-run-events system % (get-in request [:path-params :run-id]))))
   :run-wait (fn [request] (exchange-handler request #(handle-run-wait system % (get-in request [:path-params :run-id]))))
   :run-recover (fn [request] (exchange-handler request #(handle-run-recover system % (get-in request [:path-params :run-id]))))
   :reclaim-stale-runs (fn [request] (exchange-handler request #(handle-reclaim-stale-runs system %)))
   :list-tools (fn [request] (exchange-handler request #(handle-list-tools system %)))
   :execute-tool (fn [request] (exchange-handler request #(handle-execute-tool system % (get-in request [:path-params :tool-name]))))
   :list-tool-approvals (fn [request] (exchange-handler request #(handle-list-tool-approvals system %)))
   :create-tool-approval (fn [request] (exchange-handler request #(handle-create-tool-approval system %)))
   :approve-tool-approval (fn [request] (exchange-handler request #(handle-decide-tool-approval system % (get-in request [:path-params :approval-id]) :approved)))
   :deny-tool-approval (fn [request] (exchange-handler request #(handle-decide-tool-approval system % (get-in request [:path-params :approval-id]) :denied)))
   :list-skills (fn [request] (exchange-handler request #(handle-list-skills system %)))
   :list-channel-adapters (fn [request] (exchange-handler request #(handle-list-channel-adapters system %)))
   :list-events (fn [request] (exchange-handler request #(handle-list-events system %)))
   :telemetry (fn [request] (exchange-handler request #(handle-telemetry system %)))
   :memory-surfaces (fn [request] (exchange-handler request #(handle-memory-surfaces system %)))
   :memory-prompt (fn [request] (exchange-handler request #(handle-memory-prompt system %)))
   :memory-search (fn [request] (exchange-handler request #(handle-memory-search system %)))
   :memory-graph-save (fn [request] (exchange-handler request #(handle-memory-graph-save system %)))
   :memory-graph-query (fn [request] (exchange-handler request #(handle-memory-graph-query system %)))
   :list-agents (fn [request] (exchange-handler request #(handle-list-agents system %)))
   :create-agent (fn [request] (exchange-handler request #(handle-create-agent system %)))
   :agent-messages (fn [request] (exchange-handler request #(handle-agent-messages system % (get-in request [:path-params :agent-id]))))
   :agent-message (fn [request] (exchange-handler request #(handle-agent-message system % (get-in request [:path-params :agent-id]))))
   :agent-tool-execute (fn [request] (exchange-handler request #(handle-agent-tool-execute system % (get-in request [:path-params :agent-id]) (get-in request [:path-params :tool-name]))))
   :orchestrator-spawn-worker (fn [request] (exchange-handler request #(handle-orchestrator-spawn-worker system % (get-in request [:path-params :agent-id]))))
   :agent-step-execute (fn [request] (exchange-handler request #(handle-agent-step-execute system % (get-in request [:path-params :agent-id]))))
   :consume-agent-inbox (fn [request] (exchange-handler request #(handle-consume-agent-inbox system % (get-in request [:path-params :agent-id]))))
   :agent-interop (fn [request] (exchange-handler request #(handle-agent-interop system % (get-in request [:path-params :agent-id]))))
   :agent-interop-capabilities (fn [request] (exchange-handler request #(handle-agent-interop-capabilities system % (get-in request [:path-params :agent-id]))))
   :agent-interop-message (fn [request] (exchange-handler request #(handle-agent-interop-message system % (get-in request [:path-params :agent-id]))))
   :agent-interop-messages (fn [request] (exchange-handler request #(handle-agent-interop-messages system % (get-in request [:path-params :agent-id]))))
   :agent-interop-ack (fn [request] (exchange-handler request #(handle-agent-interop-ack system % (get-in request [:path-params :agent-id]) (get-in request [:path-params :message-id]))))
   :agent-interop-retry (fn [request] (exchange-handler request #(handle-agent-interop-retry system % (get-in request [:path-params :agent-id]) (get-in request [:path-params :message-id]))))
   :list-federated-peers (fn [request] (exchange-handler request #(handle-list-federated-peers system %)))
   :create-federated-peer (fn [request] (exchange-handler request #(handle-create-federated-peer system %)))
   :federation-inbox (fn [request] (exchange-handler request #(handle-federation-inbox system %)))
   :list-channels (fn [request] (exchange-handler request #(handle-list-channels system %)))
   :create-channel (fn [request] (exchange-handler request #(handle-create-channel system %)))
   :channel-messages (fn [request] (exchange-handler request #(handle-channel-messages system % (get-in request [:path-params :channel-id]))))
   :channel-message (fn [request] (exchange-handler request #(handle-channel-message system % (get-in request [:path-params :channel-id]))))})

(defn- route-handlers
  [system]
  (merge
   (exchange-handler-map system)
   {:ui-index (fn [_] (responses/html-response 200 (ui/index-page)))
    :public-file public-file-response
    :chat-completions (fn [request] (chat-completions-response system request))
    :events-stream (fn [request] (events-stream-response system request))
    :run-events-stream (fn [request] (run-events-stream-response system (get-in request [:path-params :run-id]) request))
    :ui-session-live (fn [request] (ui-session-live-response system request))
    :ui-events-live (fn [request] (ui-events-live-response system request))}))

(defn- bind-route-handlers
  [system]
  (let [handlers (route-handlers system)]
    (walk/postwalk
     (fn [node]
       (if (and (map? node) (contains? node :handler/id))
         (-> node
             (dissoc :handler/id)
             (assoc :handler (get handlers (:handler/id node))))
         node))
     routes/routes)))

(defn create-handler
  [system]
  (middleware/wrap-defaults
   (ring/ring-handler
    (ring/router (bind-route-handlers system)
                 {:conflicts nil})
    (fn [_] (responses/not-found-response)))
   (:api (:config system))))

(defn start-server!
  [system {:keys [host port]}]
  (let [server (http-kit/run-server (create-handler system)
                                    {:ip host
                                     :port (int port)})]
    (logging/log! :agent.http/server-started
                  {:host host
                   :port port})
    server))

(defn stop-server!
  [server]
  (when server
    (logging/log! :agent.http/server-stopping {})
    (server :timeout 100)))
