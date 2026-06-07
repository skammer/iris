(ns agent.api.handlers.federation
  (:require
   [agent.api.errors :as errors]
   [agent.api.helpers :as h]
   [agent.api.responses :as responses]
   [agent.api.serializers :as ser]
   [agent.federation.http :as federation-http]
   [agent.orchestrator :as orchestrator]
   [agent.persistence.sqlite :as sqlite]))

(defn list-peers [system _request]
  (responses/json-response 200
                           {:data (mapv ser/federated-peer->response
                                        (orchestrator/list-federated-peers (:orchestrator system)))}))

(defn create-peer [system request]
  (let [body (h/read-json-body request)
        id (:id body)
        name (:name body)
        base-url (:base_url body)
        logical-address-prefix (:logical_address_prefix body)
        capabilities (or (:capabilities body) [])
        status (or (:status body) "online")
        keys (mapv (fn [key*]
                     {:key-id (:key_id key*)
                      :public-key (:public_key key*)
                      :status (or (:status key*) "active")
                      :valid-from (:valid_from key*)
                      :valid-until (:valid_until key*)})
                   (or (:keys body) []))
        peer (orchestrator/register-federated-peer!
              (:orchestrator system)
              {:id id
               :name name
               :base-url base-url
               :logical-address-prefix logical-address-prefix
               :capabilities capabilities
               :status status
               :keys keys})]
    (when (:store system)
      (doseq [key* keys]
        (sqlite/upsert-federation-peer-key!
         (:store system)
         (assoc key* :peer-id (:id peer)))))
    (responses/json-response 201 {:data (ser/federated-peer->response peer)})))

(defn inbox [system request]
  (let [body (h/read-json-body request)
        peer-id (:peer_id body)
        to-agent-ref (:to_agent_ref body)
        envelope (:envelope body)]
    (try
      (federation-http/verify-request!
       {:store (:store system)
        :peer (orchestrator/get-federated-peer (:orchestrator system) peer-id)}
       body)
      (responses/json-response 202
                               {:data (ser/interop->response
                                       (orchestrator/receive-federated-message! (:orchestrator system)
                                                                                peer-id
                                                                                to-agent-ref
                                                                                envelope))})
      (catch Exception e
        (case (:type (ex-data e))
          :peer-not-found (throw (errors/api-error 404 "peer_not_found" "Federated peer not found"))
          :agent-not-found (throw (errors/api-error 404 "agent_not_found" "Agent not found"))
          :signature-missing (throw (errors/api-error 401 "signature_missing" "Federation signature missing"))
          :signature-invalid (throw (errors/api-error 401 "signature_invalid" "Federation signature invalid"))
          :timestamp-skew (throw (errors/api-error 401 "timestamp_skew" "Federation timestamp outside skew"))
          :nonce-replay (throw (errors/api-error 409 "nonce_replay" "Federation nonce replay"))
          :key-inactive (throw (errors/api-error 401 "key_inactive" "Federation signing key inactive"))
          :nonce-store-missing (throw (errors/api-error 500 "nonce_store_missing" "Federation nonce store missing"))
          (throw e))))))
