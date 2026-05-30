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

(defn http-error [message response]
  (llm-core/llm-error :http-error
                      message
                      {:status (:status response)
                       :headers (:headers response)
                       :body (:body response)}))

(defn checked-response [response error-fn]
  (if (<= 200 (:status response 0) 299)
    response
    (let [error (error-fn response)]
      (close-response-body! response)
      (throw error))))

(defn post-json [url request error-fn]
  (llm-core/retry-with-backoff
   #(checked-response (http/post url (assoc request :throw-exceptions false))
                      error-fn)))

(defn post-stream [url request error-fn]
  (checked-response
   (http/post url (assoc request
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
