(ns agent.federation.http-test
  (:require
   [agent.federation.http :as federation-http]
   [agent.persistence.sqlite :as sqlite]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-federation-" ".db")))

(defn temp-store []
  (sqlite/create-store {:path (temp-db-path)
                        :evict-on-close? true}))

(deftest signed-request-verifies-and-rejects-bad-signatures
  (let [store (temp-store)
        keys (federation-http/generate-ed25519-keypair)
        request {:peer_id "peer-a"
                 :to_agent_ref "agent-1"
                 :envelope {:id "msg-1" :content "hello"}}
        signed (federation-http/sign-request request {:key-id "k1"
                                                      :private-key (:private-key keys)
                                                      :nonce "n1"})
        tampered (assoc-in signed [:envelope :content] "bye")]
    (sqlite/upsert-federation-peer-key! store {:peer-id "peer-a"
                                               :key-id "k1"
                                               :public-key (:public-key keys)})
    (is (true? (federation-http/verify-request! {:store store} signed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Federation nonce replay"
                          (federation-http/verify-request! {:store store} signed)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Federation signature invalid"
                          (federation-http/verify-request! {:store store} tampered)))
    (sqlite/close-store! store)
    (io/delete-file (:path store) true)))

(deftest forwarder-retries-retryable-statuses
  (let [store (temp-store)
        keys (federation-http/generate-ed25519-keypair)
        calls (atom [])
        forwarder (federation-http/create-forwarder
                   {:store store
                    :key-id "k1"
                    :private-key (:private-key keys)
                    :retry-policy {:max-attempts 2
                                   :base-delay-ms 1
                                   :max-delay-ms 1}})]
    (with-redefs [http/post (fn [_ request]
                              (swap! calls conj (json/parse-string (:body request) true))
                              (if (= 1 (count @calls))
                                {:status 500 :body "{\"message\":\"try again\"}"}
                                {:status 202 :body "{\"ok\":true}"}))]
      (let [result (forwarder {:peer-id "peer-a"
                               :peer {:base-url "https://peer.example"}
                               :remote-agent-id "agent-1"
                               :envelope {:id "msg-1" :content "hello"}})
            outbox (sqlite/get-federation-outbox store (:outbox-id result))]
        (is (:ok? result))
        (is (= 2 (count @calls)))
        (is (= "acked" (:state outbox)))
        (is (= 2 (:attempt-count outbox)))
        (is (= "k1" (get-in (first @calls) [:auth :key_id])))))
    (sqlite/close-store! store)
    (io/delete-file (:path store) true)))

(deftest forwarder-does-not-retry-nonretryable-statuses
  (let [store (temp-store)
        calls (atom 0)
        forwarder (federation-http/create-forwarder
                   {:store store
                    :retry-policy {:max-attempts 3
                                   :base-delay-ms 1
                                   :max-delay-ms 1}})]
    (with-redefs [http/post (fn [_ _]
                              (swap! calls inc)
                              {:status 400 :body "{\"message\":\"bad\"}"})]
      (let [result (forwarder {:peer-id "peer-a"
                               :peer {:base-url "https://peer.example"}
                               :remote-agent-id "agent-1"
                               :envelope {:id "msg-1"}})
            outbox (sqlite/get-federation-outbox store (:outbox-id result))]
        (is (false? (:ok? result)))
        (is (= 1 @calls))
        (is (= "dead-letter" (:state outbox)))
        (is (= 1 (:attempt-count outbox)))))
    (sqlite/close-store! store)
    (io/delete-file (:path store) true)))

(deftest forwarder-opens-peer-circuit
  (let [store (temp-store)
        calls (atom 0)
        forwarder (federation-http/create-forwarder
                   {:store store
                    :retry-policy {:max-attempts 1}
                    :peer-policy {:failure-threshold 1
                                  :circuit-open-ms 60000}})]
    (with-redefs [http/post (fn [_ _]
                              (swap! calls inc)
                              {:status 500 :body "{\"message\":\"down\"}"})]
      (let [first-result (forwarder {:peer-id "peer-a"
                                     :peer {:base-url "https://peer.example"}
                                     :remote-agent-id "agent-1"
                                     :envelope {:id "msg-1"}})
            second-result (forwarder {:peer-id "peer-a"
                                      :peer {:base-url "https://peer.example"}
                                      :remote-agent-id "agent-1"
                                      :envelope {:id "msg-2"}})]
        (is (false? (:ok? first-result)))
        (is (false? (:ok? second-result)))
        (is (= "circuit-open" (get-in second-result [:body :message])))
        (is (= 1 @calls))))
    (sqlite/close-store! store)
    (io/delete-file (:path store) true)))
