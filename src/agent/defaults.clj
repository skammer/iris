(ns agent.defaults
  "Shared runtime defaults used when config omits optional values.")

(def llm-temperature 0.2)
(def llm-max-tokens 1024)

(def chat-max-steps 6)
(def tool-output-max-chars 8000)

(def broker-channel-buffer-size 64)
(def broker-block-timeout-ms 1000)
(def agent-inbox-buffer-size 64)
(def channel-bus-buffer-size 128)
(def event-stream-buffer-size 256)
