(ns agent.api
  "Minimal HTTP API for rewritten runtime."
  (:require
   [agent.llm.core :as llm-core]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
   (java.net InetSocketAddress)
   (java.nio.charset StandardCharsets)))

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

(defn- split-path [^HttpExchange exchange]
  (let [path (.getPath (.getRequestURI exchange))]
    (->> (str/split path #"/")
         (remove str/blank?)
         vec)))

(defn- session->response [session]
  {:id (:id session)
   :title (:title session)
   :created_at (:created-at session)})

(defn- message->response [message]
  {:role (:role message)
   :content (:content message)
   :created_at (:created-at message)})

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

(defn- persist-completion! [system messages content {:keys [session-id]}]
  (let [provider (name (get-in system [:config :llm :provider]))
        user-message (last (filter #(= "user" (:role %)) messages))]
    (when session-id
      (when-let [prompt (:content user-message)]
        (sqlite/append-message! (:store system) session-id "user" prompt))
      (sqlite/append-message! (:store system) session-id "assistant" content))
    (sqlite/log-completion! (:store system)
                            {:session-id session-id
                             :provider provider
                             :model (get-in system [:config :llm :model])
                             :prompt (:content user-message)
                             :response content})))

(defn- complete! [system messages {:keys [session-id]}]
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

(defn- handle-chat-completions-stream [system exchange messages session-id]
  (let [stream-id (str "chatcmpl-" (System/currentTimeMillis))
        provider (name (get-in system [:config :llm :provider]))
        model (get-in system [:config :llm :model])
        chunks (llm-core/stream (:llm-provider system) messages {})]
    (try
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
                             :provider (get-in system [:config :llm :provider])}))

(defn- handle-create-session [system exchange]
  (let [body (read-json-body exchange)
        title (:title body)]
    (when (and (some? title) (not (string? title)))
      (throw (api-error 400 "bad_request" "title must be a string")))
    (let [session (sqlite/create-session! (:store system) title)]
      (write-json! exchange 201 (session->response session)))))

(defn- handle-list-sessions [system exchange]
  (write-json! exchange 200 {:data (mapv session->response (sqlite/list-sessions (:store system)))}))

(defn- handle-list-messages [system exchange session-id]
  (ensure-session-exists! system session-id)
  (write-json! exchange 200 {:data (mapv message->response
                                         (sqlite/list-messages (:store system) session-id))}))

(defn- handle-chat-completions [system exchange]
  (let [{:keys [messages session-id stream?]}
        (normalize-chat-request (read-json-body exchange))]
    (ensure-session-exists! system session-id)
    (if stream?
      (handle-chat-completions-stream system exchange messages session-id)
      (let [result (complete! system messages {:session-id session-id})]
        (write-json! exchange 200 (openai-style-completion system session-id (:content result)))))))

(defn create-handler
  [system]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod exchange)
              path (split-path exchange)]
          (cond
            (and (= method "GET") (= path ["health"]))
            (handle-health system exchange)

            (and (= method "GET") (= path ["v1" "sessions"]))
            (handle-list-sessions system exchange)

            (and (= method "POST") (= path ["v1" "sessions"]))
            (handle-create-session system exchange)

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
        handler (create-handler system)]
    (.createContext server "/" handler)
    (.setExecutor server nil)
    (.start server)
    server))

(defn stop-server!
  [^HttpServer server]
  (when server
    (.stop server 0)))
