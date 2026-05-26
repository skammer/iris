(ns agent.runtime.tool-router
  "Small-model tool schema reducer."
  (:require
   [clojure.set]
   [clojure.string :as str]))

(def respond-tool
  {:name :respond
   :description "Return final assistant answer to user. Use only when no more tool work is needed."
   :version "1.0.0"
   :category :respond
   :input-schema {:type "object"
                  :additionalProperties false
                  :required ["content"]
                  :properties {"content" {:type "string"}}}
   :required-permissions #{}
   :timeout-ms 1000
   :source :synthetic
   :sensitive false})

(def all-categories #{:respond :read :write :run :search :web :plan :messaging})

(defn- tool-name [tool]
  (keyword (:name tool)))

(defn- haystack [messages]
  (->> messages
       (map :content)
       (map str)
       (str/join "\n")
       str/lower-case))

(defn classify-tool [tool]
  (case (tool-name tool)
    :respond #{:respond}
    :fs #{:read :write}
    :shell #{:run}
    :http #{:web :read}
    :memory #{:search :read}
    :message_search #{:search :read}
    :todo #{:read :write :search :plan}
    :telegram_send_photo #{:messaging}
    :telegram_send_document #{:messaging}
    :system_reload #{:run}
    (case (:category tool)
      :respond #{:respond}
      :system #{:run}
      :messaging #{:messaging}
      :memory #{:search :read}
      #{:read})))

(defn infer-categories [messages]
  (let [text (haystack messages)
        categories (cond-> #{:respond}
                     (re-find #"\b(read|open|show|list|inspect|find|search)\b|найд|поищ|ищи|найти|найди|список|покаж" text)
                     (into [:read :search])
                     (re-find #"\b(write|edit|replace|create|delete|mkdir|fix|implement|patch)\b" text)
                     (conj :write)
                     (re-find #"\b(run|test|command|shell|exec|build)\b" text)
                     (conj :run)
                     (re-find #"\b(http|url|web|fetch|download|browser)\b" text)
                     (conj :web)
                     (re-find #"\b(send|message|telegram|attach|upload)\b|отправ|пришл|скинь|телеграм" text)
                     (conj :messaging))]
    (if (= categories #{:respond})
      all-categories
      categories)))

(defn route-tools
  [{:keys [tools profile messages]}]
  (let [tools* (cond-> (vec (or tools []))
                 (:respond-tool? profile) (conj respond-tool))
        categories (set (or (:tool-categories profile)
                            (when (:tool-routing? profile)
                              (infer-categories messages))
                            all-categories))
        selected (if (:tool-routing? profile)
                   (filterv (fn [tool]
                              (seq (clojure.set/intersection categories
                                                             (classify-tool tool))))
                            tools*)
                   tools*)]
    {:tools selected
     :allowed-tools (set (map tool-name selected))
     :categories categories}))
