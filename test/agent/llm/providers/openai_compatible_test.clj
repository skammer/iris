(ns agent.llm.providers.openai-compatible-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.providers.openai-compatible :as provider]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]))

(defn byte-stream [text]
  (java.io.ByteArrayInputStream. (.getBytes text "UTF-8")))

(defn closing-byte-stream [text closed?]
  (proxy [java.io.ByteArrayInputStream] [(.getBytes text "UTF-8")]
    (close []
      (reset! closed? true)
      (proxy-super close))))

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

(deftest openai-compatible-stream-retries-retryable-status-test
  (let [calls (atom 0)
        failed-body-closed? (atom false)]
    (with-redefs [http/post (fn [_ _]
                              (if (= 1 (swap! calls inc))
                                {:status 503
                                 :headers {"Content-Type" "text/plain"}
                                 :body (closing-byte-stream "temporarily unavailable"
                                                            failed-body-closed?)}
                                {:status 200
                                 :headers {"Content-Type" "text/event-stream"}
                                 :body (byte-stream
                                        (str "data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"}}]}\n\n"
                                             "data: [DONE]\n\n"))}))]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key "oa-key"
                  :max-retries 1
                  :initial-delay 1})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :on-content-delta (fn [_])})]
        (is (= "recovered" (:content response)))
        (is (= 2 @calls))
        (is (true? @failed-body-closed?))))))

(deftest openai-compatible-responses-complete-test
  (let [url* (atom nil)
        body* (atom nil)]
    (with-redefs [http/post (fn [url request]
                              (reset! url* url)
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:id "resp_1"
                                      :status "completed"
                                      :output [{:type "message"
                                                :role "assistant"
                                                :content [{:type "output_text"
                                                           :text "responses-ok"}]}]
                                      :usage {:input_tokens 2
                                              :output_tokens 3
                                              :total_tokens 5
                                              :input_tokens_details {:cached_tokens 1}}}})]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key "oa-key"
                  :api :responses
                  :base-url "https://api.openai.com/v1"
                  :model "gpt-4.1"})]
        (is (= "responses-ok"
               (llm-core/complete llm [{:role "user" :content "hi"}] {})))
        (is (= "https://api.openai.com/v1/responses" @url*))
        (is (= "gpt-4.1" (:model @body*)))
        (is (= [{:role "user" :content "hi"}] (:input @body*)))
        (is (nil? (:messages @body*)))
        (is (= 1024 (:max_output_tokens @body*)))))))

(deftest openai-compatible-responses-tools-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:id "resp_1"
                                      :status "completed"
                                      :output [{:type "function_call"
                                                :id "fc_1"
                                                :call_id "call_1"
                                                :name "fs"
                                                :arguments "{\"action\":\"list\",\"path\":\".\"}"
                                                :status "completed"}]
                                      :usage {:input_tokens 4
                                              :output_tokens 1
                                              :total_tokens 5
                                              :input_tokens_details {:cached_tokens 2}}}})]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key "oa-key"
                  :api :responses})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "list"}]
                       :tools [{:type "function"
                                :function {:name "fs"
                                           :description "Filesystem"
                                           :parameters {:type "object"}}}]})
            tc (first (:tool-calls response))]
        (is (= [{:type "function"
                 :name "fs"
                 :description "Filesystem"
                 :parameters {:type "object"}}]
               (:tools @body*)))
        (is (= "call_1" (:id tc)))
        (is (= "fs" (:name tc)))
        (is (= {:action "list" :path "."} (:arguments tc)))
        (is (= 2 (get-in response [:usage :cached-tokens])))))))

(deftest openai-compatible-responses-incomplete-preserves-length-stop-reason-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:id "resp_1"
                                    :status "incomplete"
                                    :output [{:type "message"
                                              :role "assistant"
                                              :content [{:type "output_text"
                                                         :text "partial"}]}]
                                    :usage {:input_tokens 2
                                            :output_tokens 1024
                                            :total_tokens 1026}}})]
    (let [llm (provider/create-openai-compatible-provider
               {:api-key "oa-key"
                :api :responses})
          response (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]})]
      (is (= "partial" (:content response)))
      (is (= "length" (:stop-reason response))))))

(deftest openrouter-stream-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n"
                                         "data: [DONE]\n"))})]
    (let [llm (provider/create-openrouter-provider {:api-key "or-key"})
          ch (llm-core/stream llm [{:role "user" :content "hi"}] {})
          chunks (loop [acc []]
                   (if-let [value (async/<!! ch)]
                     (recur (conj acc value))
                     acc))]
      (is (= ["hello" " world"] chunks)))))

(deftest invoke-streams-content-via-on-content-delta-callback-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
            chunks (atom [])
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :on-content-delta #(swap! chunks conj %)})]
        (is (true? (:stream @body*)))
        (is (= ["Hello" " world"] @chunks))
        (is (= "Hello world" (:content response)))
        (is (= "stop" (:stop-reason response)))
        (is (empty? (:tool-calls response)))))))

(deftest invoke-stream-preserves-length-finish-reason-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "hi"}]
                     :on-content-delta (fn [_])})]
      (is (= "partial" (:content response)))
      (is (= "length" (:stop-reason response))))))

(deftest invoke-streams-reasoning-via-on-thinking-delta-callback-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hard\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
            thinking (atom [])
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :on-thinking-delta #(swap! thinking conj %)})]
        (is (true? (:stream @body*)))
        (is (= ["think " "hard"] @thinking))
        (is (= {:type :thinking :text "think hard"}
               (first (:content-blocks response))))
        (is (= "OK" (:content response)))))))

(deftest invoke-honors-configured-stream-flag-test
  (let [body* (atom nil)
        as* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              (reset! as* (:as request))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":5}}}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"
                                                             :stream? true})
            response (llm-core/invoke llm {:messages [{:role "user" :content "hi"}]})]
        (is (= :stream @as*))
        (is (true? (:stream @body*)))
        (is (= {:include_usage true} (:stream_options @body*)))
        (is (= "OK" (:content response)))
        (is (= 5 (get-in response [:usage :cached-tokens])))))))

(deftest invoke-can-disable-configured-stream-flag-test
  (let [body* (atom nil)
        as* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              (reset! as* (:as request))
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:choices [{:message {:content "ok"}}]}})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"
                                                             :stream? true})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :stream? false})]
        (is (= :json @as*))
        (is (false? (:stream @body*)))
        (is (= "ok" (:content response)))))))

(deftest invoke-streams-responses-api-content-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n"
                                           "data: {\"type\":\"response.output_text.delta\",\"delta\":\" world\"}\n\n"
                                           "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello world\"}]}],\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12,\"input_tokens_details\":{\"cached_tokens\":7}}}}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider
                 {:api-key "oa-key"
                  :api :responses})
            chunks (atom [])
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :on-content-delta #(swap! chunks conj %)})]
        (is (true? (:stream @body*)))
        (is (= ["Hello" " world"] @chunks))
        (is (= "Hello world" (:content response)))
        (is (= 7 (get-in response [:usage :cached-tokens])))))))

(deftest invoke-streams-normal-content-when-tools-present-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
            chunks (atom [])
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :tools [{:type "function"
                                :function {:name "fs"
                                           :description "Filesystem"
                                           :parameters {:type "object"}}}]
                       :on-content-delta #(swap! chunks conj %)})]
        (is (true? (:stream @body*)))
        (is (= ["Hello" " world"] @chunks))
        (is (= "Hello world" (:content response)))
        (is (empty? (:tool-calls response)))))))

(deftest invoke-errors-on-reasoning-only-length-stream-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          err (try
                (llm-core/invoke
                 llm
                 {:messages [{:role "user" :content "hi"}]
                  :on-content-delta (fn [_])})
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
      (is (some? err))
      (is (re-find #"ended before final content" (.getMessage err))))))

(deftest complete-errors-on-reasoning-only-length-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:role "assistant"
                                                         :reasoning_content "thinking"
                                                         :content nil}
                                               :finish_reason "length"}]}})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          err (try
                (llm-core/complete llm [{:role "user" :content "hi"}] {})
                nil
                (catch clojure.lang.ExceptionInfo e
                  e))]
      (is (some? err))
      (is (re-find #"ended before final content" (.getMessage err))))))

(deftest invoke-strips-leaked-tool-call-tags-when-native-tool-call-present-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:role "assistant"
                                                         :content (str "<tool_call>\n"
                                                                       "<function=memory>\n"
                                                                       "<parameter=query>\n"
                                                                       "Модель: Kimi\n"
                                                                       "</parameter>\n"
                                                                       "<parameter=action>\n"
                                                                       "search\n"
                                                                       "</parameter>\n"
                                                                       "</function>\n"
                                                                       "</tool_call>")
                                                         :tool_calls [{:id "call_memory_1"
                                                                       :type "function"
                                                                       :function {:name "memory"
                                                                                  :arguments "{\"query\":\"Модель: Kimi\",\"action\":\"search\"}"}}]}
                                               :finish_reason "tool_calls"}]}})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "find Kimi"}]
                     :tools [{:type "function"
                              :function {:name "memory"
                                         :description "Memory"
                                         :parameters {:type "object"}}}]})
          tc (first (:tool-calls response))]
      (is (= "" (:content response)))
      (is (= "memory" (:name tc)))
      (is (= {:query "Модель: Kimi" :action "search"} (:arguments tc))))))

(deftest stream-reports-reasoning-only-length-error-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          ch (llm-core/stream llm [{:role "user" :content "hi"}] {})
          value (async/<!! ch)]
      (is (= :error (:type value)))
      (is (re-find #"ended before final content" (:error value))))))

(deftest stream-closes-error-response-body-test
  (let [closed? (atom false)]
    (with-redefs [http/post (fn [_ _]
                              {:status 429
                               :headers {"Content-Type" "text/event-stream"}
                               :body (closing-byte-stream "rate limited" closed?)})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
            ch (llm-core/stream llm [{:role "user" :content "hi"}] {})
            value (async/<!! ch)]
        (is (= :error (:type value)))
        (is (true? @closed?))))))

(deftest invoke-merges-streamed-tool-call-arg-fragments-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"fs\",\"arguments\":\"\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"act\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"ion\\\":\\\"list\\\",\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"\\\"path\\\":\\\".\\\"}\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          chunks (atom [])
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "list files"}]
                     :on-content-delta #(swap! chunks conj %)})
          tc (first (:tool-calls response))]
      (is (empty? @chunks))
      (is (= 1 (count (:tool-calls response))))
      (is (= "call_1" (:id tc)))
      (is (= "fs" (:name tc)))
      (is (= {:action "list" :path "."} (:arguments tc))))))

(deftest invoke-suppresses-streamed-dsml-when-tools-present-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"content\":\"<｜DSML｜tool_calls>\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<｜DSML｜invoke name=\\\"shell\\\">\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<｜DSML｜parameter name=\\\"command\\\" string=\\\"true\\\">df -h</｜DSML｜parameter>\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"</｜DSML｜invoke></｜DSML｜tool_calls>\"}}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          chunks (atom [])
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "run df"}]
                     :tools [{:type "function"
                              :function {:name "shell"
                                         :description "Local shell"
                                         :parameters {:type "object"}}}]
                     :on-content-delta #(swap! chunks conj %)})
          tc (first (:tool-calls response))]
      (is (empty? @chunks))
      (is (= "" (:content response)))
      (is (= "shell" (:name tc)))
      (is (= {:command "df -h"} (:arguments tc))))))

(deftest invoke-suppresses-streamed-doubled-dsml-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"content\":\"<｜｜DSML｜｜tool_calls>\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<｜｜DSML｜｜invoke name=\\\"shell\\\">\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<｜｜DSML｜｜parameter name=\\\"command\\\" string=\\\"true\\\">df -h</｜｜DSML｜｜parameter>\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"</｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>\"}}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          chunks (atom [])
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "run df"}]
                     :tools [{:type "function"
                              :function {:name "shell"
                                         :description "Local shell"
                                         :parameters {:type "object"}}}]
                     :on-content-delta #(swap! chunks conj %)})
          tc (first (:tool-calls response))]
      (is (empty? @chunks))
      (is (= "" (:content response)))
      (is (= "shell" (:name tc)))
      (is (= {:command "df -h"} (:arguments tc))))))

(deftest invoke-recovers-streamed-tool-call-tags-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"content\":\"<tool_call>\\n<function=memory>\\n\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<parameter=query>\\nпример\\n</parameter>\\n\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<parameter=action>\\nsearch\\n</parameter>\\n\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"</function>\\n</tool_call>\"}}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          chunks (atom [])
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "найди пример"}]
                     :tools [{:type "function"
                              :function {:name "memory"
                                         :description "Memory"
                                         :parameters {:type "object"}}}]
                     :on-content-delta #(swap! chunks conj %)})
          tc (first (:tool-calls response))]
      (is (empty? @chunks))
      (is (= "" (:content response)))
      (is (= "memory" (:name tc)))
      (is (= {:query "пример" :action "search"} (:arguments tc))))))

(deftest structured-output-invoke-streams-by-default-test
  (let [body* (atom nil)
        as* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              (reset! as* (:as request))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{\"content\":\"true}\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":8}}}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :structured-output {:name "answer"
                                           :schema {:type "object"
                                                    :properties {:ok {:type "boolean"}}
                                                    :required ["ok"]
                                                    :additionalProperties false}}})]
        (is (= :stream @as*))
        (is (true? (:stream @body*)))
        (is (= {:include_usage true} (:stream_options @body*)))
        (is (= "json_schema" (get-in @body* [:response_format :type])))
        (is (= "{\"ok\":true}" (:content response)))
        (is (= 8 (get-in response [:usage :cached-tokens])))))))

(deftest deepseek-structured-output-uses-json-object-mode-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":\"}}]}\n\n"
                                           "data: {\"choices\":[{\"delta\":{\"content\":\"true}\"},\"finish_reason\":\"stop\"}]}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-deepseek-provider {:api-key "ds-key"
                                                    :model "deepseek-chat"})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "system" :content "Output JSON only."}
                                  {:role "user" :content "Return JSON."}]
                       :structured-output {:name "answer"
                                           :schema {:type "object"
                                                    :properties {:ok {:type "boolean"}}
                                                    :required ["ok"]
                                                    :additionalProperties false}}})]
        (is (= {:type "json_object"} (:response_format @body*)))
        (is (true? (:stream @body*)))
        (is (= "{\"ok\":true}" (:content response)))))))

(deftest deepseek-base-url-autodetect-uses-json-object-mode-test
  (let [body* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              {:status 200
                               :headers {"Content-Type" "text/event-stream"}
                               :body (byte-stream
                                      (str "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}\n\n"
                                           "data: [DONE]\n\n"))})]
      (let [llm (provider/create-openai-compatible-provider {:api-key "ds-key"
                                                             :base-url "https://api.deepseek.com/v1"
                                                             :model "deepseek-chat"})]
        (llm-core/invoke
         llm
         {:messages [{:role "system" :content "Output JSON only."}
                     {:role "user" :content "Return JSON."}]
          :structured-output {:name "answer"
                              :schema {:type "object"
                                       :properties {:ok {:type "boolean"}}
                                       :required ["ok"]
                                       :additionalProperties false}}})
        (is (= {:type "json_object"} (:response_format @body*)))))))

(deftest capabilities-use-model-metadata-test
  (let [llm (provider/create-openai-compatible-provider
             {:api-key "oa-key"
              :models {"plain" {}
                       "vision" {:supports-vision true
                                 :supports-audio true}}})]
    (is (false? (:supports-vision (llm-core/get-capabilities llm "plain"))))
    (is (false? (:supports-audio (llm-core/get-capabilities llm "plain"))))
    (is (true? (:supports-tools (llm-core/get-capabilities llm "plain"))))
    (is (true? (:supports-vision (llm-core/get-capabilities llm "vision"))))
    (is (true? (:supports-audio (llm-core/get-capabilities llm "vision"))))))
