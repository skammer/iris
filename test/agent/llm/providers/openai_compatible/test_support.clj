(ns agent.llm.providers.openai-compatible.test-support)

(defn byte-stream [text]
  (java.io.ByteArrayInputStream. (.getBytes text "UTF-8")))

(defn closing-byte-stream [text closed?]
  (proxy [java.io.ByteArrayInputStream] [(.getBytes text "UTF-8")]
    (close []
      (reset! closed? true)
      (proxy-super close))))
