(ns agent.llm.providers.ollama-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as provider]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [clojure.test :refer :all]))

(defn byte-stream [text]
  (java.io.ByteArrayInputStream. (.getBytes text "UTF-8")))

(deftest ollama-complete-test
  (with-redefs [http/post (fn [_ _]
                            {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body {:message {:content "ollama-ok"}
                                    :done true}})]
    (let [llm (provider/create-ollama-provider {})
          result (llm-core/complete llm [{:role "user" :content "hi"}] {})]
      (is (= "ollama-ok" result)))))

(deftest ollama-stream-and-embed-test
  (with-redefs [http/post (fn [url _]
                            (cond
                              (= "http://localhost:11434/api/chat" url)
                              {:status 200
                               :headers {"Content-Type" "application/x-ndjson"}
                               :body (byte-stream
                                      (str "{\"message\":{\"content\":\"hello\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\" world\"},\"done\":true}\n"))}

                              (= "http://localhost:11434/api/embed" url)
                              {:status 200
                               :headers {"Content-Type" "application/json"}
                               :body {:embeddings [[0.1 0.2 0.3]]}}))]
    (let [llm (provider/create-ollama-provider {})
          ch (llm-core/stream llm [{:role "user" :content "hi"}] {})
          chunks (loop [acc []]
                   (if-let [value (async/<!! ch)]
                     (recur (conj acc value))
                     acc))
          embedding (llm-core/embed llm "hi" {})]
      (is (= ["hello" " world"] chunks))
      (is (= [0.1 0.2 0.3] embedding)))))

(deftest ollama-structured-output-invoke-streams-by-default-test
  (let [body* (atom nil)
        as* (atom nil)]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              (reset! as* (:as request))
                              {:status 200
                               :headers {"Content-Type" "application/x-ndjson"}
                               :body (byte-stream
                                      (str "{\"message\":{\"content\":\"{\\\"ok\\\":\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\"true}\"},\"done\":true,\"prompt_eval_count\":10,\"eval_count\":2}\n"))})]
      (let [llm (provider/create-ollama-provider {})
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
        (is (= "{\"ok\":true}" (:content response)))
        (is (= 12 (get-in response [:usage :tokens])))))))
