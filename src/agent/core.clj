(ns agent.core
  "CLI entrypoint."
  (:gen-class)
  (:require
   [agent.cli :as cli]))

(defn -main
  [& args]
  (cli/main args))
