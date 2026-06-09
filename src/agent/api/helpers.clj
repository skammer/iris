(ns agent.api.helpers
  "Ring-request parsing helpers. All input fns take a ring request map; output
  helpers (json/html/bytes responses) live in agent.api.responses."
  (:require
   [agent.api.errors :as errors]
   [agent.sessions.service :as session-service]
   [cheshire.core :as json]
   [clojure.string :as str]
   [ring.util.codec :as codec])
  (:import
   (com.fasterxml.jackson.core JsonProcessingException)
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
  (let [form-params (or (not-empty (:multipart-params request))
                        (:form-params request)
                        (let [decoded (codec/form-decode (or (body-string request) ""))]
                          (when (map? decoded) decoded)))]
    (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} form-params)))

(defn header
  "Look up a request header (ring stores header names lower-cased)."
  [request name]
  (get (:headers request) (str/lower-case name)))

(defn ensure-session-exists! [system session-id]
  (when (and session-id (not (session-service/session-exists? system session-id)))
    (throw (errors/api-error 404 "session_not_found" "Session not found"))))

(defn bearer-token [value]
  (when value
    (second (re-matches #"(?i)^Bearer\s+(.+)$" value))))
