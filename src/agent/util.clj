(ns agent.util
  "Small shared utilities used across runtime boundaries."
  (:require
   [cheshire.core :as json])
  (:import
   (java.time Instant)))

(defn now-str []
  (str (Instant/now)))

(defn duration-ms [start-ns]
  (/ (double (- (System/nanoTime) start-ns)) 1000000.0))

(defn result-content [value]
  (cond
    (string? value) value
    (nil? value) ""
    :else (json/generate-string value)))

(defn emit!
  [sink event]
  (when sink
    (sink event))
  event)
