(ns agent.api.middleware
  (:require [agent.api.errors :as errors]
            [agent.api.helpers :as h]
            [agent.api.responses :as responses]
            [agent.logging :as logging]
            [agent.security :as security]
            [clojure.string :as str]
            [org.httpkit.server :as http-kit])
  (:import
   (java.io ByteArrayOutputStream)
   (java.nio.charset StandardCharsets)
   (java.util Base64)
   (java.util UUID)
   (java.util.zip GZIPOutputStream)))

(def ^:private byte-array-class (Class/forName "[B"))

(defn- header-value [headers header-name]
  (some (fn [[k value]]
          (when (= (str/lower-case (name k)) (str/lower-case header-name))
            value))
        headers))

(defn- remove-header [headers header-name]
  (into {}
        (remove (fn [[k _]]
                  (= (str/lower-case (name k)) (str/lower-case header-name))))
        headers))

(defn- compressible-content-type? [content-type]
  (let [value (str/lower-case (or content-type ""))]
    (or (str/starts-with? value "text/")
        (str/includes? value "json")
        (str/includes? value "javascript")
        (str/includes? value "svg")
        (str/includes? value "xml"))))

(defn- gzip-bytes [bytes]
  (let [output (ByteArrayOutputStream.)]
    (with-open [gzip (GZIPOutputStream. output)]
      (.write gzip bytes))
    (.toByteArray output)))

(defn- gzip-body [body]
  (cond
    (string? body) (gzip-bytes (.getBytes ^String body StandardCharsets/UTF_8))
    (instance? byte-array-class body) (gzip-bytes body)
    :else nil))

(defn wrap-gzip-response
  [handler]
  (fn [request]
    (let [response (handler request)
          headers (:headers response)
          encoding (str/lower-case (or (get-in request [:headers "accept-encoding"]) ""))
          body (:body response)]
      (if (and response
               (str/includes? encoding "gzip")
               (not (str/includes? encoding "gzip;q=0"))
               (nil? (header-value headers "content-encoding"))
               (compressible-content-type? (header-value headers "content-type"))
               (or (string? body) (instance? byte-array-class body))
               (> (if (string? body)
                    (count (.getBytes ^String body StandardCharsets/UTF_8))
                    (alength ^bytes body))
                  512))
        (let [compressed (gzip-body body)
              vary (header-value headers "vary")
              headers* (-> headers
                           (remove-header "content-length")
                           (assoc "Content-Encoding" "gzip"
                                  "Content-Length" (str (alength ^bytes compressed))
                                  "Vary" (if (str/blank? (str vary))
                                           "Accept-Encoding"
                                           (str vary ", Accept-Encoding"))))]
          (assoc response :headers headers* :body compressed))
        response))))

(defn wrap-request-id
  [handler]
  (fn [request]
    (let [request-id (or (get-in request [:headers "x-request-id"])
                         (str (UUID/randomUUID)))
          response (handler (assoc request :request-id request-id))]
      (if (and response
               (not (satisfies? http-kit/Channel (:body response))))
        (update response :headers #(assoc (or % {}) "X-Request-Id" request-id))
        response))))

(defn wrap-error-boundary
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (logging/log-error! :agent.http/request-failed e
                            {:method (some-> (:request-method request) name)
                             :path (:uri request)
                             :request-id (:request-id request)})
        ;; Domain errors that escape handlers map to their canonical HTTP
        ;; shape here, so handlers don't need per-call catch ladders.
        (responses/error-response (errors/domain-error->api-error e) request)))))

(defn wrap-request-logging
  [handler]
  (fn [request]
    (let [started-at (System/nanoTime)
          method (some-> (:request-method request) name)
          path (:uri request)]
      (logging/log! :agent.http/request-started
                    {:method method
                     :path path
                     :request-id (:request-id request)})
      (let [response (handler request)]
        (logging/log! :agent.http/request-completed
                      {:method method
                       :path path
                       :status (or (:status response) 200)
                       :duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))
                       :request-id (:request-id request)})
        response))))

(defn- protected-path? [path]
  (or (str/starts-with? path "/v1/")
      (= path "/v1")
      (str/starts-with? path "/ui/")
      (= path "/ui")
      (str/starts-with? path "/message:")
      (str/starts-with? path "/tasks")
      (= path "/extendedAgentCard")))

(defn- basic-token [authorization]
  (when (and authorization
             (str/starts-with? (str/lower-case authorization) "basic "))
    (try
      (let [decoded (String. (.decode (Base64/getDecoder) (subs authorization 6)) "UTF-8")
            [_ password] (str/split decoded #":" 2)]
        password)
      (catch IllegalArgumentException _
        nil))))

(defn- request-api-key [request]
  (or (get-in request [:headers "x-api-key"])
      (h/bearer-token (get-in request [:headers "authorization"]))
      (basic-token (get-in request [:headers "authorization"]))))

(defn wrap-api-key-auth
  [handler api-config]
  (let [api-key (some-> (:key api-config) str str/trim not-empty)]
    (fn [request]
      (if (and api-key
               (protected-path? (:uri request))
               (not (security/constant-time= api-key (request-api-key request))))
        (responses/json-response 401
                                 {:error "unauthorized"
                                  :message "Invalid or missing API key"}
                                 {"WWW-Authenticate" "Basic realm=\"iris\""})
        (handler request)))))

(defn wrap-defaults
  ([handler] (wrap-defaults handler nil))
  ([handler api-config]
   (-> handler
       (wrap-api-key-auth api-config)
       wrap-error-boundary
       wrap-request-logging
       wrap-gzip-response
       wrap-request-id)))
