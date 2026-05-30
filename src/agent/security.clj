(ns agent.security
  "Small security primitives shared across API and control-plane code."
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
