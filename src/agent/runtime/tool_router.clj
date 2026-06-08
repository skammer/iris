(ns agent.runtime.tool-router
  "Small-model tool schema reducer."
  (:require
   [clojure.set]
   [clojure.string :as str]))

(def ^:private respond-tool
  {:name :respond
   :description "Return final assistant answer to user. Use only when no more tool work is needed."
   :version "1.0.0"
   :category :respond
   :operation :read
   :routing-categories #{:respond}
   :input-schema {:type "object"
                  :additionalProperties false
                  :required ["content"]
                  :properties {"content" {:type "string"}}}
   :required-permissions #{}
   :timeout-ms 1000
   :source :synthetic
   :sensitive false})

(def ^:private all-categories #{:respond :read :write :run :search :web :plan :messaging})

(defn- tool-name [tool]
  (keyword (:name tool)))

(defn- haystack [messages]
  (->> messages
       (map :content)
       (map str)
       (str/join "\n")
       str/lower-case))

(defn- normalize-category [category]
  (cond
    (keyword? category) category
    (string? category) (keyword (str/lower-case category))
    :else category))

(defn- normalize-categories [categories]
  (set (keep normalize-category categories)))

(defn- legacy-category-routing [tool]
  (let [category (normalize-category (:category tool))
        operation (normalize-category (:operation tool))]
    (case category
      :respond #{:respond}
      :messaging #{:messaging}
      :api #{:web :read}
      :memory (if (= :act operation) #{:write :plan} #{:read :search :plan})
      :system (if (= :act operation) #{:write :run} #{:read :search})
      :mcp (if (= :act operation) #{:write :run :web} #{:read :search :web})
      (case operation
        :act #{:write}
        :read #{:read}
        #{:read}))))

(defn- classify-tool [tool]
  (or (not-empty (normalize-categories (:routing-categories tool)))
      (legacy-category-routing tool)))

(defn- infer-categories [messages]
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
	                              (or (= :respond (tool-name tool))
	                                  (seq (clojure.set/intersection categories
	                                                                 (classify-tool tool)))))
	                            tools*)
                   tools*)]
    {:tools selected
     :allowed-tools (set (map tool-name selected))
     :categories categories}))
