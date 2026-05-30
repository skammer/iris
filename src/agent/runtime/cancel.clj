(ns agent.runtime.cancel
  "Cancellation helpers shared by runtime loop and tool execution.")

(defn cancelled-error []
  (ex-info "Chat stopped" {:type :chat-cancelled}))

(defn cancellation-token [value]
  (if (map? value)
    (or (:cancellation-token value) (:cancelled? value))
    value))

(defn cancelled? [value]
  (let [token (cancellation-token value)]
    (cond
      (nil? token) false
      (instance? clojure.lang.IDeref token) (true? @token)
      (fn? token) (true? (token))
      :else (true? token))))

(defn throw-if-cancelled! [value]
  (when (cancelled? value)
    (throw (cancelled-error))))
