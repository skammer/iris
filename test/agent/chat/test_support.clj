(ns agent.chat.test-support
  (:require
   [agent.llm.core :as llm-core]
   [agent.llm.messages :as llm-messages]
   [agent.memory.core :as memory]
   [agent.persistence.sqlite :as sqlite]
   [agent.system :as system]
   [agent.system.events :as events]
   [agent.tools.service :as tool-service]
   [agent.tools.core :as tools]
   [cheshire.core :as json]
   [clojure.core.async :as async]))

(defrecord PlannerProvider [responses requests]
  llm-core/ILLMProvider
  (complete [_ messages _]
    (swap! requests conj {:mode :complete :messages messages})
    "fallback-response")
  (stream [_ _ _] (async/to-chan! []))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ request]
    (swap! requests conj {:mode :invoke :request request})
    (let [response (first (first (swap-vals! responses rest)))
          response (if (instance? clojure.lang.IDeref response)
                     @response
                     response)
          response* (merge {:role "assistant"
                            :content ""
                            :tool-calls []
                            :usage nil
                            :raw nil}
                           (if (map? response)
                             response
                             {:content (or response "")}))]
      (when-let [on-content-delta (:on-content-delta request)]
        (when-let [chunks (:stream-chunks response*)]
          (doseq [chunk chunks]
            (on-content-delta chunk))))
      (when-let [on-thinking-delta (:on-thinking-delta request)]
        (when-let [chunks (:thinking-chunks response*)]
          (doseq [chunk chunks]
            (on-thinking-delta chunk))))
      (dissoc response* :stream-chunks :thinking-chunks)))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defrecord FailingProvider []
  llm-core/ILLMProvider
  (complete [_ _ _]
    (throw (llm-core/llm-error :http-error "LLM request failed: 400")))
  (stream [_ _ _] (async/to-chan! []))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    (throw (llm-core/llm-error :http-error "LLM request failed: 400")))
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defrecord BlockingProvider [started response]
  llm-core/ILLMProvider
  (complete [_ _ _] "fallback-response")
  (stream [_ _ _] (async/to-chan! []))
  (embed [_ _ _] [])
  (list-models [_] [])
  (get-capabilities [_ _] {:supports-tools true})
  (estimate-cost [_ _ _] {:tokens 1 :cost-usd 0.0})
  llm-core/ILLMProviderInvoke
  (invoke [_ _]
    (deliver started true)
    @response)
  (generate [this messages opts]
    (llm-core/invoke this (assoc opts :messages messages))))

(defn temp-db-path []
  (.getAbsolutePath (java.io.File/createTempFile "iris-chat-" ".db")))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "iris-chat-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn tool-call-response
  ([tool-name args] (tool-call-response (str "call_" (name tool-name)) tool-name args))
  ([id tool-name args]
   {:tool-calls [{:id id
                  :type "function"
                  :function {:name (name tool-name)
                             :arguments (json/generate-string args)}}]}))

(defn message-text [message]
  (llm-messages/content-text message))

(defn dialogue-texts [messages]
  (mapv message-text (filter #(contains? #{"user" "assistant"} (:role %)) messages)))

(defn test-system [path provider config-fn]
  (let [base (system/create-system)
        store (sqlite/create-store {:path path})
        event-bus (events/create-event-bus)
        event-sink (events/create-event-sink store event-bus)
        config (config-fn (:config base))]
    (assoc base
           :llm-provider provider
           :note-llm-provider provider
           :store store
           :event-bus event-bus
           :event-sink event-sink
           :tool-registry (tool-service/create-tool-registry {:cfg (:tools config) :event-sink event-sink :store store})
           :memory-service (memory/create-memory-service (:memory config) store)
           :config config)))

(defn custom-tool [name f metadata]
  (tools/create-tool
   {:description (tools/create-tool-description
                  name
                  (str name)
                  :input-schema [:map [:value :int]]
                  :required-permissions #{}
                  :operation (:operation metadata)
                  :parallel-safe? (:parallel-safe? metadata)
                  :approval-sensitive? (:approval-sensitive? metadata)
                  :activates-tools? (:activates-tools? metadata))
    :execute-fn f}))

(defn custom-registry [& tools*]
  (reduce tools/register-tool (tools/create-registry) tools*))

(defn eventually
  [pred]
  (loop [remaining 20]
    (if (pred)
      true
      (when (pos? remaining)
        (Thread/sleep 25)
        (recur (dec remaining))))))
