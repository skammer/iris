(ns agent.llm.providers.responses-test
  (:require
   [agent.llm.providers.openai-compatible.test-support :refer [byte-stream]]
   [agent.llm.core :as llm-core]
   [agent.llm.providers.openai-compatible :as provider]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.test :refer [deftest is]]))

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
