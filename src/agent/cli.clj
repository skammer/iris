(ns agent.cli
  "Command-line parsing and dispatch."
  (:require
   [agent.logging :as logging]
   [agent.system :as system]
   [clojure.string :as str]))

(defn usage []
  (str/join
   \newline
   ["Usage:"
    "  clojure -M -m agent.core \"prompt text\""
    "  clojure -M -m agent.core serve"
    "  clojure -M -m agent.core --config path/to/config.edn \"prompt text\""]))

(defn parse-args [args]
  (let [[config-path rest-args] (if (= "--config" (first args))
                                  [(second args) (drop 2 args)]
                                  [nil args])]
    {:config-path config-path
     :command (first rest-args)
     :prompt (str/join " " rest-args)}))

(defn main [args]
  (let [{:keys [config-path command prompt]} (parse-args args)]
    (cond
      (= "serve" command)
      (let [system (system/start-api! (system/create-system config-path))
            {:keys [host port]} (:api (:config system))]
        (logging/log! :agent.cli/serve {:host host :port port})
        (println (str "API listening on http://" host ":" port))
        @(promise))

      (str/blank? prompt)
      (do
        (binding [*out* *err*]
          (println (usage)))
        (System/exit 1))

      :else
      (let [system (system/create-system config-path)
            response (system/complete system prompt)]
        (logging/log! :agent.cli/prompt {:prompt-length (count prompt)})
        (println response)))))
