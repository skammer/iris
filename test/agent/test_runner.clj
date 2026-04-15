(ns agent.test-runner
  (:require
   [agent.api-test]
   [clojure.test :as t]
   [agent.config-test]
   [agent.core-test]
   [agent.persistence.sqlite-test]
   [agent.llm.providers.ollama-test]
   [agent.llm.providers.openai-compatible-test]))

(def rewritten-test-namespaces
  '[agent.api-test
    agent.config-test
    agent.core-test
    agent.persistence.sqlite-test
    agent.llm.providers.ollama-test
    agent.llm.providers.openai-compatible-test])

(defn run-all-tests
  []
  (apply t/run-tests rewritten-test-namespaces))
