(ns agent.llm.providers.openai-compatible-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.providers.openai-compatible :as provider]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [clojure.test :refer :all]))

(defn byte-stream [text]
  (java.io.ByteArrayInputStream. (.getBytes text "UTF-8")))

(deftest openrouter-complete-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:content "openrouter-ok"}}]}})]
    (let [llm (provider/create-openrouter-provider
               {:api-key "or-key"
                :model "openai/gpt-4o-mini"
                :site-url "https://example.com"
                :app-name "iris-test"})]
      (is (= "openrouter-ok"
             (llm-core/complete llm [{:role "user" :content "hi"}] {}))))))

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
        (is (empty? (:tool-calls response)))))))

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
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"ended before final content"
           (llm-core/invoke
            llm
            {:messages [{:role "user" :content "hi"}]
             :on-content-delta (fn [_])}))))))

(deftest complete-errors-on-reasoning-only-length-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:choices [{:message {:role "assistant"
                                                         :reasoning_content "thinking"
                                                         :content nil}
                                               :finish_reason "length"}]}})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"ended before final content"
           (llm-core/complete llm [{:role "user" :content "hi"}] {}))))))

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

(deftest invoke-merges-streamed-tool-call-arg-fragments-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"fs\",\"arguments\":\"\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"act\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ion\\\":\\\"list\\\",\"}}]}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"path\\\":\\\".\\\"}\"}}]}}]}\n\n"
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

(deftest invoke-recovers-streamed-tool-call-tags-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "text/event-stream"}
                             :body (byte-stream
                                    (str "data: {\"choices\":[{\"delta\":{\"content\":\"<tool_call>\\n<function=memory>\\n\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<parameter=query>\\nМакс\\n</parameter>\\n\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"<parameter=action>\\nsearch\\n</parameter>\\n\"}}]}\n\n"
                                         "data: {\"choices\":[{\"delta\":{\"content\":\"</function>\\n</tool_call>\"}}]}\n\n"
                                         "data: [DONE]\n\n"))})]
    (let [llm (provider/create-openai-compatible-provider {:api-key "oa-key"})
          chunks (atom [])
          response (llm-core/invoke
                    llm
                    {:messages [{:role "user" :content "вспомни Макса"}]
                     :tools [{:type "function"
                              :function {:name "memory"
                                         :description "Memory"
                                         :parameters {:type "object"}}}]
                     :on-content-delta #(swap! chunks conj %)})
          tc (first (:tool-calls response))]
      (is (empty? @chunks))
      (is (= "" (:content response)))
      (is (= "memory" (:name tc)))
      (is (= {:query "Макс" :action "search"} (:arguments tc))))))

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

(deftest capabilities-use-model-metadata-test
  (let [llm (provider/create-openai-compatible-provider
             {:api-key "oa-key"
              :config {:models {"plain" {}
                                "vision" {:supports-vision true
                                          :supports-audio true}}}})]
    (is (false? (:supports-vision (llm-core/get-capabilities llm "plain"))))
    (is (false? (:supports-audio (llm-core/get-capabilities llm "plain"))))
    (is (true? (:supports-tools (llm-core/get-capabilities llm "plain"))))
    (is (true? (:supports-vision (llm-core/get-capabilities llm "vision"))))
    (is (true? (:supports-audio (llm-core/get-capabilities llm "vision"))))))
