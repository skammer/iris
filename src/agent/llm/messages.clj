(ns agent.llm.messages
  "Conversions between rich Iris messages and provider wire shapes."
  (:require
   [agent.runtime.schema :as runtime-schema]
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.time Instant)))

(defn- now-str [] (str (Instant/now)))

(defn- role-name [role]
  (cond
    (keyword? role) (name role)
    (string? role) role
    :else (str role)))

(defn- json-object [value]
  (cond
    (nil? value) {}
    (map? value) value
    (string? value) (try
                      (let [parsed (json/parse-string value true)]
                        (if (map? parsed) parsed {:value parsed}))
                      (catch Exception _
                        {:arguments value}))
    :else {:value value}))

(defn- json-text [value]
  (cond
    (nil? value) ""
    (string? value) value
    :else (json/generate-string value)))

(defn- content-blocks [message]
  (runtime-schema/normalize-content (:content message)))

(defn- text-content [blocks]
  (str/join "" (keep (fn [block]
                       (when (= :text (:type block))
                         (:text block)))
                     blocks)))

(defn- image-url [{:keys [source]}]
  (case (:type source)
    :url (:value source)
    :base64 (str "data:"
                 (or (:media-type source) "application/octet-stream")
                 ";base64,"
                 (:value source))
    :file (:value source)))

(defn- openai-content [blocks]
  (if (some #(= :image (:type %)) blocks)
    (mapv (fn [block]
            (case (:type block)
              :text {:type "text" :text (:text block)}
              :image {:type "image_url"
                      :image_url {:url (image-url block)}}
              {:type "text" :text (json-text block)}))
          blocks)
    (text-content blocks)))

(defn provider-tool-call->internal
  [tool-call]
  (let [function (or (:function tool-call)
                     (:function_call tool-call)
                     tool-call)
        name* (or (:name function)
                  (:tool-name tool-call)
                  (:tool_name tool-call)
                  (:name tool-call))
        arguments (or (:arguments function)
                      (:input tool-call)
                      (:args tool-call))]
    (runtime-schema/validate-message-block!
     (cond-> {:type :tool-call
              :name name*
              :arguments (json-object arguments)
              :raw tool-call}
       (:id tool-call) (assoc :id (:id tool-call))))))

(defn internal-tool-call->provider-tool-call
  ([block] (internal-tool-call->provider-tool-call :openai-compatible block))
  ([_provider block]
   (let [block* (runtime-schema/normalize-block block)]
     (cond-> {:type "function"
              :function {:name (name (:name block*))
                         :arguments (json/generate-string (or (:arguments block*) {}))}}
       (:id block*) (assoc :id (:id block*))))))

(defn provider-tool-result->internal
  [message]
  (runtime-schema/validate-message-block!
   {:type :tool-result
    :tool-call-id (or (:tool-call-id message)
                      (:tool_call_id message))
    :name (:name message)
    :status (or (:status message) :ok)
    :content (let [content (:content message)]
               (if (string? content)
                 (try
                   (json/parse-string content true)
                   (catch Exception _
                     content))
                 content))
    :raw message}))

(defn internal-tool-result->provider-tool-result
  ([block] (internal-tool-result->provider-tool-result :openai-compatible block))
  ([_provider block]
   (let [block* (runtime-schema/normalize-block block)]
     {:role "tool"
      :tool_call_id (:tool-call-id block*)
      :content (json-text (:content block*))})))

(defn- tool-call-blocks [blocks]
  (filterv #(= :tool-call (:type %)) blocks))

(defn- tool-result-block [blocks]
  (first (filter #(= :tool-result (:type %)) blocks)))

(defn internal->openai-compatible
  [messages]
  (mapv (fn [message]
          (let [role (role-name (:role message))
                blocks (content-blocks message)]
            (case role
              "assistant"
              (cond-> {:role "assistant"
                       :content (text-content blocks)}
                (seq (tool-call-blocks blocks))
                (assoc :tool_calls (mapv internal-tool-call->provider-tool-call
                                          (tool-call-blocks blocks))))

              "tool"
              (let [tool-result (or (tool-result-block blocks)
                                    {:type :tool-result
                                     :tool-call-id (:tool-call-id message)
                                     :content (text-content blocks)})]
                (internal-tool-result->provider-tool-result tool-result))

              (cond-> {:role role
                       :content (openai-content blocks)}
                (:name message) (assoc :name (:name message))))))
        messages))

(defn- ollama-images [blocks]
  (into [] (keep (fn [block]
                   (when (and (= :image (:type block))
                              (= :base64 (get-in block [:source :type])))
                     (get-in block [:source :value]))))
        blocks))

(defn internal->ollama
  [messages]
  (mapv (fn [message]
          (let [role (role-name (:role message))
                blocks (content-blocks message)
                images (ollama-images blocks)]
            (cond-> {:role role
                     :content (text-content blocks)}
              (seq images) (assoc :images images)
              (and (= "assistant" role) (seq (tool-call-blocks blocks)))
              (assoc :tool_calls (mapv #(internal-tool-call->provider-tool-call :ollama %)
                                        (tool-call-blocks blocks)))
              (= "tool" role)
              (assoc :tool_call_id (:tool-call-id (or (tool-result-block blocks)
                                                       message))))))
        messages))

(defn- response-tool-calls [response]
  (vec (or (:tool-calls response)
           (:tool_calls response)
           (get-in response [:raw :tool_calls])
           [])))

(defn- response-thinking [response]
  (or (:thinking response)
      (:reasoning-content response)
      (:reasoning_content response)
      (get-in response [:raw :reasoning_content])
      (get-in response [:raw :thinking])))

(defn provider-response->assistant-turn
  ([response] (provider-response->assistant-turn nil nil response))
  ([provider model response]
   (let [response* (if (map? response) response {:content (json-text response)})
         thinking (response-thinking response*)
         content (cond-> []
                   (and (string? thinking) (not (str/blank? thinking)))
                   (conj {:type :thinking :text thinking})
                   (some? (:content response*))
                   (conj {:type :text :text (str (:content response*))})
                   (seq (response-tool-calls response*))
                   (into (map provider-tool-call->internal
                              (response-tool-calls response*))))]
     (runtime-schema/validate-assistant-turn!
      {:provider provider
       :model model
       :response-model (or (:response-model response*)
                           (:model response*)
                           (get-in response* [:raw :model]))
       :response-id (or (:response-id response*)
                        (:id response*)
                        (get-in response* [:raw :id]))
       :content (runtime-schema/normalize-content content)
       :usage (:usage response*)
       :stop-reason (or (:stop-reason response*)
                        (:stop_reason response*)
                        (:finish-reason response*)
                        (:finish_reason response*))
       :error (:error response*)
       :timestamp (or (:timestamp response*) (now-str))}))))
