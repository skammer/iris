(ns agent.federation.crypto
  "Federation Ed25519 signing."
  (:require
   [agent.util :as util]
   [cheshire.core :as json])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security KeyFactory KeyPairGenerator Signature)
   (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)
   (java.util Base64 UUID)))

(defn- b64-encode [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn- b64-decode [value]
  (.decode (Base64/getDecoder) ^String value))

(defn generate-ed25519-keypair
  []
  (let [generator (KeyPairGenerator/getInstance "Ed25519")
        pair (.generateKeyPair generator)]
    {:public-key (b64-encode (.getEncoded (.getPublic pair)))
     :private-key (b64-encode (.getEncoded (.getPrivate pair)))}))

(defn- decode-private-key [encoded]
  (.generatePrivate (KeyFactory/getInstance "Ed25519")
                    (PKCS8EncodedKeySpec. (b64-decode encoded))))

(defn- decode-public-key [encoded]
  (.generatePublic (KeyFactory/getInstance "Ed25519")
                   (X509EncodedKeySpec. (b64-decode encoded))))

(defn- canonical-value [value]
  (cond
    (map? value)
    (into (sorted-map)
          (map (fn [[k v]]
                 [(if (keyword? k) (name k) (str k))
                  (canonical-value v)]))
          value)

    (sequential? value)
    (mapv canonical-value value)

    :else value))

(defn- canonical-json [value]
  (json/generate-string (canonical-value value)))

(defn- signing-bytes [request]
  (.getBytes (canonical-json request) StandardCharsets/UTF_8))

(defn sign-request
  [request {:keys [key-id private-key timestamp nonce]}]
  (let [auth {:scheme "ed25519"
              :key_id key-id
              :timestamp (or timestamp (util/now-str))
              :nonce (or nonce (str (UUID/randomUUID)))}
        unsigned (assoc request :auth auth)
        signer (doto (Signature/getInstance "Ed25519")
                 (.initSign (decode-private-key private-key))
                 (.update (signing-bytes unsigned)))]
    (assoc unsigned :auth (assoc auth :signature (b64-encode (.sign signer))))))

(defn verify-signature!
  [request public-key signature]
  (try
    (let [unsigned (update request :auth dissoc :signature)
          verifier (doto (Signature/getInstance "Ed25519")
                     (.initVerify (decode-public-key public-key))
                     (.update (signing-bytes unsigned)))]
      (when-not (.verify verifier (b64-decode signature))
        (throw (ex-info "Federation signature invalid"
                        {:type :signature-invalid}))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info "Federation signature invalid"
                      {:type :signature-invalid}
                      e)))))
