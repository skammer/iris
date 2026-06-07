(ns agent.api.responses
  (:require
   [cheshire.core :as json]))

(defn json-response
  ([status payload]
   (json-response status payload nil))
  ([status payload headers]
   {:status status
    :headers (merge {"Content-Type" "application/json"} headers)
    :body (json/generate-string payload)}))

(defn html-response
  ([status html]
   (html-response status html nil))
  ([status html headers]
   {:status status
    :headers (merge {"Content-Type" "text/html; charset=utf-8"} headers)
    :body html}))

(defn bytes-response
  ([status content-type bytes]
   (bytes-response status content-type bytes nil))
  ([status content-type bytes headers]
   {:status status
    :headers (merge {"Content-Type" content-type} headers)
    :body bytes}))

(defn- coercion-error-message [data]
  (let [in (:in data)
        humanized (:humanized data)]
    (cond
      (and humanized (map? humanized))
      (let [[path msg] (first humanized)]
        (str (or (some-> path first name) "request") " " (if (sequential? msg) (first msg) msg)))
      (sequential? humanized) (str (first humanized))
      humanized (str humanized)
      :else (str "Invalid " (or (some-> in first name) "request")))))

(defn error-response
  ([error]
   (error-response error nil))
  ([error request]
   (let [data (ex-data error)
         request-id (or (:request-id request) (:request-id data))
         {:keys [type status details]
          error-code :error} data]
     (cond
       (and status error-code)
       (json-response status
                      (cond-> {:error error-code
                               :message (.getMessage error)}
                        details (assoc :details details)))

       (= :reitit.coercion/request-coercion type)
       (json-response 400
                      {:error "bad_request"
                       :message (coercion-error-message data)
                       :details {:in (:in data)
                                 :errors (:humanized data)}})

       :else
       (json-response 500
                      (cond-> {:error "internal_error"
                               :message "Internal server error"}
                        request-id (assoc :request_id request-id)))))))

(defn not-found-response
  []
  (json-response 404 {:error "not_found"}))
