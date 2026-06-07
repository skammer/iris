(ns agent.federation.http
  "Compatibility facade for split federation namespaces."
  (:require
   [agent.federation.auth :as auth]
   [agent.federation.crypto :as crypto]
   [agent.federation.forwarder :as forwarder]))

(def generate-ed25519-keypair crypto/generate-ed25519-keypair)
(def sign-request crypto/sign-request)
(def verify-request! auth/verify-request!)
(def create-forwarder forwarder/create-forwarder)
