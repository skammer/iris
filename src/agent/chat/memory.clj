(ns agent.chat.memory
  "Chat memory integration. Builds bounded recall context before a turn."
  (:require
   [agent.chat.util :as util]
   [agent.memory.recall :as recall]
   [agent.persistence.sqlite :as sqlite]
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
  (loop [value* value]
    (let [text (json/generate-string value*)]
      (cond
        (<= (count text) memory-max-chars) text
        (seq (:results value*)) (recur (update value* :results pop))
        :else (json/generate-string (assoc value* :truncated true :results []))))))

(defn recall-memory [system session-id query request-id]
  (try
    (if-let [memory-service (:memory-service system)]
      (if-not (str/blank? (or query ""))
        (let [metadata (:metadata (sqlite/get-session (:store system) session-id))
              project-id (or (:project-id metadata) (:project_id metadata) (:project metadata))]
          (recall/recall memory-service
                         query
                         (cond-> {:limit memory-result-limit
                                  :session-id session-id
                                  :entity-type :session
                                  :entity-id session-id
                                  :scope {:type :session :id session-id}}
                           project-id (assoc :project-id (str project-id)))))
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
