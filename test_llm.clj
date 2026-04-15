(ns agent.test-llm
  (:require
   [agent.llm :as llm]
   [clojure.test :refer :all]))

;; Mock provider for testing without real API key
(defrecord MockProvider []
  llm/ILLMProvider
  (complete [_ messages _]
    (str "Mock response to: " (-> messages first :content)))
  
  (stream [_ messages _]
    (let [ch (async/chan)]
      (async/go
        (async/>! ch (str "Streaming mock response to: " (-> messages first :content)))
        (async/close! ch))
      ch))
  
  (embed [_ text _]
    (vec (take 10 (repeat 0.1)))))

(deftest test-llm-protocol
  (let [mock (->MockProvider)]
    (testing "Complete method"
      (is (= "Mock response to: Hello"
             (llm/complete mock [{:role "user" :content "Hello"}] {}))))
    
    (testing "Stream method returns channel"
      (let [ch (llm/stream mock [{:role "user" :content "Test"}] {})]
        (is (instance? clojure.core.async.impl.channels.ManyToManyChannel ch))
        (is (= "Streaming mock response to: Test" (async/<!! ch)))))))

(deftest test-openai-provider-creation
  (testing "Provider creation with env var"
    (try
      (let [provider (llm/create-openai-provider {})]
        (is (instance? agent.llm.OpenAIProvider provider)))
      (catch Exception e
        (is (re-find #"OpenAI API key required" (.getMessage e)))))))

(deftest test-simple-completion
  (let [mock (->MockProvider)]
    (is (= "Mock response to: Test prompt"
           (llm/simple-completion mock "Test prompt")))))

(run-tests 'agent.test-llm)