(ns agent.api.middleware
  (:require [agent.api.helpers :as h]
            [agent.api.responses :as responses]
            [agent.logging :as logging]
            [agent.security :as security]
            [clojure.string :as str]
            [org.httpkit.server :as http-kit])
  (:import
   (java.util Base64)
   (java.util UUID)))

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
        (responses/error-response e request)))))

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
      (= path "/ui")))

(defn- run-control-path? [path]
  (boolean (re-matches #"^/v1/runs/[^/]+/control(?:/.*)?$" (or path ""))))

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
               (not (run-control-path? (:uri request)))
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
       wrap-request-id)))
