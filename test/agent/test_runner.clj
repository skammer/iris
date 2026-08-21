(ns agent.test-runner
  (:require
   [agent.config :as config]
   [agent.api-test]
   [agent.api-smoke-test]
   [agent.api.event-compat-test]
   [agent.cli-test]
   [agent.cli.render-test]
   [agent.broker.local-test]
   [agent.channels.core-test]
   [agent.chat-harness-test]
   [agent.chat.streaming-test]
   [agent.chat.turn-test]
   [agent.chat-test]
   [clojure.test :as t]
   [endive-clj.core-test]
   [agent.config-test]
   [agent.cron-test]
   [agent.health-test]
   [agent.system-test]
   [agent.telegram.format-test]
   [agent.telegram.rich-test]
   [agent.telegram.streaming-test]
   [agent.telegram-test]
   [agent.telemetry-test]
   [agent.kernel-test]
   [agent.logging-test]
   [agent.memory.core-test]
   [agent.memory.idle-test]
   [agent.memory.magi-review-test]
   [agent.memory.user-profile-test]
   [agent.mcp.core-test]
   [agent.persistence.session-entries-test]
   [agent.persistence.sqlite-test]
   [agent.planner-test]
   [agent.release-smoke-test]
   [agent.restart-handoff-test]
   [agent.runtime.compaction-test]
   [agent.runtime.context-pack-test]
   [agent.runtime.loop-test]
   [agent.runtime.messages-test]
   [agent.runtime.nudge-test]
   [agent.runtime.schema-test]
   [agent.runtime.tool-router-test]
   [agent.runtime.tools-test]
   [agent.runtime.trace-test]
   [agent.skills-test]
   [agent.ui-test]
   [agent.ui.render-test]
   [agent.tools.common.fs-test]
   [agent.tools.common.homeassistant-test]
   [agent.tools.common.memory-test]
   [agent.tools.common.skills-test]
   [agent.llm.registry-test]
   [agent.llm.providers.ollama-test]
   [agent.llm.core-test]
   [agent.llm.messages-test]
   [agent.llm.dsml-test]
   [agent.llm.providers.openai-compatible-test]
   [agent.loop-test]
   [agent.tools.common.http-test]
   [agent.tools.common.web-test]
   [agent.tools.common.telegram-test]
   [agent.tools.common.todo-test]
   [agent.tools.common.wasm-test]
   [agent.wasm.bundles-test]
   [agent.tools.common.shell-test]
   [agent.tools.core-test]
   [clojure.java.io :as io]))

(def rewritten-test-namespaces
  '[agent.api-test
    agent.api-smoke-test
    agent.api.event-compat-test
    agent.cli-test
    agent.cli.render-test
    agent.broker.local-test
    agent.channels.core-test
    agent.chat-harness-test
    agent.chat.streaming-test
    agent.chat.turn-test
    agent.chat-test
    endive-clj.core-test
    agent.config-test
    agent.cron-test
    agent.health-test
    agent.system-test
    agent.telegram.format-test
    agent.telegram.rich-test
    agent.telegram.streaming-test
    agent.telegram-test
    agent.telemetry-test
    agent.kernel-test
    agent.logging-test
    agent.memory.core-test
    agent.memory.idle-test
    agent.memory.magi-review-test
    agent.memory.user-profile-test
    agent.mcp.core-test
    agent.persistence.session-entries-test
    agent.persistence.sqlite-test
    agent.planner-test
    agent.release-smoke-test
    agent.restart-handoff-test
    agent.runtime.compaction-test
    agent.runtime.context-pack-test
    agent.runtime.loop-test
    agent.runtime.messages-test
    agent.runtime.nudge-test
    agent.runtime.schema-test
    agent.runtime.tool-router-test
    agent.runtime.tools-test
    agent.runtime.trace-test
    agent.skills-test
    agent.ui-test
    agent.ui.render-test
    agent.tools.common.fs-test
    agent.tools.common.homeassistant-test
    agent.tools.common.memory-test
    agent.tools.common.skills-test
    agent.llm.registry-test
    agent.llm.providers.ollama-test
    agent.llm.core-test
    agent.llm.messages-test
    agent.llm.dsml-test
    agent.llm.providers.openai-compatible-test
    agent.loop-test
    agent.tools.common.http-test
    agent.tools.common.web-test
    agent.tools.common.telegram-test
    agent.tools.common.todo-test
    agent.tools.common.wasm-test
    agent.wasm.bundles-test
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
