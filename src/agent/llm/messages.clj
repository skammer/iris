(ns agent.llm.messages
  "Conversions between rich Iris messages and provider wire shapes."
  (:require
   [agent.llm.dsml :as dsml]
   [agent.runtime.schema :as runtime-schema]
   [agent.util :as util]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def ^:private now-str util/now-str)

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
                       (case (:type block)
                         :text (:text block)
                         :tool-result (json-text (:content block))
                         nil))
                     blocks)))

(defn content-text
  "Return provider-visible text from a rich content value or message."
  [value]
  (text-content (runtime-schema/normalize-content
                 (if (and (map? value) (contains? value :content))
                   (:content value)
                   value))))

(def ^:private media-block-types #{:image :audio :video :file})

(defn- source-data-uri [{:keys [source]}]
  (case (:type source)
    :url (:value source)
    :base64 (str "data:"
                 (or (:media-type source) "application/octet-stream")
                 ";base64,"
                 (:value source))
    :file (:value source)))

(defn- source-data [{:keys [source]}]
  (case (:type source)
    :base64 (:value source)
    (source-data-uri {:source source})))

(defn- extension [filename]
  (some-> (re-find #"(?i)\.([a-z0-9]+)$" (or filename ""))
          second
          str/lower-case))

(defn- audio-format [block]
  (let [media-type (some-> (get-in block [:source :media-type]) str/lower-case)
        filename (:filename block)]
    (or (case media-type
          "audio/wav" "wav"
          "audio/x-wav" "wav"
          "audio/mpeg" "mp3"
          "audio/mp3" "mp3"
          "audio/flac" "flac"
          "audio/ogg" "ogg"
          "audio/opus" "opus"
          nil)
        (extension filename)
        "wav")))

(defn- part-detail [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn- openai-content-part [block]
  (case (:type block)
    :text {:type "text" :text (:text block)}
    :image (cond-> {:type "image_url"
                    :image_url {:url (source-data-uri block)}}
             (:detail block) (assoc-in [:image_url :detail] (part-detail (:detail block))))
    :audio {:type "input_audio"
            :input_audio {:data (source-data block)
                          :format (audio-format block)}}
    (:video :file) (cond-> {:type "file"
                            :file {:file_data (source-data block)}}
                     (:filename block) (assoc-in [:file :filename] (:filename block)))
    {:type "text" :text (json-text block)}))

(defn- openai-content [blocks]
  (if (some #(contains? media-block-types (:type %)) blocks)
    (mapv openai-content-part blocks)
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

(defn tool-call-blocks
  [content]
  (filterv #(= :tool-call (:type %))
           (runtime-schema/normalize-content content)))

(defn message-tool-calls
  [message]
  (let [message* (if (and (map? message) (contains? message :content))
                   message
                   {:content message})]
    (vec (concat (tool-call-blocks (:content message*))
                 (map provider-tool-call->internal
                      (or (:tool-calls message*)
                          (:tool_calls message*)
                          []))))))

(defn message->internal
  [message]
  (let [role (role-name (:role message))
        legacy-tool-calls (or (:tool-calls message)
                              (:tool_calls message))
        tool-call-id (or (:tool-call-id message)
                         (:tool_call_id message))
        blocks (if (= "tool" role)
                 (let [normalized (runtime-schema/normalize-content (:content message))]
                   (if (some #(= :tool-result (:type %)) normalized)
                     normalized
                     [(runtime-schema/validate-message-block!
                       (cond-> {:type :tool-result
                                :tool-call-id tool-call-id
                                :content (:content message)}
                         (:name message) (assoc :name (:name message))))]))
                 (runtime-schema/normalize-content (:content message)))
        existing-tool-call? (some #(= :tool-call (:type %)) blocks)
        legacy-blocks (when (and (seq legacy-tool-calls)
                                 (not existing-tool-call?))
                        (mapv provider-tool-call->internal legacy-tool-calls))]
    (cond-> {:role role
             :content (vec (concat blocks legacy-blocks))}
	      (:id message) (assoc :id (:id message))
	      (:name message) (assoc :name (:name message))
	      (:metadata message) (assoc :metadata (:metadata message))
	      (:created-at message) (assoc :created-at (:created-at message)))))

(defn messages->internal
  [messages]
  (mapv message->internal (or messages [])))

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

(defn- tool-result-block [blocks]
  (first (filter #(= :tool-result (:type %)) blocks)))

(defn- openai-responses-content-part [block]
  (case (:type block)
    :text {:type "input_text" :text (:text block)}
    :image (cond-> {:type "input_image"
                    :image_url (source-data-uri block)}
             (:detail block) (assoc :detail (part-detail (:detail block))))
    (:video :file) (cond-> {:type "input_file"
                            :file_data (source-data-uri block)}
                     (:filename block) (assoc :filename (:filename block)))
    {:type "input_text" :text (json-text block)}))

(defn- openai-responses-content [blocks]
  (if (some #(contains? media-block-types (:type %)) blocks)
    (mapv openai-responses-content-part blocks)
    (text-content blocks)))

(defn internal-tool-call->openai-response-call
  [block]
  (let [block* (runtime-schema/normalize-block block)
        call-id (:id block*)]
    (cond-> {:type "function_call"
             :name (name (:name block*))
             :arguments (json/generate-string (or (:arguments block*) {}))}
      call-id (assoc :call_id call-id))))

(defn internal-tool-result->openai-response-output
  [block]
  (let [block* (runtime-schema/normalize-block block)]
    {:type "function_call_output"
     :call_id (:tool-call-id block*)
     :output (json-text (:content block*))}))

(defn internal->openai-responses
  [messages]
  (mapv identity
        (mapcat (fn [message]
                  (let [message* (message->internal message)
                        role (:role message*)
                        blocks (content-blocks message*)
                        tool-calls (tool-call-blocks blocks)]
                    (case role
                      "assistant"
                      (let [text (text-content blocks)]
                        (concat
                         (when-not (str/blank? text)
                           [{:role "assistant" :content text}])
                         (map internal-tool-call->openai-response-call tool-calls)))

                      "tool"
                      [(internal-tool-result->openai-response-output
                        (or (tool-result-block blocks)
                            {:type :tool-result
                             :tool-call-id (:tool-call-id message)
                             :content (text-content blocks)}))]

                      [(cond-> {:role role
                                :content (openai-responses-content blocks)}
                         (:name message) (assoc :name (:name message)))])))
                messages)))

(defn internal->openai-compatible
  [messages]
  (mapv (fn [message]
          (let [message* (message->internal message)
                role (:role message*)
                blocks (content-blocks message*)]
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
          (let [message* (message->internal message)
                role (:role message*)
                blocks (content-blocks message*)
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
      (:reasoning response)
      (get-in response [:raw :reasoning_content])
      (get-in response [:raw :reasoning])
      (get-in response [:raw :thinking])))

(defn provider-response->assistant-turn
  ([response] (provider-response->assistant-turn nil nil response))
  ([provider model response]
   (let [response* (dsml/recover-tool-calls
                    (if (map? response) response {:content (json-text response)}))
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
