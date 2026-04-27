(ns agent.api.streaming
  "Server-Sent Events helpers — both legacy JDK-stream writers (used by old
   exchange-based handlers) and ring/http-kit channel writers (used by new
   *-response handlers). The legacy writers will be deleted in P2."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [org.httpkit.server :as http-kit])
  (:import
   (java.nio.charset StandardCharsets)))

(defn write-sse-bytes! [stream text]
  (.write stream (.getBytes text StandardCharsets/UTF_8))
  (.flush stream))

(defn write-sse-chunk! [stream payload]
  (write-sse-bytes! stream (str "data: " (json/generate-string payload) "\n\n")))

(defn write-sse-done! [stream]
  (write-sse-bytes! stream "data: [DONE]\n\n"))

(defn datastar-patch-frame [html]
  (let [lines (str/split (str html) #"\n" -1)]
    (str "event: datastar-patch-elements\n"
         (->> lines
              (map #(str "data: elements " %))
              (str/join "\n"))
         "\n\n")))

(defn write-datastar-patch! [stream html]
  (write-sse-bytes! stream (datastar-patch-frame html)))

(defn sse-response
  ([request on-open on-close]
   (sse-response request on-open on-close ":\n\n"))
  ([request on-open on-close initial-body]
   (http-kit/as-channel
    request
    {:on-open (fn [channel]
                (http-kit/send! channel
                                {:status 200
                                 :headers {"Content-Type" "text/event-stream"
                                           "Cache-Control" "no-cache"}
                                 :body initial-body}
                                false)
                (when on-open
                  (on-open channel)))
     :on-close (fn [channel status]
                 (when on-close
                   (on-close channel status)))})))

(defn send-sse-text! [channel text]
  (http-kit/send! channel text false))

(defn send-sse-chunk! [channel payload]
  (send-sse-text! channel (str "data: " (json/generate-string payload) "\n\n")))

(defn send-sse-done! [channel]
  (send-sse-text! channel "data: [DONE]\n\n"))

(defn send-datastar-patch! [channel html]
  (send-sse-text! channel (datastar-patch-frame html)))
