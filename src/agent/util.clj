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

(defn truncate
  "Truncate text to max-chars, appending (marker-fn dropped-char-count)."
  [text max-chars marker-fn]
  (let [text* (str text)]
    (if (> (count text*) max-chars)
      (str (subs text* 0 max-chars) (marker-fn (- (count text*) max-chars)))
      text*)))

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
