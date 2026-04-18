(ns agent.api.exchange
  (:require [agent.api.responses :as responses]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import
   (com.sun.net.httpserver Headers HttpExchange)
   (java.io ByteArrayInputStream ByteArrayOutputStream InputStream OutputStream)
   (java.net InetSocketAddress URI)
   (java.nio.charset StandardCharsets)))

(def response-status-attr "agent.response-status")

(defn- request-body-bytes
  [request]
  (let [body (:body request)]
    (cond
      (nil? body) (byte-array 0)
      (instance? InputStream body)
      (let [buffer (ByteArrayOutputStream.)]
        (io/copy body buffer)
        (.toByteArray buffer))
      (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
      (bytes? body) body
      :else (.getBytes (str body) StandardCharsets/UTF_8))))

(defn- headers-from-map
  [m]
  (let [headers (Headers.)]
    (doseq [[k v] m]
      (cond
        (sequential? v) (doseq [item v]
                          (.add headers (name k) (str item)))
        (some? v) (.add headers (name k) (str v))))
    headers))

(defn- request-uri
  [request]
  (URI.
   (str (name (or (:scheme request) :http))
        "://"
        (get-in request [:headers "host"] "localhost")
        (:uri request)
        (when-let [query-string (:query-string request)]
          (str "?" query-string)))))

(defn request->exchange
  [request]
  (let [request-bytes (request-body-bytes request)
        request-body (ByteArrayInputStream. request-bytes)
        response-body (ByteArrayOutputStream.)
        request-headers (headers-from-map (:headers request))
        response-headers (Headers.)
        attributes (atom {})
        status (atom 200)
        uri (request-uri request)
        remote-address (InetSocketAddress. "127.0.0.1" 0)
        local-address (InetSocketAddress. (or (:server-name request) "127.0.0.1")
                                          (int (or (:server-port request) 80)))]
    {:exchange
     (proxy [HttpExchange] []
       (getRequestHeaders [] request-headers)
       (getResponseHeaders [] response-headers)
       (getRequestURI [] uri)
       (getRequestMethod [] (str/upper-case (name (:request-method request))))
       (getHttpContext [] nil)
       (close []
         (.close request-body)
         (.close response-body))
       (getRequestBody [] request-body)
       (getResponseBody [] response-body)
       (sendResponseHeaders [response-status _response-length]
         (reset! status response-status))
       (getRemoteAddress [] remote-address)
       (getResponseCode [] @status)
       (getLocalAddress [] local-address)
       (getProtocol [] (or (:protocol request) "HTTP/1.1"))
       (getAttribute [name] (get @attributes name))
       (setAttribute [name value] (swap! attributes assoc name value))
       (setStreams [_in _out] nil)
       (getPrincipal [] nil))
     :status status
     :response-headers response-headers
     :response-body response-body}))

(defn exchange-result->response
  [{:keys [status response-headers response-body]}]
  {:status @status
   :headers (responses/headers->map response-headers)
   :body (.toByteArray ^ByteArrayOutputStream response-body)})

(defn invoke-exchange-handler
  [request handler-fn]
  (let [{:keys [exchange] :as exchange-state} (request->exchange request)]
    (handler-fn exchange)
    (exchange-result->response exchange-state)))
