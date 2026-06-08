(ns agent.api.helpers
  "Ring-request parsing helpers. All input fns take a ring request map; output
  helpers (json/html/bytes responses) live in agent.api.responses."
  (:require
   [agent.api.errors :as errors]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (com.fasterxml.jackson.core JsonProcessingException)
   (java.net URLDecoder)
   (java.nio.charset StandardCharsets)))

(defn content-type-for-path [path]
  (cond
    (str/ends-with? path ".css") "text/css; charset=utf-8"
    (str/ends-with? path ".js") "application/javascript; charset=utf-8"
    (str/ends-with? path ".woff2") "font/woff2"
    (str/ends-with? path ".woff") "font/woff"
    (str/ends-with? path ".ttf") "font/ttf"
    (str/ends-with? path ".otf") "font/otf"
    :else "application/octet-stream"))

(defn- decode-url-component [value]
  (URLDecoder/decode (or value "") StandardCharsets/UTF_8))

(defn- merge-param [m key value]
  (let [existing (get m key)]
    (cond
      (nil? existing) (assoc m key value)
      (vector? existing) (assoc m key (conj existing value))
      :else (assoc m key [existing value]))))

(defn parse-urlencoded [value]
  (if (str/blank? value)
    {}
    (reduce
     (fn [acc pair]
       (let [[raw-k raw-v] (str/split pair #"=" 2)
             key (keyword (decode-url-component raw-k))
             val (decode-url-component raw-v)]
         (merge-param acc key val)))
     {}
     (str/split value #"&"))))

(defn- body-string [request]
  (when-let [body (:body request)]
    (cond
      (string? body) body
      (bytes? body) (String. ^bytes body StandardCharsets/UTF_8)
      :else (slurp body))))

(defn read-json-body
  "Return the parsed JSON body for `request`. Prefers `:body-params` (set by
   muuntaja's format-request-middleware) and falls back to slurping/parsing the
   raw body when middleware isn't in front."
  [request]
  (or (:body-params request)
      (let [raw (body-string request)]
        (if (or (nil? raw) (str/blank? raw))
          {}
          (try
            (json/parse-string raw true)
            (catch JsonProcessingException _
              (throw (errors/api-error 400
                                       "bad_json"
                                       "Malformed JSON body"))))))))

(defn read-form-body
  "Return the parsed form body for `request`. Prefers `:form-params` (set by
   reitit's parameters-middleware, which wraps ring's wrap-params) and falls
   back to slurping/parsing the raw body when middleware isn't in front. Always
   returns a keyword-keyed map."
  [request]
  (if-let [form-params (or (not-empty (:multipart-params request))
                           (:form-params request))]
    (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} form-params)
    (parse-urlencoded (body-string request))))

(defn header
  "Look up a request header (case-insensitive on standard ring lower-cased keys)."
  [request name]
  (let [headers (:headers request)
        lower (str/lower-case name)]
    (or (get headers lower)
        (get headers name))))

(defn body-value [body & ks]
  (some #(get body %) ks))

(defn bearer-token [value]
  (when value
    (second (re-matches #"(?i)^Bearer\s+(.+)$" value))))
