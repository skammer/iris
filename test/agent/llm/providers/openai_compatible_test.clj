(ns agent.llm.providers.openai-compatible-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.providers.openai-compatible :as provider]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.test :refer [deftest is]]))

(deftest openrouter-complete-test
  (let [headers* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! headers* (:headers request))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "openrouter-ok"}}]}})]
      (let [llm (provider/create-openrouter-provider
                 {:api-key "or-key"
                  :model "openai/gpt-4o-mini"
                  :site-url "https://example.com"
                  :app-name "iris-test"
                  :extra-headers {"x-proxy-token" "proxy-token"}})]
        (is (= "openrouter-ok"
               (llm-core/complete llm [{:role "user" :content "hi"}] {})))
        (is (= "proxy-token" (get @headers* "x-proxy-token")))))))

(deftest openai-compatible-default-prompt-cache-test
  (let [bodies* (atom [])]
    (with-redefs [http/post (fn [_ request]
                              (swap! bodies* conj (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "ok"}}]}})]
      (let [openai (provider/create-openai-compatible-provider
                    {:api-key "oa-key"
                     :base-url "https://api.openai.com/v1"})
            openrouter-claude (provider/create-openrouter-provider
                               {:api-key "or-key"
                                :model "anthropic/claude-sonnet-4.5"})
            openrouter-openai (provider/create-openrouter-provider
                               {:api-key "or-key"
                                :model "openai/gpt-4o-mini"})]
        (llm-core/complete openai [{:role "user" :content "hi"}] {})
        (llm-core/complete openrouter-claude [{:role "user" :content "hi"}] {})
        (llm-core/complete openrouter-openai [{:role "user" :content "hi"}] {})
        (is (= "in_memory" (:prompt_cache_retention (nth @bodies* 0))))
        (is (= {:type "ephemeral"} (:cache_control (nth @bodies* 1))))
        (is (nil? (:cache_control (nth @bodies* 2))))))))

(deftest openai-compatible-provider-config-defaults-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "ok"}}]}})]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key "oa-key"
                  :temperature 0.1
                  :max-tokens 4096
                  :extra-body {:presence_penalty 0.2}})]
        (is (= "ok" (llm-core/complete llm [{:role "user" :content "hi"}] {})))
        (is (= 0.1 (:temperature @body*)))
        (is (= 4096 (:max_tokens @body*)))
        (is (= 0.2 (:presence_penalty @body*)))))))

(deftest openai-compatible-sends-user-from-session-id-test
  (let [bodies* (atom [])]
    (with-redefs [http/post (fn [_ request]
                              (swap! bodies* conj (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "ok"}}]}})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})]
        (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]
                              :session-id "session-1"})
        (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]
                              :session-id "session-1"
                              :user "explicit-user"})
        (is (= "session-1" (:user (first @bodies*))))
        (is (= "explicit-user" (:user (second @bodies*))))))))

(deftest openai-compatible-keeps-configured-user-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "ok"}}]}})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"
                                                             :user "sticky-user"})]
        (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]})
        (is (= "sticky-user" (:user @body*)))))))

(deftest openai-compatible-usage-accepts-provider-cache-variants-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:content "ok"}}]
                                    :usage {:prompt_tokens 20
                                            :completion_tokens 3
                                            :total_tokens 23
                                            :cache_read_input_tokens 11}}})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          response (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]})]
      (is (= 11 (get-in response [:usage :cached-tokens]))))))

(deftest openai-compatible-nonstream-preserves-reasoning-content-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:role "assistant"
                                                         :reasoning_content "think first"
                                                         :content "ok"}
                                               :finish_reason "stop"}]}})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          response (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]})]
      (is (= "ok" (:content response)))
      (is (= "stop" (:stop-reason response)))
      (is (= {:type :thinking :text "think first"}
             (first (:content-blocks response)))))))

(deftest openai-compatible-nonstream-preserves-length-finish-reason-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:role "assistant"
                                                         :content "partial"}
                                               :finish_reason "length"}]}})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          response (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]})]
      (is (= "partial" (:content response)))
      (is (= "length" (:stop-reason response))))))

(deftest openai-compatible-provider-resolves-api-key-per-call-test
  (let [headers* (atom [])
        token (atom "k1")]
    (with-redefs [http/post (fn [_ request]
                              (swap! headers* conj (:headers request))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "ok"}}]}})]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key-resolver (fn [_] @token)})]
        (llm-core/complete llm [{:role "user" :content "hi"}] {})
        (reset! token "k2")
        (llm-core/complete llm [{:role "user" :content "hi"}] {})
        (is (= ["Bearer k1" "Bearer k2"]
               (mapv #(get % "Authorization") @headers*)))))))

(deftest openai-compatible-uses-timeout-and-retry-config-test
  (let [calls (atom [])]
    (with-redefs [http/post (fn [_ request]
                              (swap! calls conj request)
                              (if (= 1 (count @calls))
                                {:status 429
                                 :headers {"Retry-After" "0"
                                           "Content-Type" "application/json"
                                           "Authorization" "Bearer secret"}
                                 :body "secret error body"}
                                {:status 200
                                 :headers {"Content-Type" "application/json"}
                                 :body {:choices [{:message {:content "ok"}}]}}))]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key "oa-key"
                  :timeout-ms 1234
                  :max-retries 1
                  :initial-delay 1})]
        (is (= "ok" (llm-core/complete llm [{:role "user" :content "hi"}] {})))
        (is (= 2 (count @calls)))
        (is (= [1234 1234]
               ((juxt :socket-timeout :connection-timeout) (first @calls))))
        (is (not (contains? (first @calls) :timeout-ms)))
        (is (not (contains? (first @calls) :max-retries)))))))
