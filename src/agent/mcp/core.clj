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

(defn- valid-http-uri? [endpoint-url]
  (try
    (let [uri (URI. endpoint-url)
          scheme (some-> (.getScheme uri) str/lower-case)]
      (and (#{"http" "https"} scheme)
           (some? (.getHost uri))))
    (catch Exception _
      false)))

(defn- validate-endpoint-url! [endpoint-url]
  (when-not (and (string? endpoint-url)
                 (not (str/blank? endpoint-url))
                 (valid-http-uri? endpoint-url))
    (throw (ex-info "MCP endpoint-url must be an absolute http(s) URL"
                    {:type :invalid-mcp-endpoint
                     :endpoint-url endpoint-url}))))

(defn create-http-client
  [{:keys [endpoint-url headers protocol-version client-info capabilities timeout-ms telemetry]
    :or {protocol-version default-protocol-version
         client-info {:name "iris" :version "0.1.0"}
         capabilities {}
         timeout-ms 30000}}]
  (validate-endpoint-url! endpoint-url)
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

(defn- post-request! [client request]
  (http/post (:endpoint-url client)
             {:headers (request-headers client request)
              :body (json/generate-string request)
              :socket-timeout (:timeout-ms client)
              :connection-timeout (:timeout-ms client)
              :throw-exceptions false
              :as :text}))

(defn- with-mcp-telemetry [client request f]
  (let [start-ns (System/nanoTime)
        record! (fn [attrs]
                  (telemetry/record-mcp-call!
                   (:telemetry client)
                   (merge {:server-url (:endpoint-url client)
                           :method (:method request)
                           :duration-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)}
                          attrs)))]
    (try
      (let [result (f)]
        (record! {:success? true})
        result)
      (catch Exception e
        (record! {:success? false :error e})
        (throw e)))))

(defn rpc!
  [client request]
  (with-mcp-telemetry client request
    #(response->result! request (post-request! client request))))

(defn initialize!
  [client]
  (let [request (json-rpc-request
                 "initialize"
                 {:protocolVersion (:protocol-version client)
                  :capabilities (:capabilities client)
                  :clientInfo (:client-info client)})
        [response result] (with-mcp-telemetry client request
                            (fn []
                              (let [response (post-request! client request)]
                                [response (response->result! request response)])))
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

(declare json-schema->malli)

(defn- nullable-type? [type]
  (and (coll? type) (some #{"null"} type)))

(defn- type-name [type]
  (if (keyword? type) (name type) type))

(defn- non-null-types [type]
  (if (coll? type)
    (remove #{"null"} (map type-name type))
    [(type-name type)]))

(defn- json-schema-type [schema]
  (let [type (:type schema)]
    (if (coll? type)
      (map type-name type)
      (or (type-name type)
          (cond
            (:properties schema) "object"
            (:items schema) "array"
            (:enum schema) "string"
            :else nil)))))

(defn- json-schema-object->malli [schema]
  (let [required (set (map name (:required schema)))
        properties (:properties schema)
        entries (mapv (fn [[property child-schema]]
                        (let [property-name (name property)
                              property-key (keyword property-name)
                              optional? (not (contains? required property-name))]
                          (if optional?
                            [property-key {:optional true} (json-schema->malli child-schema)]
                            [property-key (json-schema->malli child-schema)])))
                      properties)
        props (cond-> {}
                (false? (:additionalProperties schema))
                (assoc :closed true))]
    (if (seq entries)
      (into [:map props] entries)
      (if (:closed props)
        [:map props]
        [:map-of :any :any]))))

(defn- json-schema-array->malli [schema]
  [:vector (json-schema->malli (or (:items schema) {}))])

(defn- json-schema-type->malli [schema type]
  (case type
    "null" :nil
    "string" :string
    "integer" :int
    "number" number?
    "boolean" :boolean
    "array" (json-schema-array->malli schema)
    "object" (json-schema-object->malli schema)
    :any))

(defn- json-schema-union->malli [schema types]
  (let [schemas (mapv #(json-schema-type->malli schema %) types)]
    (case (count schemas)
      0 :any
      1 (first schemas)
      (into [:or] schemas))))

(defn- json-schema->malli [schema]
  (let [schema (or schema {})
        type (json-schema-type schema)
        malli-schema (cond
                       (contains? schema :const) [:= (:const schema)]
                       (contains? schema :enum) (into [:enum] (:enum schema))
                       (seq (:anyOf schema)) (into [:or] (map json-schema->malli (:anyOf schema)))
                       (seq (:oneOf schema)) (into [:or] (map json-schema->malli (:oneOf schema)))
                       (coll? type) (json-schema-union->malli schema (non-null-types type))
                       :else (json-schema-type->malli schema type))]
    (if (nullable-type? type)
      [:maybe malli-schema]
      malli-schema)))

(defn mcp-tool->description
  [mcp-tool & {:keys [name-prefix required-permissions]}]
  (let [input-schema (or (:inputSchema mcp-tool)
                         (:input_schema mcp-tool))
        malli-schema (if input-schema
                       (json-schema->malli input-schema)
                       [:map-of :any :any])]
    (tools/create-tool-description
     (keyword (str (or name-prefix "") (:name mcp-tool)))
     (:description mcp-tool)
     :category :mcp
     :input-schema malli-schema
     :required-permissions (or required-permissions #{:mcp-call})
     :source :mcp
     :source-details {:remote-name (:name mcp-tool)
                      :input-schema input-schema})))

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
  (let [remote-tools (mapv #(apply create-remote-tool client % opts)
                           (list-tools! client))
        names (mapv (comp :name tools/describe) remote-tools)
        duplicate-name (first (keep (fn [[tool-name count]]
                                      (when (> count 1) tool-name))
                                    (frequencies names)))
        existing-name (first (filter #(tools/get-tool registry %) names))]
    (when duplicate-name
      (throw (ex-info "MCP server returned duplicate tool names"
                      {:type :mcp-tool-name-collision
                       :tool-name duplicate-name})))
    (when existing-name
      (throw (ex-info "MCP remote tool would overwrite an existing tool"
                      {:type :mcp-tool-name-collision
                       :tool-name existing-name})))
    (reduce tools/register-tool registry remote-tools)))

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
