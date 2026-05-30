(ns agent.llm.providers.ollama-test
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.providers.ollama :as provider]
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

(deftest ollama-stream-closes-error-response-body-test
  (let [closed? (atom false)]
    (with-redefs [http/post (fn [_ _]
                              {:status 500
                               :headers {"Content-Type" "application/x-ndjson"}
                               :body (closing-byte-stream "oops" closed?)})]
      (let [llm (provider/create-ollama-provider {})
            ch (llm-core/stream llm [{:role "user" :content "hi"}] {})
            value (async/<!! ch)]
        (is (= :error (:type value)))
        (is (true? @closed?))))))

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

(deftest ollama-invoke-streams-when-content-delta-callback-present-test
  (let [body* (atom nil)
        as* (atom nil)
        deltas (atom [])]
    (with-redefs [http/post (fn [_ request]
                              (reset! body* (json/parse-string (:body request) true))
                              (reset! as* (:as request))
                              {:status 200
                               :headers {"Content-Type" "application/x-ndjson"}
                               :body (byte-stream
                                      (str "{\"message\":{\"content\":\"hello\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\" world\"},\"done\":true,\"prompt_eval_count\":3,\"eval_count\":2}\n"))})]
      (let [llm (provider/create-ollama-provider {})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "hi"}]
                       :on-content-delta #(swap! deltas conj %)})]
        (is (= :stream @as*))
        (is (true? (:stream @body*)))
        (is (= ["hello" " world"] @deltas))
        (is (= "hello world" (:content response)))
        (is (= 5 (get-in response [:usage :tokens])))))))

(deftest ollama-invoke-suppresses-streamed-tool-call-tags-test
  (let [deltas (atom [])]
    (with-redefs [http/post (fn [_ _]
                              {:status 200
                               :headers {"Content-Type" "application/x-ndjson"}
                               :body (byte-stream
                                      (str "{\"message\":{\"content\":\"<tool_call>\\n<function=fs>\\n\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\"<parameter=action>\\nwrite\\n</parameter>\\n\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\"<parameter=path>\\n/tmp/test_document.txt\\n</parameter>\\n\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\"<parameter=content>\\nПривет! Это тестовый документ.\\nСоздан автоматически.\\n</parameter>\\n\"},\"done\":false}\n"
                                           "{\"message\":{\"content\":\"</function>\\n</tool_call>\",\"tool_calls\":null},\"done\":true}\n"))})]
      (let [llm (provider/create-ollama-provider {})
            response (llm-core/invoke
                      llm
                      {:messages [{:role "user" :content "write file"}]
                       :tools [{:type "function"
                                :function {:name "fs"
                                           :description "Filesystem"
                                           :parameters {:type "object"}}}]
                       :on-content-delta #(swap! deltas conj %)})
            tc (first (:tool-calls response))]
        (is (empty? @deltas))
        (is (= "" (:content response)))
        (is (= "fs" (:name tc)))
        (is (= {:action "write"
                :path "/tmp/test_document.txt"
                :content "Привет! Это тестовый документ.\nСоздан автоматически."}
               (:arguments tc)))))))
