(ns agent.llm
  "Basic LLM integration for the agent system.
  Provides a simple interface to interact with LLM providers."
  (:require
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.net URLEncoder)))

(defprotocol ILLMProvider
  "Protocol for LLM providers."
  (complete [this messages opts]
    "Send messages to LLM and get completion.")
  (stream [this messages opts]
    "Stream completion from LLM.")
  (embed [this text opts]
    "Get embeddings for text."))

(defrecord OpenAIProvider [api-key base-url]
  ILLMProvider
  (complete [this messages opts]
    (let [url (str (or base-url "https://api.openai.com/v1") "/chat/completions")
          body {:model (or (:model opts) "gpt-3.5-turbo")
                :messages messages
                :temperature (or (:temperature opts) 0.7)
                :max_tokens (or (:max-tokens opts) 1000)}
          response (http/post url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string body)
                               :as :json})]
      (-> response :body :choices first :message :content)))

  (stream [this messages opts]
    (let [ch (async/chan)
          url (str (or base-url "https://api.openai.com/v1") "/chat/completions")
          body {:model (or (:model opts) "gpt-3.5-turbo")
                :messages messages
                :temperature (or (:temperature opts) 0.7)
                :max_tokens (or (:max-tokens opts) 1000)
                :stream true}]
      (async/go
        (try
          (let [response (http/post url
                                    {:headers {"Authorization" (str "Bearer " api-key)
                                               "Content-Type" "application/json"}
                                     :body (json/generate-string body)
                                     :as :stream})]
            ; Simplified streaming - in real implementation would parse SSE
            (async/>! ch (str "Streaming response for: " (count messages) " messages"))
            (async/close! ch))
          (catch Exception e
            (async/>! ch (str "Error: " (.getMessage e)))
            (async/close! ch))))
      ch))

  (embed [this text opts]
    (let [url (str (or base-url "https://api.openai.com/v1") "/embeddings")
          body {:model (or (:model opts) "text-embedding-ada-002")
                :input text}
          response (http/post url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string body)
                               :as :json})]
      (-> response :body :data first :embedding))))

(defn create-openai-provider
  "Create an OpenAI provider instance.
  Options:
  - :api-key (required)
  - :base-url (optional, for custom endpoints)"
  [opts]
  (let [api-key (or (:api-key opts)
                    (System/getenv "OPENAI_API_KEY")
                    (throw (ex-info "OpenAI API key required" {})))]
    (->OpenAIProvider api-key (:base-url opts))))

(defn complete-with-retry
  "Complete with retry logic."
  [provider messages opts & {:keys [retries delay-ms]
                             :or {retries 3 delay-ms 1000}}]
  (loop [attempt 1]
    (let [result (try
                   (complete provider messages opts)
                   (catch Exception e
                     (if (>= attempt retries)
                       (throw e)
                       e)))]
      (cond
        (not (instance? Exception result)) result
        :else (do
                (Thread/sleep (* attempt delay-ms))
                (recur (inc attempt)))))))

(defn simple-completion
  "Simple completion function for quick testing."
  [provider prompt]
  (complete provider [{:role "user" :content prompt}] {}))

(comment
  ;; Example usage
  (def openai (create-openai-provider {:api-key "sk-..."}))

  ;; Simple completion
  (simple-completion openai "Hello, how are you?")

  ;; With options
  (complete openai [{:role "user" :content "What is Clojure?"}]
            {:model "gpt-4" :temperature 0.5})

  ;; Streaming (returns a channel)
  (let [ch (stream openai [{:role "user" :content "Tell me a story"}] {})]
    (async/<!! ch))

  ;; Embeddings
  (embed openai "Clojure is a functional programming language" {})
  )