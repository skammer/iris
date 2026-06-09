(ns agent.llm.providers.common
  "Shared HTTP/stream helpers for LLM providers."
  (:require
   [agent.llm.core :as llm-core]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [clojure.string :as str]))

(defn trim-trailing-slash [value]
  (str/replace (or value "") #"/+$" ""))

(defn endpoint [base-url path]
  (str (trim-trailing-slash base-url) path))

(defn stream-structured-output? [config opts]
  (and (:structured-output opts)
       (not (false? (if (contains? opts :stream-structured-output?)
                      (:stream-structured-output? opts)
                      (:stream-structured-output? config true))))))

(defn close-response-body! [response]
  (when-let [body (:body response)]
    (when (instance? java.io.Closeable body)
      (.close ^java.io.Closeable body))))

(defn- header-value [headers header-name]
  (let [target (str/lower-case header-name)]
    (some (fn [[k v]]
            (when (= target (str/lower-case (name k))) v))
          headers)))

(defn http-error [message response]
  (llm-core/llm-error :http-error
                      message
                      {:status (:status response)
                       :retry-after (header-value (:headers response) "Retry-After")
                       :content-type (header-value (:headers response) "Content-Type")}))

(def ^:private transport-option-keys
  [:timeout-ms :max-retries :initial-delay :max-delay])

(defn with-transport-options
  [request config opts]
  (merge request
         (select-keys config transport-option-keys)
         (select-keys opts transport-option-keys)))

(defn http-request-options [request]
  (cond-> (apply dissoc request transport-option-keys)
    (:timeout-ms request) (assoc :socket-timeout (:timeout-ms request)
                                 :connection-timeout (:timeout-ms request))))

(defn- retry-args [request]
  (mapcat (fn [k]
            (when-let [value (get request k)]
              [k value]))
          [:max-retries :initial-delay :max-delay]))

(defn checked-response [response error-fn]
  (if (<= 200 (:status response 0) 299)
    response
    (let [error (error-fn response)]
      (close-response-body! response)
      (throw error))))

(defn post-json
  [url request error-fn]
  (apply llm-core/retry-with-backoff
         #(checked-response (http/post url (assoc (http-request-options request) :throw-exceptions false))
                            error-fn)
         (retry-args request)))

(defn post-stream
  [url request error-fn]
  (checked-response
   (http/post url (assoc (http-request-options request)
                         :throw-exceptions false
                         :as :stream))
   error-fn))

(defn stream-channel [f]
  (let [ch (async/chan)]
    (async/thread
      (try
        (f #(async/>!! ch %))
        (catch Exception e
          (async/>!! ch (llm-core/stream-error-event e)))
        (finally
          (async/close! ch))))
    ch))
