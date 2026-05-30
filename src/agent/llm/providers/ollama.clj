(ns agent.llm.providers.ollama
  "Native Ollama provider."
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.dsml :as dsml]
   [agent.llm.messages :as llm-messages]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- trim-trailing-slash [value]
  (str/replace (or value "") #"/+$" ""))

(defn- endpoint [base-url path]
  (str (trim-trailing-slash base-url) path))

(defn- chat-body [default-model keep-alive messages opts stream?]
  (cond-> {:model (or (:model opts) default-model)
           :messages (llm-messages/internal->ollama messages)
           :stream stream?
           :keep_alive (or (:keep-alive opts) keep-alive)
           :options (cond-> {}
                      (:temperature opts) (assoc :temperature (:temperature opts))
                      (:max-tokens opts) (assoc :num_predict (:max-tokens opts)))}
    (:tools opts) (assoc :tools (:tools opts))))

(defn- structured-chat-body [default-model keep-alive messages opts stream?]
  (cond-> (chat-body default-model keep-alive messages opts stream?)
    (:structured-output opts) (assoc :format (get-in opts [:structured-output :schema]))
    (:response-format opts) (assoc :format (:response-format opts))))

(defn- stream-structured-output? [config opts]
  (and (:structured-output opts)
       (not (false? (if (contains? opts :stream-structured-output?)
                      (:stream-structured-output? opts)
                      (:stream-structured-output? config true))))))

(defn- checked-response [response]
  (if (<= 200 (:status response 0) 299)
    response
    (let [error (llm-core/llm-error :http-error
                                    (str "Ollama request failed: " (:status response))
                                    {:status (:status response)
                                     :headers (:headers response)
                                     :body (:body response)})]
      (when (instance? java.io.Closeable (:body response))
        (.close ^java.io.Closeable (:body response)))
      (throw error))))

(defn- post-json [url request]
  (llm-core/retry-with-backoff
   #(checked-response (http/post url (assoc request :throw-exceptions false)))))

(defn- stream-response->turn
  ([body-stream] (stream-response->turn body-stream nil))
  ([body-stream on-content-delta]
   (with-open [reader (io/reader body-stream)]
     (loop [content []
            tool-calls []
            prompt-tokens 0
            completion-tokens 0
            raw []]
       (if-let [line (.readLine reader)]
         (if (str/blank? line)
           (recur content tool-calls prompt-tokens completion-tokens raw)
           (let [event (json/parse-string line true)
                 message (:message event)
                 chunk (:content message)
                 tool-calls* (or (:tool_calls message)
                                 (:tool-calls message))]
             (when (and on-content-delta (string? chunk) (not= "" chunk))
               (on-content-delta chunk))
             (recur (cond-> content
                      (string? chunk) (conj chunk))
                    (cond-> tool-calls
                      (seq tool-calls*) (into tool-calls*))
                    (or (:prompt_eval_count event) prompt-tokens)
                    (or (:eval_count event) completion-tokens)
                    (conj raw event))))
         {:role "assistant"
          :content (apply str content)
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
    (let [stream? (stream-structured-output? config opts)
          request {:body (json/generate-string (structured-chat-body default-model keep-alive messages opts stream?))
                   :content-type :json
                   :accept :json}]
      (if stream?
        (let [response (checked-response
                        (http/post (endpoint base-url "/api/chat")
                                   (assoc request
                                          :throw-exceptions false
                                          :as :stream)))]
          (:content (stream-response->turn (:body response))))
        (let [response (post-json (endpoint base-url "/api/chat")
                                  (assoc request :as :json))]
          (-> response :body :message :content)))))

  (stream [_ messages opts]
    (let [ch (async/chan)]
      (async/thread
        (try
          (let [response (checked-response
                          (http/post (endpoint base-url "/api/chat")
                                     {:body (json/generate-string (structured-chat-body default-model keep-alive messages opts true))
                                      :content-type :json
                                      :accept :json
                                      :throw-exceptions false
                                      :as :stream}))]
            (with-open [reader (io/reader (:body response))]
              (doseq [line (line-seq reader)]
                (when-not (str/blank? line)
                  (let [event (json/parse-string line true)]
                    (when-let [content (-> event :message :content)]
                      (async/>!! ch content)))))))
          (catch Exception e
            (async/>!! ch (llm-core/stream-error-event e)))
          (finally
            (async/close! ch))))
      ch))

  (embed [_ text opts]
    (let [input (if (string? text) text (vec text))
          response (post-json (endpoint base-url "/api/embed")
                              {:body (json/generate-string {:model (or (:model opts)
                                                                       (:embedding-model opts)
                                                                       embedding-model)
                                                            :input input})
                               :content-type :json
                               :accept :json
                               :as :json})
          embeddings (-> response :body :embeddings)]
      (if (string? text)
        (first embeddings)
        (vec embeddings))))

  (list-models [_]
    (let [response (http/get (endpoint base-url "/api/tags")
                             {:accept :json
                              :as :json})]
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
  llm-core/ILLMProviderWithTools
  (complete-with-tools [this messages tools opts]
    (let [response (post-json (endpoint (:base-url this) "/api/chat")
                              {:body (json/generate-string
                                      (chat-body (:default-model this)
                                                 (:keep-alive this)
                                                 messages
                                                 (assoc opts :tools tools)
                                                 false))
                               :content-type :json
                               :accept :json
                               :as :json})
          message (-> response :body :message)]
      {:role (:role message "assistant")
       :content (:content message)
       :tool-calls (vec (or (:tool_calls message) []))
       :usage {:prompt-tokens 0
               :completion-tokens 0
               :cached-tokens 0
               :tokens 0
               :cost-usd 0.0}
       :raw message})))

(extend-type OllamaProvider
  llm-core/ILLMProviderInvoke
  (invoke [this request]
    (let [opts (llm-core/request->completion-opts request)
          stream? (or (some? (:on-content-delta opts))
                      (stream-structured-output? (:config this) opts))
          request* {:body (json/generate-string
                           (structured-chat-body (:default-model this)
                                                 (:keep-alive this)
                                                 (:messages request)
                                                 opts
                                                 stream?))
                    :content-type :json
                    :accept :json}]
      (if stream?
        (let [response (checked-response
                        (http/post (endpoint (:base-url this) "/api/chat")
                                   (assoc request*
                                          :throw-exceptions false
                                          :as :stream)))]
          (llm-core/normalize-llm-response
           (stream-response->turn (:body response)
                                  (dsml/guard-content-delta (:on-content-delta opts)
                                                            (:tools opts)))
           {}))
        (let [response (post-json (endpoint (:base-url this) "/api/chat")
                                  (assoc request* :as :json))
              body (:body response)
              message (:message body)]
          (llm-core/normalize-llm-response
           {:role (:role message "assistant")
            :content (:content message)
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
  llm-core/ILLMProviderWithUsage
  (usage [_ response _opts]
    {:prompt-tokens (or (:prompt_eval_count response) 0)
     :completion-tokens (or (:eval_count response) 0)
     :cached-tokens 0
     :tokens (+ (or (:prompt_eval_count response) 0)
                (or (:eval_count response) 0))
     :cost-usd 0.0}))

(extend-type OllamaProvider
  llm-core/ILLMProviderWithConfig
  (update-config [this new-config]
    (->OllamaProvider
     (or (:base-url new-config) (:base-url this))
     (or (:default-model new-config) (:default-model this))
     (or (:embedding-model new-config) (:embedding-model this))
     (or (:keep-alive new-config) (:keep-alive this))
     (merge (:config this) new-config)))

  (get-config [this]
    {:base-url (:base-url this)
     :default-model (:default-model this)
     :embedding-model (:embedding-model this)
     :keep-alive (:keep-alive this)
     :config (:config this)}))

(extend-type OllamaProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [response (http/get (endpoint (:base-url this) "/api/tags")
                               {:throw-exceptions false
                                :accept :json
                                :as :json})]
        {:healthy (= 200 (:status response))
         :details {:status (:status response)}})
      (catch Exception e
        {:healthy false
         :details {:error (.getMessage e)}})))

  (get-metrics [_]
    {:provider :ollama}))

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
                    (merge (select-keys opts [:stream-structured-output?])
                           config)))
