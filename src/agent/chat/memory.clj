(ns agent.chat.memory
  "Chat memory integration. Builds bounded recall context before a turn and
   extracts durable facts from the completed user/assistant exchange."
  (:require
   [agent.chat.util :as util]
   [agent.config :as config]
   [agent.memory.core :as memory]
   [agent.prompts :as prompts]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def memory-result-limit 5)
(def memory-max-chars 6000)

(def empty-recall
  {:prompt {:documents []}
	   :search {:messages []
	            :events []
	            :facts []}})

(defn- compact-memory-json
  "Serializes recalled memory to JSON, capped at memory-max-chars to keep
   recall payloads bounded."
  [value]
  (let [text (json/generate-string value)]
    (if (> (count text) memory-max-chars)
      (json/generate-string {:truncated true
                             :max-chars memory-max-chars
                             :preview (subs text 0 memory-max-chars)})
      text)))

(defn recall-memory [system session-id query request-id]
  (try
    (if-let [memory-service (:memory-service system)]
      (let [prompt (memory/read-prompt-memory memory-service)
            search (when-not (str/blank? (or query ""))
                     (memory/search-memory memory-service
                                           query
                                           {:limit memory-result-limit
                                            :session-id session-id
                                            :entity-type :session
                                            :entity-id session-id
                                            :scope {:type :session :id session-id}}))]
        {:prompt prompt
         :search (or search (:search empty-recall))})
      empty-recall)
    (catch Exception e
      (util/emit! system {:event-type :message-update
                          :entity-type :session
                          :entity-id session-id
                          :request-id request-id
                          :payload {:kind :memory-recall-failed
                                    :message (.getMessage e)
                                    :type (some-> e ex-data :type)}})
      empty-recall)))

(defn memory-message [recall]
  {:role "system"
   :content (prompts/render "memory-context"
                            {:memory_json (compact-memory-json recall)})})

(defn extract-turn-memory! [system session-id user-message assistant-message request-id]
  (when (and session-id user-message assistant-message)
    (try
      (memory/extract-and-save-facts!
       (:memory-service system)
       (or (:fact-llm-provider system) (:llm-provider system))
       {:user-message (:content user-message)
        :assistant-message (:content assistant-message)}
       {:session-id session-id
        :source-session-id session-id
        :source-message-ids [(:id user-message) (:id assistant-message)]
        :source-request-id request-id
        :model (config/active-model (get-in system [:config :llm]))})
      (catch Exception e
        (util/emit! system {:event-type :message-update
                            :entity-type :session
                            :entity-id session-id
                            :request-id request-id
                            :payload {:kind :memory-extract-failed
                                      :message (.getMessage e)
                                      :type (some-> e ex-data :type)}})))))
