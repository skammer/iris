(ns agent.chat.memory
  "Chat memory integration. Builds bounded recall context before a turn."
  (:require
   [agent.chat.util :as util]
   [agent.memory.recall :as recall]
   [agent.prompts :as prompts]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def memory-result-limit 5)
(def memory-max-chars 6000)

(def empty-recall
  {:query nil
   :results []
   :surface-counts {:messages 0
                    :events 0
                    :vault-chunks 0}})

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
      (if-not (str/blank? (or query ""))
        (recall/recall memory-service
                       query
                       {:limit memory-result-limit
                        :session-id session-id
                        :entity-type :session
                        :entity-id session-id
                        :scope {:type :session :id session-id}})
        empty-recall)
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
