(ns agent.test.predictable
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.string :as str]))

(defn- parse-json [text]
  (try
    (json/parse-string text true)
    (catch Exception _
      {:raw text})))

(defn- text-of [message]
  (llm-messages/content-text message))

(defn- latest-user-text [messages]
  (some->> messages
           reverse
           (some #(when (= "user" (:role %)) (text-of %)))
           str/trim))

(defn- last-role [messages]
  (:role (last messages)))

(defn- tool-call
  [id name args]
  {:id id
   :type "function"
   :function {:name name
              :arguments (json/generate-string args)}})

(defn- split-tool-pattern [text]
  (let [body (str/trim (subs text (count "tool: ")))
        [tool-name json-text] (str/split body #"\s+" 2)]
    [tool-name (parse-json (or json-text "{}"))]))

(defn- split-shell-pattern [text]
  (str/trim (subs text (count "shell: "))))

(defn- response-for [text messages]
  (cond
    (= "tool" (last-role messages))
    {:content "Done."}

    (str/starts-with? text "echo: ")
    {:content (subs text (count "echo: "))}

    (str/starts-with? text "tool: ")
    (let [[tool-name args] (split-tool-pattern text)]
      {:content ""
       :tool-calls [(tool-call "call_predictable_tool" tool-name args)]})

    (str/starts-with? text "shell: ")
    (let [command (split-shell-pattern text)]
      {:content ""
       :tool-calls [(tool-call "call_predictable_shell"
                               "shell"
                               {:argv ["sh" "-lc" command]})]})

    (str/starts-with? text "delay: ")
    (let [seconds (Double/parseDouble (str/trim (subs text (count "delay: "))))]
      (Thread/sleep (long (* 1000 seconds)))
      {:content (str "Delayed for " seconds " seconds")})

    (str/starts-with? text "error: ")
    (throw (llm-core/llm-error :predictable-error
                               (subs text (count "error: "))))

    (= text "truncate")
    {:content "predictable truncated output"
     :stop-reason "length"
     :usage {:tokens 7}}

    (= text "multi-tool")
    {:content ""
     :tool-calls [(tool-call "call_predictable_fs"
                             "fs"
                             {:action "list" :path "."})
                  (tool-call "call_predictable_http"
                             "http"
                             {:url "http://127.0.0.1/"})]}

    (= text "empty-assistant")
    {:content ""}

    (= text "orphan-tool-result")
    {:content "orphan repaired"}

    :else
    {:content text}))

(defrecord PredictableProvider [requests]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (:content (response-for (latest-user-text messages) messages)))
  (stream [_ messages _]
    (async/to-chan! [(:content (response-for (latest-user-text messages) messages))]))
  (embed [_ _ _] [])
  (list-models [_] [{:id "predictable" :name "predictable"}])
  (get-capabilities [_ _] {:supports-tools true :supports-streaming true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ {:keys [messages on-content-delta] :as request}]
    (swap! requests (fn [xs] (vec (take-last 20 (conj xs request)))))
    (let [response (merge {:role "assistant"
                           :content ""
                           :tool-calls []
                           :usage nil
                           :raw nil}
                          (response-for (latest-user-text messages) messages))]
      (when (and on-content-delta (seq (:content response)))
        (doseq [ch (map str (:content response))]
          (on-content-delta ch)))
      response))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn create-provider []
  (->PredictableProvider (atom [])))

(defn recent-requests [provider]
  @(:requests provider))
