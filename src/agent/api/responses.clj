(ns agent.api.responses
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import
   (com.sun.net.httpserver Headers)
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

(defn error-response
  [error]
  (let [{:keys [type status details]
         error-code :error} (ex-data error)]
    (if (and status error-code)
      (json-response status
                     (cond-> {:error error-code
                              :message (.getMessage error)}
                       details (assoc :details details)))
      (json-response 500
                     {:error "internal_error"
                      :message (.getMessage error)}))))

(defn not-found-response
  []
  (json-response 404 {:error "not_found"}))

(defn headers->map
  [^Headers headers]
  (reduce-kv
   (fn [acc k values]
     (assoc acc k (if (= 1 (count values))
                    (first values)
                    (str/join ", " values))))
   {}
   headers))

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
        (catch Exception _
          nil)
        (finally
          (.close ^OutputStream stream))))
    {:status 200
     :headers headers
     :body body-seq}))

(defn utf8-bytes
  [text]
  (.getBytes text StandardCharsets/UTF_8))
