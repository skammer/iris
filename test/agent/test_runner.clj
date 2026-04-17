(ns agent.test-runner
  (:require
   [agent.api-test]
   [agent.broker.local-test]
   [agent.channels.core-test]
   [clojure.test :as t]
   [agent.config-test]
   [agent.core-test]
   [agent.memory.core-test]
   [agent.orchestrator-test]
   [agent.persistence.sqlite-test]
   [agent.runners.docker-podman-e2e-test]
   [agent.runners.docker-podman-test]
   [agent.runners.local-process-test]
   [agent.runners.seatbelt-test]
   [agent.runtime.child-test]
   [agent.runtime.core-test]
   [agent.skills-test]
   [agent.tools.common.fs-test]
   [agent.llm.providers.ollama-test]
   [agent.llm.providers.openai-compatible-test]
   [agent.tools.common.http-test]
    [agent.tools.common.shell-test]
   [agent.tools.core-test]))

(def rewritten-test-namespaces
  '[agent.api-test
    agent.broker.local-test
    agent.channels.core-test
    agent.config-test
    agent.core-test
    agent.memory.core-test
    agent.orchestrator-test
    agent.persistence.sqlite-test
    agent.runners.docker-podman-e2e-test
    agent.runners.docker-podman-test
    agent.runners.local-process-test
    agent.runners.seatbelt-test
    agent.runtime.child-test
    agent.runtime.core-test
    agent.skills-test
    agent.tools.common.fs-test
    agent.llm.providers.ollama-test
    agent.llm.providers.openai-compatible-test
    agent.tools.common.http-test
    agent.tools.common.shell-test
    agent.tools.core-test])

(defn run-all-tests
  []
  (apply t/run-tests rewritten-test-namespaces))
