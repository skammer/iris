(ns agent.llm.providers.openai
  "OpenAI provider implementation."
  (:require
   [agent.llm.core :as llm-core]
   [clj-http.client :as http]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str])
  (:import
   (java.net URLEncoder)))

;; ======================
;; OpenAI Provider
;; ======================

(defrecord OpenAIProvider [api-key base-url config]
  llm-core/ILLMProvider
  (complete [this messages opts]
    (let [url (str (or base-url "https://api.openai.com/v1") "/chat/completions")
          body {:model (or (:model opts) "gpt-3.5-turbo")
                :messages (llm-core/normalize-messages messages)
                :temperature (or (:temperature opts) 0.7)
                :max_tokens (or (:max-tokens opts) 1000)
                :stream false}
          response (http/post url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string body)
                               :as :json})]
      (-> response :body :choices first :message :content)))
  
  (stream [this messages opts]
    (let [url (str (or base-url "https://api.openai.com/v1") "/chat/completions")
          body {:model (or (:model opts) "gpt-3.5-turbo")
                :messages (llm-core/normalize-messages messages)
                :temperature (or (:temperature opts) 0.7)
                :max_tokens (or (:max-tokens opts) 1000)
                :stream true}
          ch (async/chan)]
      (async/go
        (try
          (let [response (http/post url
                                    {:headers {"Authorization" (str "Bearer " api-key)
                                               "Content-Type" "application/json"}
                                     :as :stream})]
            (doseq [line (-> response :body .getLines)]
              (when (str/starts-with? line "data: ")
                (let [data (subs line 6)]
                  (when-not (str/blank? data)
                    (let [parsed (json/parse-string data true)]
                      (when-let [content (-> parsed :choices first :delta :content)]
                        (async/>! ch content))))))))
          (catch Exception e
            (async/>! ch {:error (str e)}))
          (finally
            (async/close! ch))))
      ch))
  
  (embed [this text opts]
    (let [url (str (or base-url "https://api.openai.com/v1") "/embeddings")
          texts (if (string? text) [text] text)
          body {:model (or (:model opts) "text-embedding-ada-002")
                :input texts}
          response (http/post url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/generate-string body)
                               :as :json})]
      (-> response :body :data (mapv :embedding))))
  
  (list-models [this]
    (let [url (str (or base-url "https://api.openai.com/v1") "/models")
          response (http/get url
                             {:headers {"Authorization" (str "Bearer " api-key)}
                              :as :json})]
      (->> response :body :data
           (map (fn [model]
                  {:id (:id model)
                   :object (:object model)
                   :created (:created model)
                   :owned_by (:owned_by model)})))))
  
  (get-capabilities [this model]
    (let [model-info (first (filter #(= (:id %) model) (list-models this)))]
      (when model-info
        {:max-tokens (case model
                       "gpt-4" 8192
                       "gpt-4-turbo" 128000
                       "gpt-3.5-turbo" 16385
                       4096)
         :supports-embedding (str/includes? model "embedding")
         :supports-chat (str/includes? model "gpt")
         :model-type (cond
                       (str/includes? model "gpt-4") :chat
                       (str/includes? model "gpt-3.5") :chat
                       (str/includes? model "embedding") :embedding
                       :else :completion)})))
  
  (estimate-cost [this messages model]
    (let [capabilities (get-capabilities this model)
          ;; Simplified token estimation
          total-chars (reduce + (map #(count (:content %)) messages))
          estimated-tokens (int (/ total-chars 4))]
      {:tokens estimated-tokens
       :cost-usd (case model
                   "gpt-4" (* estimated-tokens 0.00003)
                   "gpt-4-turbo" (* estimated-tokens 0.00001)
                   "gpt-3.5-turbo" (* estimated-tokens 0.0000015)
                   0.0)})))

;; Implement configuration protocols
(extend-type OpenAIProvider
  llm-core/ILLMProviderWithConfig
  (update-config [this new-config]
    (OpenAIProvider. (:api-key this)
                     (:base-url this)
                     (merge (:config this) new-config)))
  
  (get-config [this]
    (merge {:api-key (:api-key this)
            :base-url (:base-url this)}
           (:config this))))

(extend-type OpenAIProvider
  llm-core/ILLMProviderWithHealth
  (health-check [this]
    (try
      (let [response (http/get (str (or (:base-url this) "https://api.openai.com/v1") "/models")
                               {:headers {"Authorization" (str "Bearer " (:api-key this))}
                                :throw-exceptions false})]
        {:healthy (= 200 (:status response))
         :details {:status (:status response)
                   :message (if (= 200 (:status response))
                              "API is accessible"
                              (str "API error: " (:status response)))}})
      (catch Exception e
        {:healthy false
         :details {:error (str e)}}))))

;; ======================
;; Factory Functions
;; ======================

(defn create-openai-provider
  "Create an OpenAI provider instance.
  
  Options:
  :api-key - OpenAI API key (required)
  :base-url - Custom base URL (optional)
  :config - Additional configuration (optional)"
  [{:keys [api-key base-url config]
    :or {config {}}}]
  (when-not api-key
    (throw (ex-info "OpenAI API key is required" {})))
  (OpenAIProvider. api-key base-url config))

(defn create-provider
  "Create a provider by type.
  
  Supported types:
  :openai - OpenAI provider
  :anthropic - Anthropic provider (if available)"
  [type config]
  (case type
    :openai (create-openai-provider config)
    :anthropic (when-let [anthropic-ns (try
                                         (require 'agent.llm.providers.anthropic)
                                         (find-ns 'agent.llm.providers.anthropic)
                                         (catch Exception _ nil))]
                 (when-let [create-fn (ns-resolve anthropic-ns 'create-anthropic-provider)]
                   (create-fn config)))
    (throw (ex-info (str "Unsupported provider type: " type) {}))))