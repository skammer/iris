(ns agent.llm.providers.openai-compatible-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.providers.openai-compatible :as provider]
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
                :app-name "clj-agent-test"})]
      (is (= "openrouter-ok"
             (llm-core/complete llm [{:role "user" :content "hi"}] {}))))))

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
