(ns agent.llm.providers.ollama
  "Native Ollama provider."
  (:require
   [agent.llm.core :as llm-core]
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
  {:model (or (:model opts) default-model)
   :messages (llm-core/normalize-messages messages)
   :stream stream?
   :keep_alive (or (:keep-alive opts) keep-alive)
   :options (cond-> {}
              (:temperature opts) (assoc :temperature (:temperature opts))
              (:max-tokens opts) (assoc :num_predict (:max-tokens opts)))})

(defrecord OllamaProvider [base-url default-model embedding-model keep-alive config]
  llm-core/ILLMProvider
  (complete [_ messages opts]
    (let [response (http/post (endpoint base-url "/api/chat")
                              {:body (json/generate-string (chat-body default-model keep-alive messages opts false))
                               :content-type :json
                               :accept :json
                               :as :json})]
      (-> response :body :message :content)))

  (stream [_ messages opts]
    (let [ch (async/chan)]
      (async/thread
        (try
          (let [response (http/post (endpoint base-url "/api/chat")
                                    {:body (json/generate-string (chat-body default-model keep-alive messages opts true))
                                     :content-type :json
                                     :accept :json
                                     :as :stream})]
            (with-open [reader (io/reader (:body response))]
              (doseq [line (line-seq reader)]
                (when-not (str/blank? line)
                  (let [event (json/parse-string line true)]
                    (when-let [content (-> event :message :content)]
                      (async/>!! ch content)))))))
          (catch Exception e
            (async/>!! ch {:error (.getMessage e)}))
          (finally
            (async/close! ch))))
      ch))

  (embed [_ text opts]
    (let [input (if (string? text) text (vec text))
          response (http/post (endpoint base-url "/api/embed")
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
     :supports-tools true})

  (estimate-cost [_ messages model]
    {:tokens (llm-core/count-tokens-estimate messages)
     :cost-usd 0.0
     :model model}))

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
  [{:keys [base-url model default-model embedding-model keep-alive]
    :or {base-url "http://localhost:11434"
         keep-alive "5m"
         embedding-model "nomic-embed-text"}}]
  (->OllamaProvider base-url
                    (or default-model model "llama3.2:3b")
                    embedding-model
                    keep-alive
                    {}))
