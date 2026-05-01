(ns agent.prompts
  "Classpath-backed prompt templates."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def prompts-root "prompts")

(defn load-prompt
  [prompt-name]
  (let [path (str prompts-root "/" prompt-name ".md")]
    (if-let [resource (io/resource path)]
      (slurp resource)
      (throw (ex-info "Prompt resource not found"
                      {:type :prompt-resource-not-found
                       :path path})))))

(defn render
  [prompt-name values]
  (reduce-kv (fn [template k v]
               (str/replace template
                            (str "{{" (name k) "}}")
                            (str (or v ""))))
             (load-prompt prompt-name)
             values))
