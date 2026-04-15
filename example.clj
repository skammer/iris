(ns agent.example
  (:require
   [agent.llm :as llm]
   [clojure.core.async :as async]))

;; Example 1: Create a mock provider for testing
(def mock-provider
  (reify llm/ILLMProvider
    (complete [_ messages _]
      (str "Mock response: You said '" (-> messages first :content) "'"))
    
    (stream [_ messages _]
      (let [ch (async/chan)]
        (async/go
          (doseq [word ["Thinking..." "Processing..." "Response ready."]]
            (async/>! ch word)
            (async/<!! (async/timeout 100)))
          (async/>! ch (str "Final: " (-> messages first :content)))
          (async/close! ch))
        ch))
    
    (embed [_ text _]
      (take 5 (repeat 0.5)))))

;; Example 2: Simple interaction
(defn simple-chat []
  (println "=== Simple Chat Example ===")
  (let [response (llm/complete mock-provider
                               [{:role "user" :content "Hello, agent!"}]
                               {:temperature 0.7})]
    (println "Response:" response)))

;; Example 3: Streaming
(defn streaming-chat []
  (println "\n=== Streaming Example ===")
  (let [ch (llm/stream mock-provider
                       [{:role "user" :content "Tell me about Clojure"}]
                       {})]
    (println "Streaming response:")
    (loop []
      (when-let [msg (async/<!! ch)]
        (println "  ->" msg)
        (recur)))))

;; Example 4: With retry logic
(defn chat-with-retry []
  (println "\n=== Chat with Retry ===")
  (try
    (let [response (llm/complete-with-retry
                    mock-provider
                    [{:role "user" :content "Important question"}]
                    {:temperature 0.5})]
      (println "Response with retry:" response))
    (catch Exception e
      (println "Error after retries:" (.getMessage e)))))

;; Run examples
(defn -main [& args]
  (println "Starting LLM integration examples...")
  (simple-chat)
  (streaming-chat)
  (chat-with-retry)
  (println "\nExamples completed."))

(comment
  ;; Run in REPL
  (simple-chat)
  (streaming-chat)
  (chat-with-retry)
  
  ;; Test with real OpenAI (requires API key)
  (when-let [api-key (System/getenv "OPENAI_API_KEY")]
    (let [provider (llm/create-openai-provider {:api-key api-key})]
      (println "Testing real OpenAI...")
      (println (llm/simple-completion provider "Say hello in Clojure")))))