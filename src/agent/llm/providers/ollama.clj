(ns agent.llm.providers.ollama
  "Native Ollama provider."
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.messages :as llm-messages]
   [agent.llm.providers.common :as provider-common]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- chat-body [default-model keep-alive messages opts stream?]
  (cond-> {:model (or (:model opts) default-model)
           :messages (llm-messages/internal->ollama messages)
           :stream stream?
           :keep_alive (or (:keep-alive opts) keep-alive)
           :options (cond-> {}
                      (:temperature opts) (assoc :temperature (:temperature opts))
                      (:max-tokens opts) (assoc :num_predict (:max-tokens opts)))}
    (contains? opts :think) (assoc :think (:think opts))
    (contains? opts :thinking) (assoc :think (:thinking opts))
    (:tools opts) (assoc :tools (:tools opts))))

(defn- structured-chat-body [default-model keep-alive messages opts stream?]
  (cond-> (chat-body default-model keep-alive messages opts stream?)
    (:structured-output opts) (assoc :format (get-in opts [:structured-output :schema]))
    (:response-format opts) (assoc :format (:response-format opts))))

(defn- ollama-http-error [response]
  (provider-common/http-error (str "Ollama request failed: " (:status response))
                              response))

(defn- post-json [url request]
  (provider-common/post-json url request ollama-http-error))

(defn- post-stream [url request]
  (provider-common/post-stream url request ollama-http-error))

(defn- stream-response->turn
  ([body-stream] (stream-response->turn body-stream nil nil))
  ([body-stream on-content-delta on-thinking-delta]
   (with-open [reader (io/reader body-stream)]
     (loop [content []
            thinking []
            tool-calls []
            prompt-tokens 0
            completion-tokens 0
            raw []]
       (if-let [line (.readLine reader)]
         (if (str/blank? line)
           (recur content thinking tool-calls prompt-tokens completion-tokens raw)
           (let [event (json/parse-string line true)
                 message (:message event)
                 chunk (:content message)
                 thinking* (llm-messages/reasoning-text message)
                 tool-calls* (or (:tool_calls message)
                                 (:tool-calls message))]
             (when (and on-content-delta (string? chunk) (not= "" chunk))
               (on-content-delta chunk))
             (when (and on-thinking-delta (string? thinking*) (not= "" thinking*))
               (on-thinking-delta thinking*))
             (recur (cond-> content
                      (string? chunk) (conj chunk))
                    (cond-> thinking
                      (string? thinking*) (conj thinking*))
                    (cond-> tool-calls
                      (seq tool-calls*) (into tool-calls*))
                    (or (:prompt_eval_count event) prompt-tokens)
                    (or (:eval_count event) completion-tokens)
                    (conj raw event))))
        {:role "assistant"
         :content (apply str content)
         :reasoning-content (when (seq thinking)
                              (apply str thinking))
         :tool-calls tool-calls
         :usage {:prompt-tokens prompt-tokens
                  :completion-tokens completion-tokens
                  :cached-tokens 0
                  :tokens (+ prompt-tokens completion-tokens)
                  :cost-usd 0.0}
          :raw raw})))))

(defrecord OllamaProvider [base-url default-model embedding-model keep-alive config]
  llm-core/ILLMProvider
  (complete [_ messages opts]
    (let [opts* (merge config opts)
          stream? (provider-common/stream-structured-output? config opts*)
          request (provider-common/with-transport-options
                   {:body (json/generate-string (structured-chat-body default-model keep-alive messages opts* stream?))
                    :content-type :json
                    :accept :json}
                   config
                   opts*)]
      (if stream?
        (let [response (post-stream (provider-common/endpoint base-url "/api/chat")
                                    request)]
          (:content (stream-response->turn (:body response))))
        (let [response (post-json (provider-common/endpoint base-url "/api/chat")
                                  (assoc request :as :json))]
          (-> response :body :message :content)))))

  (stream [_ messages opts]
    (provider-common/stream-channel
     (fn [emit!]
       (let [opts* (merge config opts)]
         (with-open [reader (io/reader
                             (:body (post-stream
                                     (provider-common/endpoint base-url "/api/chat")
                                     (provider-common/with-transport-options
                                      {:body (json/generate-string (structured-chat-body default-model keep-alive messages opts* true))
                                       :content-type :json
                                       :accept :json}
                                      config
                                      opts*))))]
           (doseq [line (line-seq reader)]
             (when-not (str/blank? line)
               (let [event (json/parse-string line true)]
                 (when-let [content (-> event :message :content)]
                   (emit! content))))))))))

  (embed [_ text opts]
    (let [input (if (string? text) text (vec text))
          response (post-json (provider-common/endpoint base-url "/api/embed")
                              (provider-common/with-transport-options
                               {:body (json/generate-string {:model (or (:model opts)
                                                                        (:embedding-model opts)
                                                                        embedding-model)
                                                             :input input})
                                :content-type :json
                                :accept :json
                                :as :json}
                               config
                               opts))
          embeddings (-> response :body :embeddings)]
      (if (string? text)
        (first embeddings)
        (vec embeddings))))

  (list-models [_]
    (let [request (provider-common/with-transport-options {:accept :json
                                                           :as :json}
                                                          config
                                                          {})
          response (http/get (provider-common/endpoint base-url "/api/tags")
                             (provider-common/http-request-options request))]
      (vec (or (-> response :body :models) []))))

  (get-capabilities [_ model]
    {:model model
     :supports-streaming true
     :supports-embedding true
     :supports-tools true
     :supports-vision true})

  (estimate-cost [_ messages model]
    {:tokens (llm-core/count-tokens-estimate messages)
     :cost-usd 0.0
     :model model}))

(extend-type OllamaProvider
  llm-core/ILLMProviderInvoke
  (invoke [this request]
    (let [opts (merge (:config this)
                      (llm-core/request->completion-opts request))
          stream? (or (some? (:on-content-delta opts))
                      (some? (:on-thinking-delta opts))
                      (provider-common/stream-structured-output? (:config this) opts))
          request* (provider-common/with-transport-options
                    {:body (json/generate-string
                            (structured-chat-body (:default-model this)
                                                  (:keep-alive this)
                                                  (:messages request)
                                                  opts
                                                  stream?))
                     :content-type :json
                     :accept :json}
                    (:config this)
                    opts)]
      (if stream?
        (let [response (post-stream (provider-common/endpoint (:base-url this) "/api/chat")
                                    request*)]
          (llm-core/normalize-llm-response
           (stream-response->turn (:body response)
                                  (dsml/guard-content-delta (:on-content-delta opts)
                                                            (:tools opts))
                                  (:on-thinking-delta opts))
           {}))
        (let [response (post-json (provider-common/endpoint (:base-url this) "/api/chat")
                                  (assoc request* :as :json))
              body (:body response)
              message (:message body)]
          (llm-core/normalize-llm-response
           {:role (:role message "assistant")
            :content (:content message)
            :reasoning-content (llm-messages/reasoning-text message)
            :tool-calls (vec (or (:tool_calls message) []))
            :usage {:prompt-tokens (or (:prompt_eval_count body) 0)
                    :completion-tokens (or (:eval_count body) 0)
                    :cached-tokens 0
                    :tokens (+ (or (:prompt_eval_count body) 0)
                               (or (:eval_count body) 0))
                    :cost-usd 0.0}
            :raw body}
           {})))))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(extend-type OllamaProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [response (http/get (provider-common/endpoint (:base-url this) "/api/tags")
                               (let [request (provider-common/with-transport-options
                                              {:throw-exceptions false
                                               :accept :json
                                               :as :json}
                                              (:config this)
                                              {})]
                                 (provider-common/http-request-options request)))]
        {:healthy (= 200 (:status response))
         :details {:status (:status response)}})
      (catch Exception e
        {:healthy false
         :details {:error (.getMessage e)}}))))

(defn create-ollama-provider
  [{:keys [base-url model default-model embedding-model keep-alive config]
    :as opts
    :or {base-url "http://localhost:11434"
         keep-alive "5m"
         embedding-model "nomic-embed-text"}}]
  (->OllamaProvider base-url
                    (or default-model model "llama3.2:3b")
                    embedding-model
                    keep-alive
                    (merge (select-keys opts [:stream-structured-output?
                                              :temperature
                                              :max-tokens
                                              :max_tokens
                                              :timeout-ms
                                              :max-retries
                                              :initial-delay
                                              :max-delay])
                           config)))
