(ns agent.mcp.core
  "MCP Streamable HTTP client and tool ABI adapters."
  (:require
   [agent.telemetry :as telemetry]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str])
  (:import
   (java.net URI)
   (java.util UUID)))

(def default-protocol-version "2025-06-18")

(defn- random-id [] (str (UUID/randomUUID)))

(defn create-http-client
  [{:keys [endpoint-url headers protocol-version client-info capabilities timeout-ms telemetry]
    :or {protocol-version default-protocol-version
         client-info {:name "iris" :version "0.1.0"}
         capabilities {}
         timeout-ms 30000}}]
  {:transport :streamable-http
   :endpoint-url endpoint-url
   :headers (or headers {})
   :protocol-version protocol-version
   :client-info client-info
   :capabilities capabilities
   :timeout-ms timeout-ms
   :session-id nil
   :telemetry telemetry})

(defn- header-value [response header-name]
  (let [target (str/lower-case header-name)]
    (some (fn [[k v]]
            (when (= target (str/lower-case (name k))) v))
          (:headers response))))

(defn- mcp-name [request]
  (or (get-in request [:params :name])
      (get-in request [:params :uri])))

(defn- request-headers [client request]
  (cond-> (merge {"Content-Type" "application/json"
                  "Accept" "application/json, text/event-stream"
                  "Mcp-Method" (:method request)}
                 (:headers client))
    (:session-id client) (assoc "Mcp-Session-Id" (:session-id client))
    (mcp-name request) (assoc "Mcp-Name" (str (mcp-name request)))))

(defn json-rpc-request
  ([method params] (json-rpc-request method params (random-id)))
  ([method params id]
   (cond-> {:jsonrpc "2.0"
            :id id
            :method method}
     (some? params) (assoc :params params))))

(defn json-rpc-notification
  [method params]
  (cond-> {:jsonrpc "2.0"
           :method method}
    (some? params) (assoc :params params)))

(defn- parse-json-body [body]
  (cond
    (nil? body) nil
    (map? body) body
    (string? body) (when-not (str/blank? body)
                     (json/parse-string body true))
    :else body))

(defn- sse-data-events [body]
  (let [lines (str/split-lines (or body ""))
        [events current] (reduce (fn [[events current] line]
                                   (cond
                                     (str/blank? line)
                                     [(cond-> events (seq current) (conj current)) []]

                                     (str/starts-with? line "data:")
                                     [events (conj current (str/trim (subs line 5)))]

                                     :else [events current]))
                                 [[] []]
                                 lines)]
    (cond-> events (seq current) (conj current))))

(defn- parse-response-body [response]
  (let [content-type (or (header-value response "Content-Type") "")]
    (if (str/includes? (str/lower-case content-type) "text/event-stream")
      (when-let [event (first (sse-data-events (:body response)))]
        (parse-json-body (str/join "\n" event)))
      (parse-json-body (:body response)))))

(defn- response->result! [request response]
  (when-not (<= 200 (:status response 0) 299)
    (throw (ex-info "MCP HTTP request failed"
                    {:type :mcp-http-error
                     :status (:status response)
                     :method (:method request)
                     :body (:body response)})))
  (when-not (= 202 (:status response))
    (let [body (parse-response-body response)]
      (when-let [error (:error body)]
        (throw (ex-info (or (:message error) "MCP JSON-RPC error")
                        {:type :mcp-json-rpc-error
                         :method (:method request)
                         :error error})))
      (:result body))))

(defn rpc!
  [client request]
  (let [start-ns (System/nanoTime)
        result (try
                 (let [response (http/post (:endpoint-url client)
                                           {:headers (request-headers client request)
                                            :body (json/generate-string request)
                                            :socket-timeout (:timeout-ms client)
                                            :connection-timeout (:timeout-ms client)
                                            :throw-exceptions false
                                            :as :text})]
                   (response->result! request response))
                 (catch Exception e
                   (telemetry/record-mcp-call! (:telemetry client)
                                               {:server-url (:endpoint-url client)
                                                :method (:method request)
                                                :duration-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
                                                :success? false
                                                :error e})
                   (throw e)))]
    (telemetry/record-mcp-call! (:telemetry client)
                                {:server-url (:endpoint-url client)
                                 :method (:method request)
                                 :duration-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
                                 :success? true})
    result))

(defn initialize!
  [client]
  (let [request (json-rpc-request
                 "initialize"
                 {:protocolVersion (:protocol-version client)
                  :capabilities (:capabilities client)
                  :clientInfo (:client-info client)})
        response (http/post (:endpoint-url client)
                            {:headers (request-headers client request)
                             :body (json/generate-string request)
                             :socket-timeout (:timeout-ms client)
                             :connection-timeout (:timeout-ms client)
                             :throw-exceptions false
                             :as :text})
        result (response->result! request response)
        client* (assoc client
                       :server-info (:serverInfo result)
                       :server-capabilities (:capabilities result)
                       :session-id (header-value response "Mcp-Session-Id"))]
    (try
      (rpc! client* (json-rpc-notification "notifications/initialized" nil))
      client*
      (catch Exception e
        (assoc client* :initialized-notification-error
               (cond-> {:message (.getMessage e)}
                 (ex-data e) (merge (ex-data e))))))))

(defn list-tools!
  [client]
  (vec (:tools (rpc! client (json-rpc-request "tools/list" {})))))

(defn call-tool!
  [client tool-name arguments]
  (rpc! client (json-rpc-request "tools/call"
                                 {:name (if (keyword? tool-name) (name tool-name) (str tool-name))
                                  :arguments (or arguments {})})))

(defn tool-description->mcp
  [description]
  {:name (name (:name description))
   :description (:description description)
   :inputSchema (:input-schema description)})

(defn mcp-tool->description
  [mcp-tool & {:keys [name-prefix required-permissions]}]
  (tools/create-tool-description
   (keyword (str (or name-prefix "") (:name mcp-tool)))
   (:description mcp-tool)
   :category :mcp
   :input-schema [:map-of :any :any]
   :required-permissions (or required-permissions #{:mcp-call})
   :source :mcp
   :source-details {:remote-name (:name mcp-tool)
                    :input-schema (or (:inputSchema mcp-tool)
                                      (:input_schema mcp-tool))}))

(defn create-remote-tool
  [client mcp-tool & opts]
  (let [description (apply mcp-tool->description mcp-tool opts)
        remote-name (:name mcp-tool)]
    (tools/create-tool
     {:description description
      :execute-fn (fn [input _context]
                    (call-tool! client remote-name input))
      :health-fn (fn [] {:healthy true
                         :details {:transport (:transport client)
                                   :endpoint-url (:endpoint-url client)
                                   :remote-name remote-name}})})))

(defn register-remote-tools!
  [registry client & opts]
  (reduce tools/register-tool
          registry
          (map #(apply create-remote-tool client % opts)
               (list-tools! client))))

(defn agent-envelope
  [{:keys [id from-address to-address message-type content metadata]}]
  {:jsonrpc "2.0"
   :id (or id (random-id))
   :method "agents/message"
   :params {:from from-address
            :to to-address
            :messageType (or message-type "request")
            :content content
            :metadata (or metadata {})}})

(defn agent-envelope? [value]
  (and (= "2.0" (:jsonrpc value))
       (= "agents/message" (:method value))
       (map? (:params value))))

(defn endpoint-origin [endpoint-url]
  (let [uri (URI. endpoint-url)]
    (str (.getScheme uri) "://" (.getAuthority uri))))
