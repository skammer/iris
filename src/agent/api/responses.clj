(ns agent.api.responses
  (:require
   [agent.logging :as logging]
   [cheshire.core :as json])
  (:import
   (java.io ByteArrayOutputStream OutputStream)
   (java.nio.charset StandardCharsets)
   (java.util.concurrent LinkedBlockingQueue)))

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
  [error]
  (let [data (ex-data error)
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
                     {:error "internal_error"
                      :message (.getMessage error)}))))

(defn not-found-response
  []
  (json-response 404 {:error "not_found"}))

(defn stream-response
  [headers writer-fn]
  (let [chunks (LinkedBlockingQueue.)
        done (Object.)
        buffer (ByteArrayOutputStream.)
        stream (proxy [OutputStream] []
                 (write
                   ([b]
                    (.write buffer (int b)))
                   ([bs off len]
                    (.write buffer ^bytes bs (int off) (int len))))
                 (flush []
                   (let [bytes (.toByteArray buffer)]
                     (when (pos? (alength bytes))
                       (.put chunks (String. ^bytes bytes StandardCharsets/UTF_8))
                       (.reset buffer))))
                 (close []
                   (.flush ^OutputStream this)
                   (.put chunks done)))
        body-seq ((fn step []
                    (lazy-seq
                     (let [chunk (.take chunks)]
                       (when-not (identical? chunk done)
                         (cons chunk (step)))))))]
    (future
      (try
        (writer-fn stream)
        (catch Throwable t
          (logging/log-error! :agent.api.responses/stream-writer-failed t))
        (finally
          (.close ^OutputStream stream))))
    {:status 200
     :headers headers
     :body body-seq}))

(defn utf8-bytes
  [text]
  (.getBytes text StandardCharsets/UTF_8))
