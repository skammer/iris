(ns agent.prompts
  "Classpath-backed prompt templates."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def prompts-root "prompts")

(def ^:private bundled-mode-names
  ["ask"
   "brainstorm"
   "code"
   "debug"
   "default"
   "frontend-design"
   "plan"
   "review"
   "review-security"
   "simplify"
   "write-prompt"])

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

(defn list-modes []
  bundled-mode-names)

(defn- mode-name [mode]
  (some-> mode name str/trim not-empty))

(defn get-mode
  [mode]
  (let [mode* (mode-name mode)]
    (when-not (some #{mode*} bundled-mode-names)
      (throw (ex-info "Prompt mode not found"
                      {:type :prompt-mode-not-found
                       :mode mode
                       :available-modes bundled-mode-names})))
    (load-prompt (str "modes/" mode*))))

(defn apply-mode
  [messages mode]
  (if (some-> mode mode-name)
    (into [{:role "system"
            :content (get-mode mode)}]
          (or messages []))
    (vec (or messages []))))
