(ns agent.llm.providers.openai-compatible.request
  "Request builders for OpenAI-compatible Chat Completions and Responses APIs."
  (:require
   [agent.defaults :as defaults]
   [agent.llm.messages :as llm-messages]
   [agent.llm.providers.common :as provider-common]
   [clojure.string :as str]))

(defn structured-output-format [{:keys [name schema strict?]}]
  {:type "json_schema"
   :json_schema {:name (or name "structured_output")
                 :schema schema
                 :strict (not (false? strict?))}})

(defn responses-output-format [{:keys [name schema strict?]}]
  {:format {:type "json_schema"
            :name (or name "structured_output")
            :schema schema
            :strict (not (false? strict?))}})

(def ^:private api-aliases
  {nil :chat-completions
   :responses :responses
   :response :responses
   :chat :chat-completions
   :chat-completions :chat-completions
   :chat-completion :chat-completions
   :completions :chat-completions})

(defn normalize-api [value]
  (let [value* (cond
                 (keyword? value) value
                 (string? value) (keyword (str/lower-case value))
                 :else value)]
    (or (api-aliases value*)
        (throw (ex-info (str "Unsupported OpenAI-compatible API: " value)
                        {:type :unsupported-openai-compatible-api
                         :api value})))))

(defn responses-api? [config opts]
  (= :responses (normalize-api (or (:api opts)
                                   (:api config)))))

(defn- provider-kind [base-url]
  (let [url (str/lower-case (or base-url ""))]
    (cond
      (str/includes? url "openrouter.ai") :openrouter
      (str/includes? url "api.openai.com") :openai
      :else :openai-compatible)))

(defn- anthropic-model? [model]
  (let [value (str/lower-case (or model ""))]
    (or (str/starts-with? value "anthropic/")
        (str/includes? value "claude"))))

(defn prompt-cache-enabled? [config opts]
  (not (false? (if (contains? opts :prompt-cache?)
                 (:prompt-cache? opts)
                 (:prompt-cache? config true)))))

(defn prompt-cache-fields [base-url model config opts]
  (if-not (prompt-cache-enabled? config opts)
    {}
    (let [kind (provider-kind base-url)
          cache-control (or (:cache-control opts)
                            (:cache_control opts)
                            (:cache-control config)
                            (:cache_control config)
                            {:type "ephemeral"})
          retention (or (:prompt-cache-retention opts)
                        (:prompt_cache_retention opts)
                        (:prompt-cache-retention config)
                        (:prompt_cache_retention config)
                        "in_memory")]
      (cond
        (or (:cache-control opts) (:cache_control opts))
        {:cache_control cache-control}

        (and (= :openrouter kind) (anthropic-model? model))
        {:cache_control cache-control}

        (= :openai kind)
        {:prompt_cache_retention retention}

        :else {}))))

(defn stream-structured-output? [config opts]
  (provider-common/stream-structured-output? config opts))

(defn request-stream? [config opts]
  (true? (cond
           (contains? opts :stream?) (:stream? opts)
           (contains? opts :stream) (:stream opts)
           :else (:stream? config))))

(defn request-user [config opts]
  (when-let [value (or (:user opts)
                       (:session-id opts)
                       (:session_id opts)
                       (:user config))]
    (let [value* (str value)]
      (when-not (str/blank? value*)
        value*))))

(defn completion-body [base-url default-model config messages opts]
  (let [model (or (:model opts) default-model)
        user (request-user config opts)
        extra-body (merge (or (:extra-body config) {})
                          (or (:extra-body opts) {}))]
    (cond-> (merge {:model model
                    :messages (llm-messages/internal->openai-compatible messages)
                    :temperature (or (:temperature opts)
                                     (:temperature config)
                                     defaults/llm-temperature)
                    :max_tokens (or (:max-tokens opts)
                                    (:max_tokens opts)
                                    (:max-tokens config)
                                    (:max_tokens config)
                                    defaults/llm-max-tokens)
                    :stream false}
                   (prompt-cache-fields base-url model config opts)
                   extra-body)
      user (assoc :user user)
      (:tools opts) (assoc :tools (:tools opts))
      (:tool-choice opts) (assoc :tool_choice (:tool-choice opts))
      (:structured-output opts) (assoc :response_format (structured-output-format (:structured-output opts)))
      (:response-format opts) (assoc :response_format (:response-format opts))
      (:cache-control opts) (assoc :cache_control (:cache-control opts))
      (:cache_control opts) (assoc :cache_control (:cache_control opts)))))

(defn stream-body [base-url default-model config messages opts]
  (cond-> (assoc (completion-body base-url default-model config messages opts) :stream true)
    (or (:structured-output opts) (:include-usage? opts true))
    (assoc :stream_options {:include_usage true})))

(defn responses-tool [tool]
  (if (and (= "function" (:type tool)) (:function tool))
    (let [function (:function tool)]
      (merge (select-keys tool [:type :strict])
             (select-keys function [:name :description :parameters :strict])))
    tool))

(defn responses-tool-choice [choice]
  (if (and (map? choice)
           (= "function" (:type choice))
           (:function choice))
    {:type "function"
     :name (get-in choice [:function :name])}
    choice))

(defn responses-text-format [format]
  (cond
    (nil? format) nil
    (:format format) format
    :else {:format format}))

(defn responses-body [base-url default-model config messages opts]
  (let [model (or (:model opts) default-model)
        user (request-user config opts)
        extra-body (merge (or (:extra-body config) {})
                          (or (:extra-body opts) {}))]
    (cond-> (merge {:model model
                    :input (llm-messages/internal->openai-responses messages)
                    :temperature (or (:temperature opts)
                                     (:temperature config)
                                     defaults/llm-temperature)
                    :max_output_tokens (or (:max-tokens opts)
                                           (:max_tokens opts)
                                           (:max-tokens config)
                                           (:max_tokens config)
                                           defaults/llm-max-tokens)
                    :stream false}
                   (prompt-cache-fields base-url model config opts)
                   extra-body)
      user (assoc :user user)
      (:tools opts) (assoc :tools (mapv responses-tool (:tools opts)))
      (:tool-choice opts) (assoc :tool_choice (responses-tool-choice (:tool-choice opts)))
      (:structured-output opts) (assoc :text (responses-output-format (:structured-output opts)))
      (:response-format opts) (assoc :text (responses-text-format (:response-format opts))))))

(defn responses-stream-body [base-url default-model config messages opts]
  (assoc (responses-body base-url default-model config messages opts) :stream true))
