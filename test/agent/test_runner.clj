(ns agent.test-runner
  (:require
   [agent.config :as config]
   [agent.api-test]
   [agent.api-smoke-test]
   [agent.broker.local-test]
   [agent.channels.core-test]
   [agent.chat-harness-test]
   [agent.chat-test]
   [clojure.test :as t]
   [agent.config-test]
   [agent.federation.http-test]
   [agent.system-test]
   [agent.telegram.format-test]
   [agent.telegram-test]
   [agent.telemetry-test]
   [agent.kernel-test]
   [agent.logging-test]
   [agent.memory.core-test]
   [agent.mcp.core-test]
   [agent.orchestrator-test]
   [agent.persistence.sqlite-test]
   [agent.planner-test]
   [agent.release-smoke-test]
   [agent.runners.docker-podman-e2e-test]
   [agent.runners.docker-podman-test]
   [agent.runners.local-unsandboxed-test]
   [agent.runners.seatbelt-test]
   [agent.runtime.child-test]
   [agent.runtime.core-test]
   [agent.runtime.schema-test]
   [agent.runtime.trace-test]
   [agent.skills-test]
   [agent.ui-test]
   [agent.tools.common.fs-test]
   [agent.tools.common.memory-test]
   [agent.llm.providers.ollama-test]
   [agent.llm.core-test]
   [agent.llm.messages-test]
   [agent.llm.dsml-test]
   [agent.llm.providers.openai-compatible-test]
   [agent.tools.common.http-test]
   [agent.tools.common.telegram-test]
   [agent.tools.common.shell-test]
   [agent.tools.core-test]
   [clojure.java.io :as io]))

(def rewritten-test-namespaces
  '[agent.api-test
    agent.api-smoke-test
    agent.broker.local-test
    agent.channels.core-test
    agent.chat-harness-test
    agent.chat-test
    agent.config-test
    agent.federation.http-test
    agent.system-test
    agent.telegram.format-test
    agent.telegram-test
    agent.telemetry-test
    agent.kernel-test
    agent.logging-test
    agent.memory.core-test
    agent.mcp.core-test
    agent.orchestrator-test
    agent.persistence.sqlite-test
    agent.planner-test
    agent.release-smoke-test
    agent.runners.docker-podman-e2e-test
    agent.runners.docker-podman-test
    agent.runners.local-unsandboxed-test
    agent.runners.seatbelt-test
    agent.runtime.child-test
    agent.runtime.core-test
    agent.runtime.schema-test
    agent.runtime.trace-test
    agent.skills-test
    agent.ui-test
    agent.tools.common.fs-test
    agent.tools.common.memory-test
    agent.llm.providers.ollama-test
    agent.llm.core-test
    agent.llm.messages-test
    agent.llm.dsml-test
    agent.llm.providers.openai-compatible-test
    agent.tools.common.http-test
    agent.tools.common.telegram-test
    agent.tools.common.shell-test
    agent.tools.core-test])

(defn run-all-tests
  []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "iris-test-runner-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (.mkdirs (io/file root "home"))
      (binding [config/*user-home* (fn [] (.getPath (io/file root "home")))
                config/*env* (fn [k]
                               (when-not (#{"IRIS_CONFIG_DIR" "XDG_CONFIG_HOME"} k)
                                 (System/getenv k)))]
        (apply t/run-tests rewritten-test-namespaces))
      (finally
        (io/delete-file root true)))))

(defn -main
  [& _args]
  (let [{:keys [fail error]} (run-all-tests)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
