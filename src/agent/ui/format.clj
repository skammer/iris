(ns agent.ui.format
  "Shared display formatting and status indicators."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.net URLEncoder)))

(defn- keyword-label [value]
  (if-let [ns (namespace value)]
    (str ns "/" (name value))
    (name value)))

(defn- json-ready [value]
  (cond
    (keyword? value) (keyword-label value)
    (map? value) (into {}
                       (map (fn [[k v]]
                              [(if (keyword? k) (keyword-label k) k)
                               (json-ready v)]))
                       value)
    (set? value) (mapv json-ready value)
    (sequential? value) (mapv json-ready value)
    :else value))

(defn pretty-json [value]
  (try
    (json/generate-string (json-ready (if (string? value)
                                        (json/parse-string value true)
                                        value))
                          {:pretty true})
    (catch Exception _
      (if (string? value)
        value
        (binding [*print-namespace-maps* false]
          (pr-str value))))))

(defn url-encode [value]
  (URLEncoder/encode (str value) "UTF-8"))

(def ^:private status-dot-classes
  {"running" "dot--live"
   "requested" "dot--live"
   "completed" "dot--ok"
   "succeeded" "dot--ok"
   "success" "dot--ok"
   "ok" "dot--ok"
   "done" "dot--ok"
   "failed" "dot--err"
   "error" "dot--err"
   "cancelled" "dot--err"
   "denied" "dot--err"
   "pending" "dot--warn"
   "approved" "dot--ok"})

(defn status-dot
  "Square status indicator, colored by status keyword/string. Stale items
   always show as warning regardless of their nominal status."
  ([status] (status-dot status nil))
  ([status {:keys [stale?]}]
   [:span.dot {:class (if stale?
                        "dot--warn"
                        (get status-dot-classes (some-> status name str/lower-case) "dot--idle"))
               :aria-hidden "true"}]))

(defn short-timestamp
  "Compact 'MM-DD HH:MM' rendering of an ISO-8601 timestamp for list rows."
  [value]
  (let [s (str value)]
    (if-let [[_ mm-dd hh-mm] (re-find #"^\d{4}-(\d{2}-\d{2})T(\d{2}:\d{2})" s)]
      (str mm-dd " " hh-mm)
      (not-empty s))))

(def ^:private uuid-rx
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn short-id
  "First segment of a UUID for display — pair with a :title attribute
   carrying the full value. Non-UUID identifiers pass through unchanged."
  [value]
  (let [s (str value)]
    (if (re-matches uuid-rx s)
      (subs s 0 8)
      s)))

(defn format-tokens
  "Compact human-readable token count: 950 -> \"950\", 12345 -> \"12.3k\", 2300000 -> \"2.3M\"."
  [n]
  (let [n (long (or n 0))]
    (cond
      (>= n 1000000) (format "%.1fM" (/ (double n) 1e6))
      (>= n 1000) (format "%.1fk" (/ (double n) 1e3))
      :else (str n))))

(defn- usage-tokens
  "Total tokens for one message's usage map, if present."
  [metadata]
  (some-> (:usage metadata) :tokens long))

(defn- usage-cached-tokens
  [metadata]
  (some-> (:usage metadata) :cached-tokens long))

(defn- usage-tps
  [metadata]
  (let [{:keys [completion-tokens duration-ms]} (:usage metadata)]
    (when (and (number? completion-tokens)
               (pos? completion-tokens)
               (number? duration-ms)
               (pos? duration-ms))
      (/ (* 1000.0 (double completion-tokens))
         (double duration-ms)))))

(defn format-tps [value]
  (format "%.1f" (double value)))

(defn- message-meta-suffix
  "Per-message stats badge text: token count for the turn + tool-call count."
  [metadata tool-calls]
  (let [tok (usage-tokens metadata)
        cached (usage-cached-tokens metadata)
        tps (usage-tps metadata)
        n-tools (count tool-calls)]
    (str (when (and tok (pos? tok)) (str " | " (format-tokens tok) " tok"))
         (when (and cached (pos? cached)) (str " | " (format-tokens cached) " cache"))
         (when tps (str " | " (format-tps tps) " t/s"))
         (when (pos? n-tools) (str " | " n-tools " tool" (when (> n-tools 1) "s"))))))

(defn ^:no-doc message-meta-text
  [{:keys [created-at metadata excluded-from-context? tool-calls]}]
  (str (short-timestamp created-at)
       (when (:queued metadata) " | queued")
       (when excluded-from-context? " | out-of-context")
       (message-meta-suffix metadata tool-calls)))
