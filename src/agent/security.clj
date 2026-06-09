(ns agent.security
  "Small security/integrity primitives shared across API and control-plane code."
  (:require
   [cheshire.core :as json])
  (:import
   (java.security MessageDigest)))

(defn constant-time=
  [left right]
  (let [left* (some-> left str)
        right* (some-> right str)]
    (and (some? left*)
         (some? right*)
         (MessageDigest/isEqual (.getBytes left* "UTF-8")
                                (.getBytes right* "UTF-8")))))

(defn sha256-hex
  "Hex SHA-256 digest of the UTF-8 bytes of (str value)."
  [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- canonical-value [value]
  (cond
    (map? value) (into (sorted-map)
                       (keep (fn [[k v]]
                               (when (some? v)
                                 [(if (keyword? k) (name k) (str k))
                                  (canonical-value v)])))
                       value)
    (set? value) (vec (sort-by pr-str (map canonical-value value)))
    (sequential? value) (mapv canonical-value value)
    (keyword? value) (name value)
    :else value))

(defn canonical-json
  "Deterministic JSON for fingerprinting logically-equal values: map keys
   stringified and sorted at every level, keywords rendered as names, nil
   map values dropped. Not a wire format — do not parse this back."
  [value]
  (json/generate-string (canonical-value value)))
