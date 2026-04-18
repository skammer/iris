(ns agent.api.middleware
  (:require [agent.api.responses :as responses]
            [agent.logging :as logging]
            [org.httpkit.server :as http-kit])
  (:import
   (java.util UUID)))

(defn wrap-request-id
  [handler]
  (fn [request]
    (let [request-id (or (get-in request [:headers "x-request-id"])
                         (str (UUID/randomUUID)))
          response (handler (assoc request :request-id request-id))]
      (if (and response
               (not (satisfies? http-kit/Channel (:body response))))
        (update response :headers #(assoc (or %) "X-Request-Id" request-id))
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
        (responses/error-response e)))))

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

(defn wrap-defaults
  [handler]
  (-> handler
      wrap-request-id
      wrap-request-logging
      wrap-error-boundary))
