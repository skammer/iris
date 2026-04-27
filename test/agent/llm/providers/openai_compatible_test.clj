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
