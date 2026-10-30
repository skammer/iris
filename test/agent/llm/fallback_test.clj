(ns agent.llm.fallback-test
  (:require [clojure.test :refer [deftest is]]
            [agent.llm.core :as llm]
            [agent.config.validation :as validation]
            [agent.llm.fallback :as fallback]
            [agent.llm.service :as service]
            [agent.llm.providers.common :as common]
            [clj-http.client :as http]))

(defn client [f]
  (reify llm/ILLMProviderInvoke
    (invoke [_ request] (f request))
    (generate [this messages opts] (llm/invoke this (assoc opts :messages messages)))))

(deftest transport-retries
  (doseq [error [(java.net.ConnectException. "refused")
                 (ex-info "wrapped" {} (java.net.SocketTimeoutException. "timeout"))
                 (ex-info "gateway" {:status 502})]]
    (let [calls (atom 0)]
      (is (= :ok (llm/retry-with-backoff
                  #(if (< (swap! calls inc) 3) (throw error) :ok)
                  :initial-delay 0 :max-delay 0)))
      (is (= 3 @calls))))
  (doseq [error [(ex-info "bad request" {:status 400})
                 (ex-info "unauthorized" {:status 401})
                 (InterruptedException. "cancel")
                 (javax.net.ssl.SSLException. "certificate")]]
    (let [calls (atom 0)]
      (is (thrown? Exception (llm/retry-with-backoff
                              #(do (swap! calls inc) (throw error))
                              :initial-delay 0)))
      (is (= 1 @calls))))
  (let [calls (atom 0)]
    (is (thrown? java.net.ConnectException
                 (llm/retry-with-backoff
                  #(do (swap! calls inc) (throw (java.net.ConnectException.)))
                  :max-retries 2 :initial-delay 0)))
    (is (= 3 @calls))))

(deftest ordered-fallback-preserves-history
  (let [requests (atom [])
        history [{:role "tool" :tool_call_id "already-done" :content "SUCCESS"}]
        failing (client (fn [r] (swap! requests conj r)
                          (throw (java.net.ConnectException. "timeout"))))
        working (client (fn [r] (swap! requests conj r) {:content "ok"}))
        chain (fallback/->FallbackProvider
               [{:provider :a :model "a1" :client failing}
                {:provider :b :model "b1" :client failing}
                {:provider :c :model "c1" :client working}])
        result (llm/invoke chain {:messages history :model "a2"
                                  :opts {:model "a2" :api :responses}
                                  :api :responses :extra-body {:vendor true}})]
    (is (= [:a :b :c] (mapv :provider (:llm-attempts result))))
    (is (= :c (:provider result)))
    (is (= "c1" (:model result)))
    (is (= ["a2" "b1" "c1"] (mapv :model @requests)))
    (is (every? #(= history (:messages %)) @requests))
    (is (nil? (:api (last @requests))))
    (is (= "c1" (get-in (last @requests) [:opts :model])))))

(deftest never-fallback-after-emission-or-permanent-error
  (doseq [kind [:content :thinking :unauthorized :cancel]]
    (let [fallback-calls (atom 0)
          seen (atom [])
          first-client (client
                        (fn [r]
                          (case kind
                            :content ((:on-content-delta r) "partial")
                            :thinking ((:on-thinking-delta r) "reasoning")
                            nil)
                          (throw (case kind
                                   :unauthorized (ex-info "auth" {:status 401})
                                   :cancel (InterruptedException. "cancel")
                                   (java.net.SocketException. "reset")))))
          chain (fallback/->FallbackProvider
                 [{:provider :a :model "a" :client first-client}
                  {:provider :b :model "b"
                   :client (client (fn [_] (swap! fallback-calls inc) {:content "bad"}))}])]
      (is (thrown? Exception
                   (llm/invoke chain {:opts {:on-content-delta #(swap! seen conj %)
                                            :on-thinking-delta #(swap! seen conj %)}})))
      (is (zero? @fallback-calls))
      (when (#{:content :thinking} kind) (is (= 1 (count @seen)))))))

(deftest factory-validation
  (let [cfg {:active-provider :a
             :providers {:a {:type :openai-compatible :model "a"}
                         :b {:type :openai-compatible :model "b"}}}]
    (is (instance? agent.llm.fallback.FallbackProvider
                   (service/create-llm-provider
                    (assoc cfg :fallbacks [{:provider :b :model "b"}]))))
    (doseq [chain [nil {} [:b] [{:provider :missing :model "b"}]
                  [{:provider :b}]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (service/create-llm-provider (assoc cfg :fallbacks chain)))))))

(deftest bounded-http-transport
  (let [requests (atom [])]
    (with-redefs [http/post (fn [_ opts]
                             (swap! requests conj opts)
                             (if (= 1 (count @requests))
                               (throw (java.net.ConnectException.))
                               {:status 200 :body {}}))]
      (is (= 200 (:status (common/post-json
                          "http://fixture" {:max-retries 1 :initial-delay 0
                                            :connect-timeout-ms 25 :timeout-ms 80}
                          #(common/http-error "http" %)))))
      (is (= 2 (count @requests)))
      (is (= 25 (:connection-timeout (first @requests))))
      (is (= 80 (:socket-timeout (first @requests))))
      (is (false? ((:retry-handler (first @requests)) nil 1 nil)))))
  (is (= 10000 (:connection-timeout (common/http-request-options {})))))


(deftest config-rejects-broken-chain
  (let [cfg {:llm {:active-provider :a
                   :providers {:a {:type :ollama :model "a" :base-url "http://local"}}
                   :fallbacks [{:provider :missing :model "missing"}]}}]
    (is (some #(= [:llm :providers :missing] (:path %))
              (validation/config-validation-errors cfg)))))
